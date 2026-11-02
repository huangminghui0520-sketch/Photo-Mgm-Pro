// algorithm/GpsGrouperEngine.kt —— §5 GPS 分组模块
// 输入：已精读 EXIF 的照片（含 GPS + 精确时间）
// 算法：50m 聚簇（并查集）+ 5min 自动拆簇 + 离群检测（>1.5×50m）
// ★ 无 GPS / 无精确时间的照片不丢弃，进 singles（供 §6.2② 按时间归事件 / 人工处理）
package algorithm

import algorithm.model.Cluster
import algorithm.model.GpsGroupResult
import algorithm.model.Photo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GpsGrouperEngine(
    private val maxDistanceM: Double = 50.0,
    private val timeWindowMs: Long = 5 * 60 * 1000,
) {
    fun group(photos: List<Photo>): GpsGroupResult {
        val valid = photos.filter { it.latitude != null && it.longitude != null && it.captureTimeMs != null }
            .sortedBy { it.captureTimeMs }
        val singles = photos.filter { it.latitude == null || it.longitude == null || it.captureTimeMs == null }
        if (valid.isEmpty()) return GpsGroupResult(emptyList(), singles)
        if (valid.size == 1) return GpsGroupResult(listOf(buildCluster(valid)), singles)
        val (clusters, split) = buildClusters(valid)
        return GpsGroupResult(clusters, singles, split)
    }

    private fun buildClusters(valid: List<Photo>): Pair<List<Cluster>, Int> {
        val n = valid.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x; while (parent[r] != r) r = parent[r]
            var c = x; while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        // 并查集建边：时间差 ≤5min 且 GPS 距离 ≤50m → 同一簇（时间已升序，只查近邻）
        for (i in 0 until n) {
            var j = i + 1
            while (j < n && valid[j].captureTimeMs!! - valid[i].captureTimeMs!! <= timeWindowMs) {
                if (distance(valid[i], valid[j]) <= maxDistanceM) union(i, j)
                j++
            }
        }
        // 连通分量 → 自动拆簇（簇内时间跨度 >5min 再分割，避免误并）
        val buckets = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) buckets.getOrPut(find(i)) { mutableListOf() }.add(i)
        val clusters = mutableListOf<Cluster>()
        var split = 0
        for (indices in buckets.values) {
            val list = indices.sortedBy { valid[it].captureTimeMs }
            var seg = mutableListOf<Photo>()
            var segStart = valid[list.first()].captureTimeMs!!
            for (idx in list) {
                val t = valid[idx].captureTimeMs!!
                if (t - segStart > timeWindowMs) {        // ★ 自动拆簇
                    clusters.add(buildCluster(seg)); seg = mutableListOf(); segStart = t; split++
                }
                seg.add(valid[idx])
            }
            if (seg.isNotEmpty()) clusters.add(buildCluster(seg))
        }
        return clusters to split
    }

    private fun buildCluster(ps: List<Photo>): Cluster {
        val lat = ps.mapNotNull { it.latitude }.average()
        val lng = ps.mapNotNull { it.longitude }.average()
        val rep = ps.minOfOrNull { it.captureTimeMs!! }
        val center = Photo(0, "", "", rep, lat, lng)
        val outliers = ps.filter { distance(it, center) > maxDistanceM * 1.5 }
        return Cluster(ps, rep, lat, lng, outliers)
    }

    private fun distance(a: Photo, b: Photo): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude!! - a.latitude!!)
        val dLng = Math.toRadians(b.longitude!! - a.longitude!!)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude!!)) * cos(Math.toRadians(b.latitude!!)) * sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}
