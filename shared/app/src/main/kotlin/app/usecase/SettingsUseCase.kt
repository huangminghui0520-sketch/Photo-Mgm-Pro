// shared/app/usecase/SettingsUseCase.kt —— 设置与日志文本的编排
//
// 三个写入口，各自的语义差别很大，混在一起就容易出"改了设置却没重算"的 bug：
//
//   updateSettings   改巡查日期/源目录 → **既有分析结果失效**，只提示"请重新分析"，不重算；
//                    改命名模板/输出目录 → 不失效，但模板影响台账与导出目录名 → 立即重算。
//   setDedupMode     三态切换 → 只重算输出层（不重跑扫描/EXIF/分类），并同步完成。
//   setLogText       日志原文 → 内存态立即生效，落盘走防抖（[DebouncedTextStore]）。
//
// ★ 为什么 setDedupMode 必须**同步**重算：
//   异步重算会碰到"快速连切 A→B→A"：两个任务都在跑，晚到的那个把状态写成 B，
//   界面显示 B 而用户最后点的是 A。同步重算把这条竞态从根上去掉（安卓端曾踩过，P2-1）。
//
// ★ 为什么"失效"只认巡查日期与源目录：
//   命名模板/去重模式不影响分类，改完重算视图即可；输出目录只影响导出目标。
//   把这几项也判成失效，会让用户改一次模板就丢掉全部人工调整 —— 那才是真正的数据损失。
package app.usecase

import algorithm.DedupKeepMode
import app.api.CoreStatus
import app.api.Settings
import app.port.SettingsStorage
import app.state.AppStateStore
import app.state.PhotoSession
import app.infra.DebouncedTextStore

/** 设置与日志文本用例。 */
class SettingsUseCase(
    private val store: AppStateStore,
    private val session: PhotoSession,
    private val storage: SettingsStorage,
    private val logs: DebouncedTextStore,
) {

    /**
     * 原子更新设置并持久化。
     *
     * @param block 在**当前设置**基础上变换（不是替换整份），避免调用方漏填某个字段就把
     *   用户的其他设置清成默认值。
     */
    fun updateSettings(block: (Settings) -> Settings) {
        store.withLock {
            val before = store.current().settings
            val after = block(before)
            // 先落盘再发布：若落盘失败（实现约定不抛），内存里也已经是新设置；
            // 顺序反过来则会存在"界面已显示新值、磁盘还是旧值"的窗口。
            storage.save(after)
            val invalidated = after.patrolDate != before.patrolDate || after.sourceDirs != before.sourceDirs
            store.publish {
                it.copy(
                    settings = after,
                    dedupMode = after.dedupMode,
                    message = when {
                        invalidated -> "设置已更新：请重新分析（巡查日期或源目录已变化）"
                        else -> it.message
                    },
                )
            }
            if (!invalidated && session.classify != null) {
                ViewPublisher.publish(store, session, CoreStatus.READY)
            }
        }
    }

    /** 日志文本变化：内存态立即生效，落盘交给防抖。 */
    fun setLogText(text: String) {
        store.withLock {
            logs.onChanged(text)
            store.publish { it.copy(logText = text) }
        }
    }

    /**
     * 切换三态去重模式：持久化 + 同步重算输出层。
     *
     * 与当前模式相同时是空操作 —— 否则每次点击都会重算一遍全部视图并弹一条提示，
     * 用户会以为"点了没反应但又说了句话"。
     */
    fun setDedupMode(mode: DedupKeepMode) {
        store.withLock {
            val state = store.current()
            if (state.dedupMode == mode) return
            val settings = state.settings.copy(dedupMode = mode)
            storage.save(settings)
            store.publish { it.copy(settings = settings, dedupMode = mode) }
            if (session.classify != null) {
                ViewPublisher.publish(store, session, CoreStatus.READY, message = dedupMessage(mode))
            }
        }
    }

    /** 立即落盘日志（关闭前、开始分析前）。幂等。 */
    fun flushLogs() = logs.flush()

    /**
     * 三态切换的口径提示（文案两端必须一致：它是用户理解"照片去哪了"的唯一解释）。
     *
     * 计数口径：`dupGroups` 是「主图 → 派生副本列表」，一组 = 1 + 派生数；
     * 单份照片 = 总数 − 所有组内张数。**不含未匹配**是故意的：未匹配不参与三态展开。
     */
    private fun dedupMessage(mode: DedupKeepMode): String {
        val dup = session.dupGroups()
        val inGroups = dup.values.sumOf { it.size + 1 }
        val single = session.photos.size - inGroups
        return when (mode) {
            DedupKeepMode.KEEP_WATERMARKED ->
                "已合并 ${dup.size} 组重复照片（保留带水印版）；单份照片 $single 张不受影响"

            DedupKeepMode.KEEP_PLAIN ->
                "已合并 ${dup.size} 组重复照片（保留不带水印版）；单份照片 $single 张不受影响"

            DedupKeepMode.KEEP_BOTH ->
                "重复照片全部保留（共 ${dup.size} 组，每组带水印/不带水印双份）；单份照片 $single 张不受影响"
        }
    }
}
