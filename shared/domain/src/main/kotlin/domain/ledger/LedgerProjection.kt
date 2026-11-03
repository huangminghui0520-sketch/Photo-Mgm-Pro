// shared/domain/ledger/LedgerProjection.kt —— 台账行投影（T07）
//
// 台账「行」本身早就在共享层了（`LedgerEngine.buildLedger`），这一层补的是**两端各自
// 加在行上的那层展示加工**：
//
//   PC  `buildLedgerRows` → 每行再算 `photo1Label`/`photo2Label`（`文件夹名/文件名`）
//   安卓 直接吃 `LedgerRow`，照片列在写 xlsx 时才拼前缀
//
// 于是同一个事件在两端可能写出不同的照片列文本 —— 只要有一端忘了加 `folderName` 前缀，
// 或者 name 兜底规则不同（`substringAfterLast('/')` 只认 `/`，Windows 路径会给整串）。
// 这正是 `docs/CROSSPLATFORM-ARCH.md` D-a「台账单元格文本」要守的那一格。
//
// ★ 命名前缀与 ZIP 内路径必须一致（`docs/golden/README.md` §4.1）：
//   `folderName` 来自 [LedgerRow.folderName]，它和 ZIP 用的是同一个
//   `numberedFolderNames` 结果，本文件不重新生成文件夹名。
package domain.ledger

import algorithm.AlgorithmApi
import algorithm.AppZone
import algorithm.model.LedgerRow
import algorithm.model.LogEvent
import algorithm.model.Photo
import java.time.ZoneId

/**
 * 投影后的一行台账：业务字段 + 两端 UI/导出都需要的照片列标签。
 *
 * 与 PC 的 `LedgerRowInfo` 字段一一对应；`folderName` 保留是为了让调用方不必再从标签里反解。
 */
data class ProjectedLedgerRow(
    val dateTime: String,
    val endTime: String,
    val location: String,
    val description: String,
    /** 照片 1 的展示标签（形如 `01_护栏损坏/IMG_001.jpg`；无照片为 null）。 */
    val photo1Label: String?,
    val photo2Label: String?,
    val folderName: String,
)

object LedgerProjection {

    /**
     * 构建台账行。
     *
     * @param eventPhotoMap **已做三态展开**的事件照片表（`ViewDerivation.expandedEventMap` 的结果）。
     *   直接传 `effectiveMap` 会让台账照片列在「仅保留不带水印」时选到本该被过滤的那张。
     * @param photos 本次分类涉及的全部照片，用于把 `sourceRef` 反查成 `displayName`。
     *   顺序即反查优先级（同名 sourceRef 取靠前者），与 PC 的 `photosById.values` 一致。
     */
    fun buildRows(
        events: List<LogEvent>,
        eventPhotoMap: Map<Int, List<Long>>,
        photos: List<Photo>,
        marked: Map<Int, Pair<Long?, Long?>> = emptyMap(),
        namingTemplate: Int = 0,
        zone: ZoneId = AppZone.ID,
    ): List<ProjectedLedgerRow> {
        val byId: Map<Long, Photo> = photos.associateBy { it.id }
        val byRef: Map<String, Photo> = buildRefIndex(photos)
        return AlgorithmApi.buildLedger(
            events = events,
            eventPhotoMap = eventPhotoMap,
            photoById = { byId[it] },
            marked = marked,
            namingTemplate = namingTemplate,
            zone = zone,
        ).map { r ->
            ProjectedLedgerRow(
                dateTime = r.dateTime,
                endTime = r.endTime,
                location = r.location,
                description = r.description,
                photo1Label = labelOf(r.folderName, r.photo1Ref, byRef),
                photo2Label = labelOf(r.folderName, r.photo2Ref, byRef),
                folderName = r.folderName,
            )
        }
    }

    /**
     * 照片列标签：`文件夹名/文件名`。
     *
     * 三条既有规则（逐条搬自 PC，改动会立刻体现在台账单元格里）：
     *   1. `ref` 为空 → `null`（不是空串，导出时据此省略照片列）；
     *   2. 反查不到照片时**退化为 ref 的最后一段**，而不是整条路径 —— 否则 Windows 上
     *      会出现 `01_xxx/C:\Users\...\IMG.jpg` 这种半截路径；
     *   3. `folderName` 为空 → 只给文件名（无分类文件夹的历史数据）。
     */
    fun labelOf(folderName: String, ref: String?, byRef: Map<String, Photo>): String? {
        if (ref.isNullOrBlank()) return null
        val name = byRef[ref]?.displayName ?: ref.substringAfterLast('/')
        return if (folderName.isBlank()) name else "$folderName/$name"
    }

    /**
     * `sourceRef` → 照片。重复 ref 取**第一个**（与 PC `values.firstOrNull { ... }` 等价）。
     *
     * 用 `putIfAbsent` 而不是 `associateBy`：后者保留最后一个，同名 ref 时结果会反过来。
     * 现实中 sourceRef 应当唯一，但这条差异一旦出现只会表现为「台账照片名不同」，极难定位，
     * 所以按既有语义固定成「第一个」。
     */
    private fun buildRefIndex(photos: List<Photo>): Map<String, Photo> {
        val index = LinkedHashMap<String, Photo>(photos.size)
        for (p in photos) index.putIfAbsent(p.sourceRef, p)
        return index
    }
}
