// algorithm/Dedup.kt —— 重复照片三态保留（2026-10-24 B1）
// 使用场景：一日巡查中，水印相机事件同时产出「带水印主图 IMG_xxx.jpg + 无水印 _original」双份（文件名数字相邻、
//           EXIF 同刻同 GPS）；手机自带相机事件仅单份无水印。
// 设计：分类阶段照旧全量照片（不提前删除）；输出层对「已分类事件的重复照片组」做三态动态分类
//       （带水印 / 不带水印 / 都保留）；未匹配照片不参与、全量保留；人工标记（keepAlways）优先。
// 扩展位：PhotoDedupStrategy 接口 —— 后续可追加哈希判重 / 水印识别等实现，管线不改。
package algorithm

import algorithm.model.Photo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 重复照片保留策略（用户三选一）。 */
enum class DedupKeepMode {
    /** 仅保留带水印（默认）：水印相机主图 IMG_xxx.jpg（无派生后缀）。 */
    KEEP_WATERMARKED,

    /** 仅保留不带水印：_original 备份。 */
    KEEP_PLAIN,

    /** 两者都保留：不去重（等同现状）。 */
    KEEP_BOTH,
}

/** 重复照片判重配置（默认：时间容差 1s + GPS 距离 10m + 降级序号容差 1000）。 */
data class DedupConfig(
    val timeToleranceMs: Long = 1_000L,
    val gpsToleranceM: Double = 10.0,
    /** ★ 2026-10-24 降级判重：双方 GPS 均无效（缺失/清零）时，文件名最后数字块（拍摄毫秒序号）容差。 */
    val seqTolerance: Long = 1_000L,
)

/**
 * 照片去重策略（预留扩展位）。
 * 本期实现 [ExifContentDedupStrategy]（时间+GPS）；后续可追加 HashDedupStrategy / WatermarkDedupStrategy。
 */
interface PhotoDedupStrategy {
    /** 判定两张照片是否视为同一张（同一内容 / 同一瞬间）。 */
    fun isSame(a: Photo, b: Photo): Boolean

    /** 同组多张中选出代表照片（用于组内保序 / 优先展示），默认主图（无派生后缀）优先。 */
    fun retain(photos: List<Photo>): Photo =
        photos.minByOrNull { derivedScore(it.displayName) } ?: photos.first()
}

/**
 * 本期实现：拍摄时间（容差）+ GPS 距离（阈值）内容判重；
 * ★ 2026-10-24 降级：双方 GPS 均无效（缺失或被 ROM 相册清零为 0/1,0/1,0/1）时，
 *   自动降级为「时间 + 派生后缀 + 文件名毫秒序号」判重，保证真机（GPS 被系统脱敏）三态仍生效。
 */
class ExifContentDedupStrategy(
    private val timeToleranceMs: Long = 1_000L,
    private val gpsToleranceM: Double = 10.0,
    private val seqTolerance: Long = 1_000L,
) : PhotoDedupStrategy {
    override fun isSame(a: Photo, b: Photo): Boolean {
        val ta = a.captureTimeMs ?: return false
        val tb = b.captureTimeMs ?: return false
        if (abs(ta - tb) > timeToleranceMs) return false
        // ── GPS 判重：任一方有有效 GPS → 维持原逻辑（时间 + GPS 距离）──
        val (latA, lngA) = a.latitude to a.longitude
        val (latB, lngB) = b.latitude to b.longitude
        val gpsAOk = latA != null && lngA != null && !isZeroGps(latA, lngA)
        val gpsBOk = latB != null && lngB != null && !isZeroGps(latB, lngB)
        if (gpsAOk || gpsBOk) {
            // 一方有有效 GPS、另一方无 → 不成组（保守：不把"有定位/无定位"的照片当双份）
            if (!gpsAOk || !gpsBOk) return false
            return haversineM(latA, lngA, latB, lngB) <= gpsToleranceM
        }
        // ── ★ 降级判重：双方 GPS 均无效（缺失/清零）──
        //   todayCam 双份：IMG_...478.jpg（带水印主图）↔ IMG_...479_original.jpg（原图），
        //   文件名最后数字块 = 拍摄毫秒序号，双份差 ∈ {1,2,956–959} 全 ≤ 1000；
        //   任一方含派生后缀才允许成组（手机自带相机照片无派生后缀，永不误并）。
        if (!isDerivedName(a.displayName) && !isDerivedName(b.displayName)) return false
        val seqA = lastNumberBlock(a.displayName) ?: return false
        val seqB = lastNumberBlock(b.displayName) ?: return false
        return abs(seqA - seqB) <= seqTolerance
    }
}

/** GPS 是否被清零（ROM 相册隐私脱敏的典型值：0/1,0/1,0/1 → 0.0,0.0）。 */
internal fun isZeroGps(lat: Double, lng: Double): Boolean = lat == 0.0 && lng == 0.0

/** 文件名最后一个数字块（todayCam 文件名末段 = 拍摄毫秒序号），无数字则 null。 */
internal fun lastNumberBlock(name: String): Long? {
    var last: MatchResult? = null
    for (m in Regex("\\d+").findAll(name)) last = m
    return last?.value?.toLongOrNull()
}

/** 两坐标点球面距离（米）。 */
internal fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return R * 2 * atan2(sqrt(h), sqrt(1 - h))
}

/**
 * 派生后缀统一清单（★ P3-5 单一事实来源）。
 * PhotoSource.dedupDerived 的基名剥离、本文件的 isDerived/score 全部走这里，
 * 消除"派生后缀名单三处各自维护、新增后缀易漏改"的漂移风险。
 * 语义：带水印主图（无后缀）=0 < _original（无水印原图）=1 < _compressed（压缩副本）=2。
 */
internal object DerivedNames {
    /** 派生后缀词（不含下划线前缀）；新增加工后缀只需改这里。 */
    val suffixes: List<String> = listOf(
        "original", "compressed", "edited", "modified", "optimized", "resized",
        "缩略", "压缩", "原图",
    )

    /** 剥离派生后缀与扩展名的正则（_original_compressed 可叠加；紧跟扩展名之前）。 */
    val stripRe: Regex =
        Regex("""(_(?:${suffixes.joinToString("|")}))+(?=\.[^.]+$)""", RegexOption.IGNORE_CASE)

    /** 是否为派生文件（_original/_compressed 等），用于区分「带水印主图」与「无水印备份」。 */
    fun isDerived(name: String): Boolean {
        val lower = name.lowercase()
        return suffixes.any { lower.contains("_$it") }
    }

    /** 派生优先级分数：越小越优先。主图=0 < 仅_original=1 < 仅_compressed=2 < 其余加工后缀=30+。 */
    fun score(name: String): Int {
        val lower = name.lowercase()
        var s = 0
        if (lower.contains("_original")) s += 1
        if (lower.contains("_compressed")) s += 2
        // ★ 2026-10-31 审查修复：[suffixes] 共 8 个词，但此前只给前两个计分，其余 6 个
        //   （_edited/_modified/_optimized/_resized/_缩略/_压缩/_原图）与主图同为 0 分。
        //   dedupDerived 以 sortedBy{score}.first() 选主图（稳定排序），同分时"列表中靠前者"胜出
        //   → 加工副本可能当主图、真主图反被判为副本丢弃；KEEP_PLAIN 也会选中 _edited 而非 _original。
        //   加工副本一律低于主图（分数更大），按下标递增赋分确保各后缀互不并列。
        for (i in 2 until suffixes.size) {
            if (lower.contains("_${suffixes[i]}")) s += 10 * (i + 1)
        }
        return s
    }

    /** 是否为 _original（水印相机原图语义，独立照片候选）。 */
    fun isOriginal(name: String): Boolean = name.lowercase().contains("_original")
}

/** 是否为派生文件（_original/_compressed 等），统一走 [DerivedNames]。 */
internal fun isDerivedName(name: String): Boolean = DerivedNames.isDerived(name)

/** 派生优先级分数：越小越优先。主图=0 < 仅_original=1 < 仅_compressed=2。 */
internal fun derivedScore(name: String): Int = DerivedNames.score(name)

/**
 * 内容判重分组（仅标记、不删除）：全量照片按「时间容差 + GPS 距离阈值」两两合并为重复组。
 * @return 代表 id -> 组内其他照片（代表 = 组内 derivedScore 最小，即带水印主图优先）；
 *         单份照片不进入结果。
 *
 * ★ 2026-10-24 修复（真实数据验证发现）：禁止链式传递合并。
 *   并查集强制等价传递（A≈B、B≈C ⇒ A、B、C 同组）会把"相邻两个瞬间各自的双份"误并成 4 张一组，
 *   导致仅保留带水印时误删另一瞬间的真实照片（15:37 过车涵洞实测被并组）。
 *   改为「新照片须与组内**每一张**都满足判重才入组」——1s 容差下同一瞬间的 2 张双份可合并，
 *   相邻瞬间（间隔 >1s）不会跨组合并。
 *
 * 复杂度 O(n²·组大小)（106 张约万次级距离计算，毫秒级），规模大时再换空间索引。
 */
fun PhotoIngest.groupDupContent(
    photos: List<Photo>,
    config: DedupConfig = DedupConfig(),
    strategy: PhotoDedupStrategy = ExifContentDedupStrategy(config.timeToleranceMs, config.gpsToleranceM, config.seqTolerance),
): Map<Long, List<Photo>> {
    if (photos.size < 2) return emptyMap()
    // 聚类：新照片只并入"与组内每张都重复"的组（防止 1s 容差链式传递）
    val groups = ArrayList<MutableList<Photo>>()
    for (p in photos) {
        var placed = false
        for (g in groups) {
            if (g.all { strategy.isSame(it, p) }) {
                g.add(p)
                placed = true
                break
            }
        }
        if (!placed) groups.add(mutableListOf(p))
    }
    val out = LinkedHashMap<Long, List<Photo>>()
    for (members in groups) {
        if (members.size < 2) continue
        val rep = strategy.retain(members)
        out[rep.id] = members.filter { it.id != rep.id }
    }
    return out
}

/**
 * 事件照片三态展开（仅作用于已分类事件照片；未匹配照片不调用本函数、全量保留）。
 *
 * ★ 2026-10-31 C：返回值是入参 photoIds 的**子集**——除三态折叠（组内成员改写为应保留版本）
 *   外，还会"整组不展示"：组内"应保留版本"已被人工移走时，该组所有成员都不输出（见 [dropped]）。
 *   调用方（预览 / 台账 / ZIP 导出）据此得到最终照片列表，故事件可能因此变为 0 张。
 *
 * 判定优先级（自上而下，顺序不可调换）：
 *   1. keepAlways（人工台账标记）—— 最高，压过一切过滤；
 *   2. passthrough（人工移动/移除过的照片）—— 用户显式意图，原样展示、不被三态改写；
 *   3. dropped（应保留版本已被移走的重复组）—— 整组不展示；
 *   4. preferred（三态折叠）—— 组内成员改写为应保留版本。
 *
 * @param keepAlways 强制保留的 id（人工标记优先，跳过三态过滤）。
 * @param passthrough 人工移动过的照片 id（用户显式意图，原样保留、不参与三态改写；
 *   ★ 2026-10-31 B：同时用于"保留侧代表被移走 → 本组映射失效"，杜绝幽灵照片）。
 * @return 展开后的照片 id 列表（保序、去重）。
 */
fun PhotoIngest.expandForKeepMode(
    photoIds: List<Long>,
    dupGroups: Map<Long, List<Photo>>,
    keepMode: DedupKeepMode,
    keepAlways: Set<Long> = emptySet(),
    passthrough: Set<Long> = emptySet(),
): List<Long> {
    if (keepMode == DedupKeepMode.KEEP_BOTH) return photoIds
    // 组内每个成员 id -> 应保留的版本 id
    val preferred = HashMap<Long, Long>()
    // ★ 2026-10-31 C：应保留版本已被人工移走的重复组 → 整组不展示（成员 id 集合）。
    val dropped = HashSet<Long>()
    for ((repId, dupList) in dupGroups) {
        val chosen = when (keepMode) {
            DedupKeepMode.KEEP_WATERMARKED -> repId
            // ★ P3-6：不带水印版 = 组内派生成员中分数最低者（_original=1 优先于 _compressed=2）；
            //   原实现取"列表顺序第一个派生"存在顺序依赖（同组含 _original 与 _compressed 时可能选错）。
            //   组内无派生（全部为主图）→ 回退代表（repId），与"仅保留不带水印"语义的保守兜底一致。
            DedupKeepMode.KEEP_PLAIN -> dupList.filter { isDerivedName(it.displayName) }
                .minByOrNull { derivedScore(it.displayName) }?.id ?: repId
            DedupKeepMode.KEEP_BOTH -> repId // 不可达（上方已返回）
        }
        // ★ 2026-10-31 B：保留侧代表已被人工移动（∈ passthrough）→ 本组不建立映射，
        //   避免事件内残留成员映射回"已移走的代表"，否则同一张照片同时出现在源/目标事件（P2 幽灵）。
        // ★ 2026-10-31 C：仅"不建立映射"还不够——残成员会走到 `preferred[id] ?: id` 原样展示，
        //   于是"仅保留带水印"的源事件里冒出无水印备份（本 bug）。
        //   语义：代表（应保留版本）被移走 = 该瞬间的照片内容已整体离开源事件 → 整组标记丢弃，
        //   残留备份跟随三态过滤，不顶替展示。抑制仅限本组，其他组照常折叠。
        if (chosen in passthrough) {
            dropped.add(repId)
            for (d in dupList) dropped.add(d.id)
            continue
        }
        preferred[repId] = chosen
        for (d in dupList) preferred[d.id] = chosen
    }
    val out = LinkedHashSet<Long>()
    for (id in photoIds) {
        // 优先级 1/2：人工意图优先（★ 必须早于 dropped 判断，keepAlways 压过整组抑制）
        if (id in keepAlways || id in passthrough) {
            out.add(id)
            continue
        }
        // 优先级 3：本组应保留版本已被移走 → 整组不展示
        if (id in dropped) continue
        // 优先级 4：三态折叠（无映射则原样保留，如单份照片）
        out.add(preferred[id] ?: id)
    }
    return out.toList()
}
