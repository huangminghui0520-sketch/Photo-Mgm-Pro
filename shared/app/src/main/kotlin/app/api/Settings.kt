// shared/app/api/Settings.kt —— 用户设置（跨端契约，唯一一份）
//
// ★ T09 的关键一次「减法」：桌面端的 `Settings` 原来带着 `window: WindowState?`。
//   窗口几何是**桌面独有的产品形态**，安卓永远没有这个字段。把它留在共享契约里，
//   安卓的状态快照就得背着一个恒为 null 的字段，并且每次读 `settings` 都要解释
//   "这个 null 不是我漏填，是这端根本没有窗口"——这正是本次重构要消灭的那类字段
//   （见 docs/CROSSPLATFORM-ARCH.md「契约里不允许出现平台概念」）。
//   T09 起窗口几何留在桌面侧（T10 由 `PcSettingsStorage` 自己存取），本文件不含它。
//
// ★ 另一条纪律：这里只放**数据**，不放文件路径类型。
//   `sourceDirs` / `outputDir` 都是 `String`：桌面端是绝对路径，安卓是 SAF 树标识或
//   物理路径——共享层不解释它们的语法，只把它们当不透明字符串传递与持久化。
package app.api

import algorithm.AppZone
import algorithm.DedupKeepMode
import java.time.LocalDate

/**
 * 用户设置。
 *
 * @property sourceDirs 扫描源目录（不透明字符串；空 = 未选择，分析只做日志解析）。
 * @property outputDir 输出目标（不透明字符串；null = 未设置，导出被阻断）。
 * @property patrolDate 巡查日期。默认按**业务时区** [AppZone.ID] 取今天，不看系统时区。
 * @property namingTemplate 导出文件夹命名模板，取值见 [NAMED_BY_EVENT_TYPE] / [NAMED_BY_LOG_CONTENT]。
 * @property dedupMode 重复照片保留策略（三态）。
 */
data class Settings(
    val sourceDirs: List<String> = emptyList(),
    val outputDir: String? = null,
    val patrolDate: LocalDate = LocalDate.now(AppZone.ID),
    val namingTemplate: Int = NAMED_BY_LOG_CONTENT,
    val dedupMode: DedupKeepMode = DedupKeepMode.KEEP_WATERMARKED,
) {
    companion object {
        /** 模板 0：`NN_事件类型`。 */
        const val NAMED_BY_EVENT_TYPE: Int = 0

        /** 模板 1（默认）：`NN_巡查日志内容`。 */
        const val NAMED_BY_LOG_CONTENT: Int = 1

        /** 模板取值是否合法。非法值一律回落默认，避免持久化里一个坏数字让导出目录名变成 `0_`。 */
        fun isValidNamingTemplate(value: Int): Boolean =
            value == NAMED_BY_EVENT_TYPE || value == NAMED_BY_LOG_CONTENT

        /** 模板非法的兜底值（与安卓 `SettingsStore`、桌面 `SettingsRepository` 的既有口径一致）。 */
        fun sanitizeNamingTemplate(value: Int): Int =
            if (isValidNamingTemplate(value)) value else NAMED_BY_LOG_CONTENT
    }
}
