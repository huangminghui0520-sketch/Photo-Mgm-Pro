// shared/app/export/ExportPlanner.kt —— 导出**计划**的唯一生成处（T09-C3）
//
// 这一段逻辑此前有两份实现：
//   · 桌面 `PcFolderExporter.buildPlan`（自己算文件夹、重名、未匹配）；
//   · 安卓 `algorithm.ZipExporter.export` 内部（另算一遍，注释写着"与桌面端一致"）。
// 两份都在**执行**里，于是"计划"这件事没有单一事实来源：改重名后缀规则时，
// 只有一端会变，而症状是"PC 导出的文件名多一个 _2、安卓少一个"，没人会立刻想到是这里。
//
// 现在计划在这里产出（纯数据），执行留给两端的 [app.port.ExportExecutor]。
//
// ★ 顺序与命名规则逐条搬自既有实现，**不改语义**：
//   1. 事件按 `endTimeMs` 升序（无时间事件排最后）——决定文件夹序号与写入顺序；
//   2. 文件夹名 = `NN_` 前缀 + 事件类型/日志内容（`ZipExporter.numberedFolderNames`，与台账前缀同源）；
//   3. 事件无照片 → **不建文件夹**（空目录在 ZIP 里也会被解压出来，用户会以为导出失败）；
//   4. 重名后缀从 `_2` 开始（不是 `_1`），插在扩展名前；无扩展名绝不拼尾随点；
//   5. 同一文件夹内同一张照片只写一份（分类结果里偶尔会重复列出同一 id）；
//   6. 未匹配照片统一进 `99_未分类`（`99_` 保证按名称排序时始终最后）。
//
// ★ 相对路径分隔符固定 `/`：它是 ZIP 条目的语法，也是台账照片列标签的分隔符。
package app.export

import algorithm.AppZone
import algorithm.ZipExporter
import algorithm.model.LogEvent
import algorithm.model.Photo
import app.api.ExportFile
import app.api.ExportPlan
import app.api.ExportRename
import domain.ledger.LedgerProjection
import java.time.ZoneId

/** 导出计划生成器（纯函数，不读文件、不写文件）。 */
object ExportPlanner {

    /**
     * 生成导出计划。
     *
     * @param eventPhotoMap **已做三态展开**的事件照片表（预览、台账、导出必须同源）。
     * @param unmatched 有效未匹配照片 id（已扣除被移入事件的、加上被移除的）。
     * @param photos 本次分类涉及的全部照片（用于 id → Photo 反查与台账标签）。
     * @param marked 人工台账标记（影响台账选照片，不影响文件清单）。
     * @param namingTemplate 0=事件类型 / 1=巡查日志内容。
     * @param includeLedger false 时计划里不带台账行（仅排障/快速复制照片）。
     */
    fun build(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        unmatched: List<Long>,
        photos: List<Photo>,
        marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
        namingTemplate: Int = 0,
        includeLedger: Boolean = true,
        zone: ZoneId = AppZone.ID,
    ): ExportPlan {
        val byId: Map<Long, Photo> = photos.associateBy { it.id }
        val folderNames = ZipExporter(zone).numberedFolderNames(events, namingTemplate)
        val ordered = events.sortedBy { it.endTimeMs ?: Long.MAX_VALUE }

        val files = LinkedHashMap<String, ExportFile>()
        val renames = mutableListOf<ExportRename>()
        // 每个文件夹内已写过的 photoId（同一张照片在同一事件里被列两次时只保留一份）
        val usedIds = HashMap<String, MutableSet<Long>>()

        fun put(folder: String, photo: Photo) {
            // 没有 sourceRef 的照片根本读不到字节，写进计划只会让执行器在报错里多一条噪音
            if (photo.sourceRef.isBlank()) return
            val ids = usedIds.getOrPut(folder) { HashSet() }
            if (!ids.add(photo.id)) return
            val base = photo.displayName.ifBlank {
                photo.sourceRef.substringAfterLast('/').substringAfterLast('\\')
            }
            var name = base
            var k = 2
            while ("$folder/$name" in files) {
                name = suffixed(base, k)
                k++
            }
            val relativePath = "$folder/$name"
            if (name != base) renames += ExportRename(originalName = base, toRelativePath = relativePath)
            files[relativePath] = ExportFile(
                relativePath = relativePath,
                sourceRef = photo.sourceRef,
                photoId = photo.id,
            )
        }

        for (event in ordered) {
            val folder = folderNames[event.id] ?: continue
            val eventPhotos = (eventPhotoMap[event.id] ?: emptyList()).mapNotNull { byId[it] }
            // 事件无照片不建文件夹（与两端既有行为一致）
            if (eventPhotos.isEmpty()) continue
            eventPhotos.forEach { put(folder, it) }
        }
        unmatched.mapNotNull { byId[it] }.forEach { put(ExportPlan.UNMATCHED_FOLDER, it) }

        // 台账：原始行给执行器写 xlsx，展示行给 UI 回填（同一次计算，同序同数）
        val ledger = if (includeLedger) {
            LedgerProjection.project(
                events = events,
                eventPhotoMap = eventPhotoMap,
                photos = photos,
                marked = marked,
                namingTemplate = namingTemplate,
                zone = zone,
            )
        } else {
            null
        }

        return ExportPlan(
            namingTemplate = namingTemplate,
            includeLedger = includeLedger,
            folderNames = folderNames,
            files = files.values.toList(),
            unmatchedFolder = ExportPlan.UNMATCHED_FOLDER,
            ledger = ledger?.rows.orEmpty(),
            ledgerPreview = ledger?.projected.orEmpty(),
            renames = renames,
            warnings = emptyList(),
        )
    }

    /** 重名后缀：`IMG_001.jpg` → `IMG_001_2.jpg`；无扩展名 → `IMG_001_2`（绝不拼出尾随点）。 */
    internal fun suffixed(base: String, k: Int): String {
        val dot = base.lastIndexOf('.')
        // dot > 0：点号在首位（`.gitignore` 这类隐藏名）不算扩展名分隔符，否则会拼成 ``_2.gitignore``
        return if (dot > 0) "${base.substring(0, dot)}_$k${base.substring(dot)}" else "${base}_$k"
    }
}
