// desktop/core/sync/SyncState.kt —— 导入续传状态 sync-state.json（桌面平台适配器）
//
// 与导出侧 ExportManifest 的关系：**两套状态，互不相干**。
//   ExportManifest 是导出根内部的 export-manifest.json（续传校验强度由设置页控制）；
//   本文件是手机导入子文件夹内部的 sync-state.json（文件名 + 大小 + 哈希去重）。
//   绝不复用、绝不共用一个文件名 —— 否则一次导入会把导出清单当成自己的历史。
//
// 存放位置：子文件夹内部。这样「换源目录 = 没有状态 = 全量重同步」是天然的，
// 不需要额外记录绝对路径；子文件夹已存在时不重建、不清空，直接读它。
//
// JSON 用 app.infra.Json（零三方依赖），解析失败一律返回 null：
// 一个坏文件不能把程序卡死，退化为「全量重同步」即可，§5.2 的判据仍会去重。
package com.photomgm.desktop.core.sync

import algorithm.AppZone
import app.infra.Json
import app.infra.JsonValue
import app.infra.arrOrNull
import app.infra.asObj
import app.infra.boolOrNull
import app.infra.intOrNull
import app.infra.json
import app.infra.longOrNull
import app.infra.objOrNull
import app.infra.strOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 区间快照：带完整年份与业务时区偏移，用于跨年同名（31_2200__01_0200）消歧。
internal data class SyncRangeStamp(val start: String, val end: String)

internal data class SyncFileRecord(val name: String, val size: Long, val sha256: String)

internal data class SyncState(
    val version: Int = CURRENT_VERSION,
    val range: SyncRangeStamp,
    val phoneDir: String,
    val files: List<SyncFileRecord>,
    val updatedAt: String,
    // 上次是否完整跑完；取消为 false，但已写入的文件记录仍保留。
    val complete: Boolean = true,
) {
    fun toJson(): String = Json.write(
        json {
            put("version", version)
            put("range", json {
                put("start", range.start)
                put("end", range.end)
            })
            put("phoneDir", phoneDir)
            put("complete", complete)
            put("updatedAt", updatedAt)
            putArray("files", files.map { f ->
                json {
                    put("name", f.name)
                    put("size", f.size)
                    put("sha256", f.sha256)
                }
            })
        },
        pretty = true,
    )

    companion object {
        const val CURRENT_VERSION = 1
        const val FILE_NAME = "sync-state.json"

        // 偏移格式：秒固定两位、偏移带冒号，与指令 §5.3 示例一致。
        private val OFFSET_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        // 挂钟时间 + 业务时区 → 带偏移的 ISO 文本（F7：offset 只在序列化时补）。
        fun stamp(start: LocalDateTime, end: LocalDateTime): SyncRangeStamp =
            SyncRangeStamp(
                start.atZone(AppZone.ID).format(OFFSET_FMT),
                end.atZone(AppZone.ID).format(OFFSET_FMT),
            )

        fun read(dir: Path): SyncState? {
            val file = dir.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return null
            val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return null
            return parse(text)
        }

        fun parse(text: String): SyncState? {
            val obj = Json.parse(text)?.asObj() ?: return null
            val rangeObj = obj.objOrNull("range") ?: return null
            val start = rangeObj.strOrNull("start") ?: return null
            val end = rangeObj.strOrNull("end") ?: return null
            val files = obj.arrOrNull("files")?.mapNotNull { raw ->
                val e = raw as? JsonValue.Obj ?: return@mapNotNull null
                SyncFileRecord(
                    name = e.strOrNull("name") ?: return@mapNotNull null,
                    size = e.longOrNull("size") ?: return@mapNotNull null,
                    sha256 = e.strOrNull("sha256") ?: "",
                )
            } ?: emptyList()
            return SyncState(
                version = obj.intOrNull("version") ?: 0,
                range = SyncRangeStamp(start, end),
                phoneDir = obj.strOrNull("phoneDir") ?: return null,
                files = files,
                updatedAt = obj.strOrNull("updatedAt") ?: "",
                complete = obj.boolOrNull("complete") ?: false,
            )
        }

        fun write(dir: Path, state: SyncState) {
            val file = dir.resolve(FILE_NAME)
            runCatching {
                Files.createDirectories(file.parent)
                Files.writeString(
                    file, state.toJson(), Charsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                )
            }
        }
    }
}

// 上次记录与本次区间/目录的冲突类型。
internal enum class SyncConflict {
    // 同一区间上次从另一手机目录同步。
    PHONE_DIR,

    // 文件夹名不含年份，记录里的年份与本次不同（跨年同名）。
    YEAR,
}

internal object SyncStateGuard {

    // 从带偏移的 ISO 文本读年份；解析不了返回 null（按「无冲突」保守处理）。
    fun yearOf(stamp: String): Int? = stamp.take(4).toIntOrNull()

    // 冲突检测：不静默合并、不静默续传，返回需要用户确认的原因。
    // 顺序：先看目录（更常见的误操作），再看年份（跨年同名）。
    // 记录不存在 / 本次显式确认覆盖 → null。
    fun conflict(
        existing: SyncState?,
        phoneDir: String,
        start: LocalDateTime,
        end: LocalDateTime,
        overwrite: Boolean = false,
    ): SyncConflict? {
        if (overwrite || existing == null) return null
        if (existing.phoneDir != phoneDir) return SyncConflict.PHONE_DIR
        val startYear = yearOf(existing.range.start)
        val endYear = yearOf(existing.range.end)
        if (startYear != null && startYear != start.year) return SyncConflict.YEAR
        if (endYear != null && endYear != end.year) return SyncConflict.YEAR
        return null
    }

    fun prompt(conflict: SyncConflict, existing: SyncState?, phoneDir: String): String = when (conflict) {
        SyncConflict.PHONE_DIR ->
            "该区间上次从另一目录同步（${existing?.phoneDir ?: "未知"}），本次是 $phoneDir。" +
                "继续会沿用这批文件；若不是同一批照片，请先换回原目录。"
        SyncConflict.YEAR ->
            "该文件夹名不含年份，上次记录的是 ${yearOf(existing?.range?.start.orEmpty()) ?: "未知"} 年的区间。" +
                "继续会把不同年份的照片混进同一文件夹，请确认区间年份。"
    }
}
