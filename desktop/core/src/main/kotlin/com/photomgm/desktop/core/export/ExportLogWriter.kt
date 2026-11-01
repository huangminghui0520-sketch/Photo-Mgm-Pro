// desktop/core/export/ExportLogWriter.kt —— export.log（JSON Lines）
//
// 规格（方案 §4.5-④）：
//   · UTF-8 无 BOM，每行一个 JSON 对象，每次导出**覆盖写**（不追加历史）；
//   · 字段：timestamp / level / event / relativePath / reason / bytes / durationMs；
//   · 末行 export_end 带 summary{total,success,skipped,failed}；
//   · 目的：机器可读（双端比对、排障脚本可直接 jq）+ 人可读（记事本打开就能看）。
package com.photomgm.desktop.core.export

import com.photomgm.desktop.core.infra.Json
import com.photomgm.desktop.core.infra.json
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class ExportLogWriter(
    private val file: Path,
    private val zone: ZoneId,
) : AutoCloseable {

    private var writer: BufferedWriter? = null

    private fun writer(): BufferedWriter? {
        writer?.let { return it }
        return runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(
                file, Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
            ).also { writer = it }
        }.getOrNull()
    }

    fun start(template: Int, targetCount: Int, includeLedger: Boolean) = line {
        put("event", "export_start")
        put("level", "info")
        put("template", template)
        put("targets", targetCount)
        put("includeLedger", includeLedger)
    }

    fun copy(relativePath: String, bytes: Long, durationMs: Long) = line {
        put("event", "copy")
        put("level", "info")
        put("relativePath", relativePath)
        put("bytes", bytes)
        put("durationMs", durationMs)
    }

    fun skip(relativePath: String, reason: String) = line {
        put("event", "skip")
        put("level", "info")
        put("relativePath", relativePath)
        put("reason", reason.take(120))
    }

    fun rename(from: String, to: String) = line {
        put("event", "rename")
        put("level", "warn")
        put("relativePath", to)
        put("reason", "重名改为 $to（原 $from）".take(120))
    }

    fun failed(relativePath: String, reason: String) = line {
        put("event", "failed")
        put("level", "error")
        put("relativePath", relativePath)
        put("reason", reason.take(120))
    }

    fun resumeHit(exportDir: String, count: Int) = line {
        put("event", "resume_skip")
        put("level", "info")
        put("relativePath", exportDir)
        put("reason", "上次导出已完整（$count 个产物校验一致），本次不重复复制")
    }

    fun end(total: Int, success: Int, skipped: Int, failed: Int, cancelled: Boolean, durationMs: Long) = line {
        put("event", "export_end")
        put("level", if (failed > 0) "error" else "info")
        put("durationMs", durationMs)
        put("cancelled", cancelled)
        put(
            "summary",
            json {
                put("total", total)
                put("success", success)
                put("skipped", skipped)
                put("failed", failed)
            },
        )
    }

    // 注意：参数不能命名为 build —— JsonObjBuilder 自带 build() 成员，会被解析成成员调用，
    // 导致外层传入的 lambda 从未执行（每行只剩 timestamp）。这类"静默空对象"坑过一次。
    private fun line(emit: com.photomgm.desktop.core.infra.JsonObjBuilder.() -> Unit) {
        val w = writer() ?: return
        val obj = json {
            emit()
            // timestamp 放在最后构造以免 JSON 键顺序被调用方覆盖；实际写盘顺序按插入顺序
            put("timestamp", nowIso())
        }
        runCatching {
            w.write(Json.write(obj))
            w.newLine()
        }
    }

    private fun nowIso(): String = Instant.now().atZone(zone).format(ISO_OFFSET)

    override fun close() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private companion object {
        val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
