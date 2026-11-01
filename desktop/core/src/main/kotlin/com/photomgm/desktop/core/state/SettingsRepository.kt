// desktop/core/state/SettingsRepository.kt —— 设置持久化（%APPDATA%\PhotoMgm\settings.json）
//
// 替代 Android 的 SharedPreferences。设计取舍：
//   · 用 JSON 文本而非 java.util.prefs：Windows 注册表在受限账户/漫游配置下行为不一致，
//     且"用户能直接看/改/备份一份配置文件"在运维上是加分项；
//   · 读失败一律回落默认值（坏文件不能把程序卡死在启动阶段）；
//   · 写失败不抛给调用方（磁盘满/只读盘时，程序应继续可用，只是设置不持久）。
package com.photomgm.desktop.core.state

import algorithm.DedupKeepMode
import com.photomgm.desktop.core.api.Settings
import com.photomgm.desktop.core.api.WindowState
import com.photomgm.desktop.core.infra.Json
import com.photomgm.desktop.core.infra.boolOrNull
import com.photomgm.desktop.core.infra.intOrNull
import com.photomgm.desktop.core.infra.json
import com.photomgm.desktop.core.infra.asObj
import com.photomgm.desktop.core.infra.objOrNull
import com.photomgm.desktop.core.infra.strOrNull
import com.photomgm.desktop.core.infra.stringList
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal class SettingsRepository(private val file: Path) {

    fun load(): Settings {
        if (!Files.isRegularFile(file)) return Settings()
        val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return Settings()
        val obj = Json.parse(text)?.asObj() ?: return Settings()

        val date = obj.strOrNull("patrolDate")?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: Settings().patrolDate

        val mode = obj.strOrNull("dedupMode")?.let {
            runCatching { DedupKeepMode.valueOf(it) }.getOrNull()
        } ?: DedupKeepMode.KEEP_WATERMARKED

        val window = obj.objOrNull("window")?.let { w ->
            val x = w.intOrNull("x")
            val y = w.intOrNull("y")
            val width = w.intOrNull("width")
            val height = w.intOrNull("height")
            if (x != null && y != null && width != null && height != null) {
                WindowState(x, y, width, height)
            } else null
        }

        return Settings(
            sourceDirs = obj.stringList("sourceDirs"),
            outputDir = obj.strOrNull("outputDir"),
            patrolDate = date,
            namingTemplate = obj.intOrNull("namingTemplate")?.takeIf { it == 0 || it == 1 } ?: 1,
            dedupMode = mode,
            window = window,
        )
    }

    /** 原子写（先写 .tmp 再 move）：避免掉电/崩溃留下半个 JSON。 */
    fun save(settings: Settings) {
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
                        settings.window?.let { w ->
                            json {
                                put("x", w.x)
                                put("y", w.y)
                                put("width", w.width)
                                put("height", w.height)
                            }
                        } ?: com.photomgm.desktop.core.infra.JsonValue.Null,
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
