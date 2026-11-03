// shared/app/port/ShellLauncher.kt —— 系统外壳动作端口（打开产物 / 在文件管理器中定位）
//
// ★ 为什么独立成端口：核心逻辑不该直接调 `java.awt.Desktop` 或 `startActivity`——
//   ① 无头环境/单元测试会炸；② 换 UI 框架时可能要用自己的实现；
//   ③ 这是核心唯一「有副作用到操作系统」的出口，隔离后便于审计与替换。
//
// ★ 三态而非 Boolean（T09 从安卓 `UriShellLauncher.Result` 推广到两端）：
//   桌面端原来只有 true/false，安卓端区分「拿不到 URI」与「系统里没有能打开的应用」——
//   因为这两种情况给用户的提示完全不同（前者是「文件不见了/授权丢了」，
//   后者是「装个表格应用」）。共享层若退化成 Boolean，安卓就得把这句提示丢掉。
//   ★ 不预检 `resolveActivity`：Android 11+ 的包可见性收紧后，预检对很多合法应用返回 null
//     （看不到 ≠ 不存在），会误报「未找到应用」。让系统在选择器阶段给出真实答案。
package app.port

/** 外壳动作的结果。 */
enum class ShellOutcome {
    /** 已交给系统（桌面=已发起；安卓=已拉起选择器）。是否真有应用能打开由系统决定。 */
    STARTED,

    /** 目标不可用（文件不存在 / 拿不到可读 URI / FileProvider 未配置），连启动都没做。 */
    UNAVAILABLE,

    /** 系统里没有能处理该类型的应用（仅安卓会返回）。 */
    NO_VIEWER,
}

/**
 * 打开一个导出产物 / 定位一个目录。
 *
 * @param target 不透明引用：桌面=绝对路径；安卓=content URI 或物理路径。
 *   实现方自行决定用哪个应用打开（按扩展名推断 MIME 即可），共享层不传 MIME、不传标题文案。
 */
interface ShellLauncher {
    /** 用系统默认程序打开 [target]。 */
    fun open(target: String): ShellOutcome

    /** 在文件管理器中定位 [target]（选中该文件/文件夹）。 */
    fun reveal(target: String): ShellOutcome

    companion object {
        /** 空实现：测试与无头环境用。 */
        val NOOP: ShellLauncher = object : ShellLauncher {
            override fun open(target: String): ShellOutcome = ShellOutcome.UNAVAILABLE
            override fun reveal(target: String): ShellOutcome = ShellOutcome.UNAVAILABLE
        }
    }
}
