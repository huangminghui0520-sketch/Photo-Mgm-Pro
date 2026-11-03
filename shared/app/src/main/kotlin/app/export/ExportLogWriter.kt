// shared/app/export/ExportLogWriter.kt —— export.log（JSON Lines）
//
// 规格（方案 §4.5-④）：
//   · UTF-8 无 BOM，每行一个 JSON 对象，每次导出**覆盖写**（不追加历史）；
//   · 字段：timestamp / level / event / relativePath / reason / bytes / durationMs；
//   · 末行 export_end 带 summary{total,success,skipped,failed}；
//   · 目的：机器可读（双端比对、排障脚本可直接 jq）+ 人可读（记事本打开就能看）。
//
// ★ T11 提交②：本类从 `desktop/core/export` **原样上移**（此前只有 PC 写这份日志，
//   安卓导出没有任何机器可读的产物，出问题只能靠用户复述"底栏写了什么"）。
//   上移而不是各写一份的理由与 `ExportPlanner` 相同：日志格式是"两端产物对拍"的基准，
//   两份实现必然在字段名/顺序上漂移，而漂移的症状是"对拍脚本只说对不上"。
//
// ★ 为什么上移后仍是"写文件"而不是"写 Appendable"：两端都不需要把日志塞进产物
//   （PC 的导出目录本身是文件夹，安卓的 ZIP 里放一份日志会让"逐条对拍 ZIP 条目"多一条噪音），
//   都写在自己的私有目录里供排障取用。若将来真需要打进 ZIP，再抽 `Appendable` 不迟 ——
//   现在多一层抽象只会让"关文件"这件事变成调用方的责任。
package app.export

import app.infra.Json
import app.infra.json
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 导出日志写入器。
 *
 * ★ 所有写入都是"尽力而为"：磁盘满、目录不可写时**静默放弃**（`writer()` 返回 null），
 *   调用方无需 try/catch。日志是排障辅助，不能因为写不了日志就让一次成功的导出报失败。
 */
class ExportLogWriter(
    private val file: Path,
    private val zone: ZoneId,
) : AutoCloseable {

    private var writer: BufferedWriter? = null

    private fun writer(): BufferedWriter? {
        writer?.let { return it }
        return runCatching {
            file.parent?.let { Files.createDirectories(it) }
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
    private fun line(emit: app.infra.JsonObjBuilder.() -> Unit) {
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
