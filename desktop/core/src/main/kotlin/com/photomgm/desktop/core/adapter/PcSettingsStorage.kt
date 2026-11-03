// desktop/core/adapter/PcSettingsStorage.kt —— 设置持久化（%APPDATA%\PhotoMgm\settings.json）
//
// 替代 Android 的 SharedPreferences。设计取舍：
//   · 用 JSON 文本而非 java.util.prefs：Windows 注册表在受限账户/漫游配置下行为不一致，
//     且"用户能直接看/改/备份一份配置文件"在运维上是加分项；
//   · 读失败一律回落默认值（坏文件不能把程序卡死在启动阶段）；
//   · 写失败不抛给调用方（磁盘满/只读盘时，程序应继续可用，只是设置不持久）。
//
// ★ T10：本类**同时是** `app.port.SettingsStorage` 的实现和窗口几何的保管者。
//   窗口几何从共享 `Settings` 上摘下来了（T09 的减法），但磁盘格式不能变：
//   `settings.json` 里仍然有 `window` 键，老配置照读、新配置照写。
//
// ★ 这里有一个必须写死的坑：**`save(settings)` 必须把已知的 window 原样写回**。
//   共享层的 `SettingsUseCase.updateSettings` 会频繁调 `save`（改个巡查日期就调一次），
//   而它交进来的 `Settings` 里**没有** window 这个字段。若 `save` 只按传入对象重写整个 JSON，
//   用户改一次日期就把窗口记忆清成 null —— 表现为"下次启动窗口回到默认位置"，
//   且没有任何报错。所以 window 由本类自己持有并在每次写盘时补回去。
package com.photomgm.desktop.core.adapter

import algorithm.DedupKeepMode
import app.api.Settings
import app.infra.Json
import app.infra.JsonValue
import app.infra.asObj
import app.infra.intOrNull
import app.infra.json
import app.infra.objOrNull
import app.infra.strOrNull
import app.infra.stringList
import app.port.SettingsStorage
import com.photomgm.desktop.core.WindowState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal class PcSettingsStorage(private val file: Path) : SettingsStorage {

    private val lock = Any()

    /** 窗口几何（桌面独有）。由 [load] / [saveWindow] 维护，[save] 时原样写回。 */
    private var window: WindowState? = null

    /** 最近一次读到/写入的窗口几何；UI 启动时读它决定首屏尺寸。 */
    fun windowState(): WindowState? = synchronized(lock) { window }

    override fun load(): Settings {
        val fallback = Settings()
        if (!Files.isRegularFile(file)) return fallback
        val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return fallback
        val obj = Json.parse(text)?.asObj() ?: return fallback

        synchronized(lock) { window = parseWindow(obj) }

        val date = obj.strOrNull("patrolDate")?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: fallback.patrolDate

        val mode = obj.strOrNull("dedupMode")?.let {
            runCatching { DedupKeepMode.valueOf(it) }.getOrNull()
        } ?: DedupKeepMode.KEEP_WATERMARKED

        return Settings(
            sourceDirs = obj.stringList("sourceDirs"),
            outputDir = obj.strOrNull("outputDir"),
            patrolDate = date,
            // 非法模板值一律回落默认：持久化里一个坏数字会让导出目录名变成 `0_`
            namingTemplate = Settings.sanitizeNamingTemplate(obj.intOrNull("namingTemplate") ?: Settings.NAMED_BY_LOG_CONTENT),
            dedupMode = mode,
        )
    }

    override fun save(settings: Settings) {
        synchronized(lock) { write(settings, window) }
    }

    /**
     * 保存窗口几何（关窗时调用）。
     *
     * @param settings 当前设置。窗口单独存不住——整个 JSON 是一次性重写的，
     *   所以必须把设置一起带上，否则会把刚改好的巡查日期/源目录写回旧值。
     */
    fun saveWindow(settings: Settings, state: WindowState?) {
        synchronized(lock) {
            window = state
            write(settings, state)
        }
    }

    private fun parseWindow(obj: app.infra.JsonValue.Obj): WindowState? =
        obj.objOrNull("window")?.let { w ->
            val x = w.intOrNull("x")
            val y = w.intOrNull("y")
            val width = w.intOrNull("width")
            val height = w.intOrNull("height")
            if (x != null && y != null && width != null && height != null) {
                WindowState(x, y, width, height)
            } else {
                null
            }
        }

    /** 原子写（先写 .tmp 再 move）：避免掉电/崩溃留下半个 JSON。 */
    private fun write(settings: Settings, window: WindowState?) {
        runCatching {
            Files.createDirectories(file.parent)
            val text = Json.write(
                json {
                    putStrings("sourceDirs", settings.sourceDirs)
                    put("outputDir", settings.outputDir)
                    put("patrolDate", settings.patrolDate.toString())
                    put("namingTemplate", settings.namingTemplate)
                    put("dedupMode", settings.dedupMode.name)
                    put(
                        "window",
                        window?.let { w ->
                            json {
                                put("x", w.x)
                                put("y", w.y)
                                put("width", w.width)
                                put("height", w.height)
                            }
                        } ?: JsonValue.Null,
                    )
                },
                pretty = true,
            )
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(
                tmp, text, Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
            )
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
