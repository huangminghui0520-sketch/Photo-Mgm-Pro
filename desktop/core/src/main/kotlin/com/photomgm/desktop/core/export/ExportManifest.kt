// desktop/core/export/ExportManifest.kt —— 导出清单（断点续传的唯一判据）
//
// 与 Android 端 `.export_state.json` 的关键差别：
//   ★ 清单**放在导出根内部**，随产物走。Android 1.3.2 曾因状态文件按巡查日期存放、与输出位置解耦，
//     换输出目录后仍提示"已完成"，实际零产出 —— 本设计从根上消除该问题（换目录 = 没有清单 = 重新导出）。
package com.photomgm.desktop.core.export

import com.photomgm.desktop.core.infra.Json
import com.photomgm.desktop.core.infra.JsonValue
import com.photomgm.desktop.core.infra.arrOrNull
import com.photomgm.desktop.core.infra.boolOrNull
import com.photomgm.desktop.core.infra.intOrNull
import com.photomgm.desktop.core.infra.json
import com.photomgm.desktop.core.infra.longOrNull
import com.photomgm.desktop.core.infra.asObj
import com.photomgm.desktop.core.infra.strOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** 单个产物的留证记录。 */
data class ManifestEntry(
    val relativePath: String,
    val size: Long,
    val sha256: String,
    val modifiedMs: Long,
)

/**
 * 导出清单。
 *
 * @param photoSetHash 目标集合指纹（相对路径 + 源文件大小），用于判定"这次要导的东西和上次是否同一批"
 * @param ledgerFingerprint 台账内容指纹（行内容哈希）；台账随人工标记变化，必须一并比对
 * @param complete 上次是否完整跑完（失败/取消 → false，永不命中续传）
 */
data class ExportManifest(
    val version: Int = CURRENT_VERSION,
    val namingTemplate: Int,
    val photoSetHash: String,
    val ledgerFingerprint: String?,
    val complete: Boolean,
    val createdAt: String,
    val entries: List<ManifestEntry>,
) {

    fun toJson(): String = Json.write(
        json {
            put("version", version)
            put("namingTemplate", namingTemplate)
            put("photoSetHash", photoSetHash)
            put("ledgerFingerprint", ledgerFingerprint)
            put("complete", complete)
            put("createdAt", createdAt)
            putArray(
                "entries",
                entries.map { e ->
                    json {
                        put("relativePath", e.relativePath)
                        put("size", e.size)
                        put("sha256", e.sha256)
                        put("modifiedMs", e.modifiedMs)
                    }
                },
            )
        },
        pretty = true,
    )

    companion object {
        const val CURRENT_VERSION = 1
        const val FILE_NAME = "export-manifest.json"

        fun read(file: Path): ExportManifest? {
            if (!Files.isRegularFile(file)) return null
            val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return null
            return parse(text)
        }

        fun parse(text: String): ExportManifest? {
            val obj = Json.parse(text)?.asObj() ?: return null
            val entries = obj.arrOrNull("entries")?.mapNotNull { raw ->
                val e = raw as? JsonValue.Obj ?: return@mapNotNull null
                ManifestEntry(
                    relativePath = e.strOrNull("relativePath") ?: return@mapNotNull null,
                    size = e.longOrNull("size") ?: return@mapNotNull null,
                    sha256 = e.strOrNull("sha256") ?: "",
                    modifiedMs = e.longOrNull("modifiedMs") ?: 0L,
                )
            } ?: emptyList()
            return ExportManifest(
                version = obj.intOrNull("version") ?: 0,
                namingTemplate = obj.intOrNull("namingTemplate") ?: return null,
                photoSetHash = obj.strOrNull("photoSetHash") ?: return null,
                ledgerFingerprint = obj.strOrNull("ledgerFingerprint"),
                complete = obj.boolOrNull("complete") ?: false,
                createdAt = obj.strOrNull("createdAt") ?: "",
                entries = entries,
            )
        }

        fun write(file: Path, manifest: ExportManifest) {
            runCatching {
                Files.createDirectories(file.parent)
                Files.writeString(
                    file, manifest.toJson(), Charsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                )
            }
        }
    }
}
