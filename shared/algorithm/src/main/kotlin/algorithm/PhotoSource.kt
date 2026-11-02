// algorithm/PhotoSource.kt —— §4 照片读取模块：平台无关的核心逻辑 + 平台接入接口
// ★ 纯度：本文件零 Android 依赖；MediaStore / SAF / ExifInterface 等平台实现由 data 层注入。
package algorithm

import algorithm.model.Photo
import java.time.LocalDate

/** 未精读 EXIF 前的候选照片（data 层产出，经纯逻辑粗筛后再精读）。 */
data class CandidatePhoto(
    val id: Long,              // MediaStore _ID（Android）；PC 端可为 0
    val sourceRef: String,     // Android=content uri / 物理路径；PC=文件绝对路径
    val displayName: String,   // 文件名（粗筛依据）
)

/** 平台接入：列出某源目录下的候选照片。Android=MediaStore 查询；PC=目录文件扫描。 */
interface PhotoSourceProvider {
    /** @param sourceDir 已授权的源目录（如 DCIM/Camera），返回其下候选照片。 */
    fun listCandidates(sourceDir: String): List<CandidatePhoto>
}

/** 平台接入：EXIF 精读。Android=androidx ExifInterface；PC=自备实现。读失败返回 null，不抛异常。 */
interface ExifReader {
    /** 拍摄时间（含日期）→ epoch 毫秒；读不到返回 null。 */
    fun readCaptureTimeMs(sourceRef: String): Long?
    /** GPS 坐标 → (纬度, 经度)；读不到返回 null。 */
    fun readGps(sourceRef: String): Pair<Double, Double>?

    /**
     * 合并精读：一次打开资源同时读取 (拍摄时间, GPS)。
     * 默认实现退化为两次独立读取（兼容旧实现/PC 端实现）；
     * 平台实现应覆盖本方法以消除重复打开开销（ExifPhotoReader 一次 ExifInterface 构造）。
     * 语义契约与两个独立方法完全一致：失败字段置 null、互不拖累。
     */
    fun readAll(sourceRef: String): Pair<Long?, Pair<Double, Double>?> =
        readCaptureTimeMs(sourceRef) to readGps(sourceRef)
}

/**
 * §4 照片读取核心编排（纯 Kotlin，可移植 PC）：
 *   多源候选 → 目录白名单 → 文件名日期粗筛 → 去重 → EXIF 精读（接口注入）
 */
object PhotoIngest {
    /**
     * 目录白名单：前缀 + 分隔符匹配。
     * 授权 "DCIM/Camera" 不误配 "DCIM/CameraOld"（边界字符必须是路径分隔符）。
     * ★ 容忍源目录尾部斜杠（"DCIM/Camera/" 与 "DCIM/Camera" 等价）。
     * ★ 缺陷回归：sourceRef 可能是 RELATIVE_PATH（相对路径前缀）或 DATA 物理路径（/storage/…/DCIM/Camera/…），
     *   必须同时匹配两种形式，否则 Android 粗筛把全部照片过滤掉 → 0 张。
     */
    fun inSourceDir(path: String, sourceDir: String): Boolean {
        val dir = sourceDir.trimEnd('/', '\\')
        // ① 相对路径前缀匹配（RELATIVE_PATH：DCIM/Camera/IMG.jpg）
        if (path.startsWith(dir)) {
            if (path.length == dir.length) return true
            val c = path[dir.length]
            return c == '/' || c == '\\'
        }
        // ② 物理路径后缀匹配（DATA：/storage/emulated/0/DCIM/Camera/IMG.jpg）
        val idx = path.lastIndexOf(dir)
        if (idx >= 0) {
            val before = idx - 1
            val after = idx + dir.length
            val beforeOk = before < 0 || path[before] == '/' || path[before] == '\\'
            val afterOk = after >= path.length || path[after] == '/' || path[after] == '\\'
            return beforeOk && afterOk
        }
        return false
    }

    /**
     * 粗筛：白名单过滤 → 文件名日期区间过滤 → 按物理路径（sourceRef）去重（保序，保留首现）。
     * @param sourceDirs 空列表 = 不按目录过滤（PC 全目录扫描场景）。
     */
    fun coarseFilter(
        candidates: List<CandidatePhoto>,
        sourceDirs: List<String>,
        range: ClosedRange<LocalDate>,
    ): List<CandidatePhoto> {
        val seen = HashSet<String>()
        val out = mutableListOf<CandidatePhoto>()
        for (c in candidates) {
            if (sourceDirs.isNotEmpty() && sourceDirs.none { inSourceDir(c.sourceRef, it) }) continue
            if (!FileNameDateFilter.isInRange(c.displayName, range.start, range.endInclusive)) continue
            if (!seen.add(c.sourceRef)) continue
            out.add(c)
        }
        return out
    }

    /**
     * 派生文件去重（2026-10-01 新增；2026-10-24 方案B' 双层判定）：
     * 同一照片存在主图 + 压缩版/原图备份等多份派生文件时，按「基名（去掉派生后缀与扩展名）」分组，
     * 每组保留最高优先级版本（主图（无派生后缀）> _original > _compressed > 其余派生后缀）。
     *
     * ★ 2026-10-24 方案B'（水印相机同序号 original 误杀修复）：
     *   水印相机每按一次快门同时输出「主图 IMG_xxx.jpg + 原图 IMG_xxx_original.jpg」，
     *   文件名序号可能相同（IMG_...661.jpg + IMG_...661_original.jpg）→ 去后缀基名完全相同，
     *   旧逻辑把 original 当「同一照片的派生副本」剔除（真机 10:10/10:22/15:59 各丢 1 张无水印）。
     *   同一次快门的 original 是独立照片（内容 = 主图 + 水印合成，EXIF 同刻同 GPS），
     *   与「同一照片的派生副本」在元数据上不可区分（时间/GPS/像素全一致）→ 新增内容哈希双层判定：
     *     - 同基名 + 内容哈希一致        → 确为同一照片的复制副本     → 合并（任何后缀）
     *     - 同基名 + 哈希不同 + _original → 独立照片（水印相机原图）    → 保留
     *     - 同基名 + 哈希不同 + 其他派生  → 加工副本（压缩/编辑）      → 合并（9-25 场景）
     *     - _original 且哈希读取失败     → 保守保留（宁可多留，不丢独立照片）
     *   修复场景：9-25 实例 124 个文件 = 62 张主图 + 62 个 _compressed 派生文件，
     *   派生文件无 EXIF 时间/GPS 全部进「未分类」，且与主图重复。
     *
     * @param hashReader 文件内容哈希读取（Android 注入 FileHasher；null = 未注入，退化为旧行为「按基名+分数合并」）。
     * @return Pair(去重后候选, 合并组数)（合并组数供 UI 提示「已合并 N 组派生文件」）
     */
    fun dedupDerived(
        candidates: List<CandidatePhoto>,
        hashReader: ((String) -> String?)? = null,
    ): Pair<List<CandidatePhoto>, Int> {
        // ★ P3-5：派生后缀清单统一走 Dedup.kt DerivedNames（单一事实来源），
        //   基名剥离 / 打分 / _original 判定不再各自维护正则，新增后缀只改 DerivedNames.suffixes。

        /** 基名 = 文件名去掉全部派生后缀与扩展名（_original_compressed 与主图归同一基名）。 */
        fun baseName(name: String): String {
            val stem = DerivedNames.stripRe.replace(name, "")
            val dot = stem.lastIndexOf('.')
            return if (dot >= 0) stem.substring(0, dot) else stem
        }

        /** 派生优先级分数：越小越优先（Dedup.kt 统一实现：主图=0 < 仅_original=1 < 仅_compressed=2）。 */
        fun score(name: String): Int = DerivedNames.score(name)

        /** 是否为 _original（水印相机原图语义，独立照片候选）。 */
        fun isOriginal(name: String): Boolean = DerivedNames.isOriginal(name)

        /**
         * 分组键 = **所在目录** + 基名。
         * ★ 2026-10-31 审查修复：原键只取基名，会把「不同目录下的同名文件」误当作同一张照片的
         *   主图与派生副本，于是其中一张**真实照片**被判为副本而静默丢弃（相机重置计数后重新
         *   拍摄的 IMG_0001.jpg、或多个源目录下的备份目录同名文件，都是典型场景）。
         *   加目录维度后各目录独立判定，跨目录同名互不干扰。
         *   边界：sourceRef 取 RELATIVE_PATH（DATA 列为空）时目录部分与物理路径写法不同，
         *   同一目录内混用两种取值会拆成两组——保守不去重，不会造成误删。
         *   分隔符用 '/'：displayName 取自 MediaStore DISPLAY_NAME，不含 '/'，故拼接无歧义。
         */
        fun groupKey(c: CandidatePhoto): String {
            val dir = c.sourceRef.substringBeforeLast('/')
            return "$dir/${baseName(c.displayName)}"
        }

        // 先按「目录 + 基名」分组（保序），再逐组按方案B' 判定
        val groups = LinkedHashMap<String, MutableList<CandidatePhoto>>()
        for (c in candidates) {
            groups.getOrPut(groupKey(c)) { mutableListOf() }.add(c)
        }
        val out = mutableListOf<CandidatePhoto>()
        var merged = 0
        for ((_, g) in groups) {
            if (g.size == 1) {
                out.add(g[0])
                continue
            }
            // 基准 = 组内分数最低者（主图优先）；其余文件逐一定夺保留/合并
            val ordered = g.sortedBy { score(it.displayName) }
            val base = ordered.first()
            out.add(base)
            val baseHash = if (hashReader != null) hashReader(base.sourceRef) else null
            for (f in ordered.drop(1)) {
                val keep = if (hashReader != null) {
                    val fHash = hashReader(f.sourceRef)
                    val sameContent = baseHash != null && fHash != null && baseHash == fHash
                    when {
                        // ① 内容一致 → 同一照片复制副本 → 合并
                        sameContent -> false
                        // ② _original 且哈希未知（任一方读失败）→ 保守保留
                        isOriginal(f.displayName) && (fHash == null || baseHash == null) -> true
                        // ③ _original 且哈希不同 → 独立照片（水印相机原图）→ 保留
                        isOriginal(f.displayName) -> true
                        // ④ 其他派生后缀 → 加工副本（压缩/编辑）→ 合并
                        else -> false
                    }
                } else {
                    // hashReader 未注入 → 旧行为：同基名派生文件一律合并
                    false
                }
                if (keep) out.add(f) else merged++
            }
        }
        return out to merged
    }

    /**
     * EXIF 精读编排：对粗筛结果逐一精读，组装为 Photo。
     * 读失败字段置 null、不中断（§4.3）。并发优化由 data 层实现，核心保持串行简单可移植。
     */
    fun readExif(candidates: List<CandidatePhoto>, exif: ExifReader): List<Photo> =
        candidates.map { c ->
            val (t, g) = runCatching { exif.readAll(c.sourceRef) }.getOrNull() ?: (null to null)
            Photo(
                id = c.id,
                sourceRef = c.sourceRef,
                displayName = c.displayName,
                captureTimeMs = t,
                latitude = g?.first,
                longitude = g?.second,
            )
        }

    /**
     * 精读后按 MediaStore _ID 去重（MediaStore 与 SAF 可能以不同 uri 指向同一文件；_ID 唯一）。
     * ★ 仅对 id>0（真实 _ID）去重；id<=0（PC 端无意义默认 0）全部保留，避免静默丢照片。
     */
    fun dedupById(photos: List<Photo>): List<Photo> {
        val seen = HashSet<Long>()
        // id<=0 直接保留（谓词 true）；id>0 按首次出现去重
        return photos.filter { it.id <= 0 || seen.add(it.id) }
    }
}
