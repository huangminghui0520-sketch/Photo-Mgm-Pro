// desktop/core/sync/PhoneConnection.kt —— A+B 连接方式的状态机（纯判定 + 探测接口）
//
// 已定规则（§9.3 + 裁定 F4/F5）：
//   · 自动探测顺序：先 B（ADB）后 A（UMS 盘符 / 用户手动选的本机目录）；UI 显示当前方式。
//   · 一次同步只走一种方式：启动时锁一份 connection 快照，绝不在同一批里 ADB 拉一半、盘符拉一半。
//   · ADB 可用但用户选了盘符 → 以用户选择为准（显式覆盖生效）。
//   · ADB 不可用（未安装 / 未授权）→ 不报致命错，自动落到 A；两者都不可用才报失败。
//   · 原生 MTP 不支持（Windows 不分配盘符，java.nio 访问不到），A 只认 UMS 盘符与手动目录。
//
// ★ 本文件不调用 ProcessBuilder、不读文件系统：resolve 是纯函数，
//   探测能力经 AdbBridge / UsbDriveScanner 两个接口注入，单测可以完全离线覆盖降级矩阵。
package com.photomgm.desktop.core.sync

import java.nio.file.Path

// 手机侧的一个候选文件；[modifiedMs] 是手机侧 mtime（mtime 兜底用，EXIF 优先）。
internal data class RemotePhoto(
    val name: String,
    val size: Long,
    val modifiedMs: Long?,
)

// 一次同步所锁定的唯一数据源。
// ★ 「不混用」由这个接口保证：Executor 启动时取一份 [PhoneSource] 实例，
// 整批的 list 与 pull 都走它，不会 ADB 拉一半再切到盘符。
// [id] 作为手机目录标识写进 sync-state.json，换目录时由 SyncStateGuard 报冲突。
internal interface PhoneSource {
    val id: String
    val kind: ConnectionKind

    // 列出候选文件名（不保证全都是照片，筛选在暂存区做）。
    fun list(): List<RemotePhoto>

    // 把远端文件拉到暂存区 [staging]；返回 false 表示该文件或连接不可用。
    fun pullTo(photo: RemotePhoto, staging: Path): Boolean
}

// UI 上的连接方式选择。
internal enum class ConnectionMode {
    // 自动：先 ADB，后盘符。
    AUTO,

    // 强制 ADB（不可用时降级到 A，不致命）。
    ADB,

    // 强制 USB 盘符 / 手动目录（不可见时降级到 B，不致命）。
    USB_DRIVE,
}

// 本次同步实际锁定的方式。NONE = 两者都不可用。
internal enum class ConnectionKind {
    ADB,
    USB_DRIVE,
    NONE;

    val label: String
        get() = when (this) {
            ADB -> "ADB"
            USB_DRIVE -> "USB 盘符"
            NONE -> "未检测到"
        }
}

// 一次探测的结果快照。
internal data class ConnectionProbe(val adbAvailable: Boolean, val usbVisible: Boolean)

internal object PhoneConnection {

    // 解析实际连接方式。
    // 显式覆盖的降级口径：用户选的方式若不可用，**回落到 AUTO 的结果**，而不是直接失败。
    // 例如强制 ADB 但未授权、同时盘符可见 → 落 USB_DRIVE；两者都不可用 → NONE。
    fun resolve(mode: ConnectionMode, probe: ConnectionProbe): ConnectionKind {
        val auto = auto(probe)
        return when (mode) {
            ConnectionMode.AUTO -> auto
            ConnectionMode.ADB -> if (probe.adbAvailable) ConnectionKind.ADB else auto
            ConnectionMode.USB_DRIVE -> if (probe.usbVisible) ConnectionKind.USB_DRIVE else auto
        }
    }

    private fun auto(probe: ConnectionProbe): ConnectionKind = when {
        probe.adbAvailable -> ConnectionKind.ADB
        probe.usbVisible -> ConnectionKind.USB_DRIVE
        else -> ConnectionKind.NONE
    }

    // 失败文案按方式分开（§9.3 明确要求不能合并成一句「连不上手机」）：
    // ADB 要 USB 调试授权，盘符要文件传输模式，两者都失败时两条都给。
    fun failureText(kind: ConnectionKind): String = when (kind) {
        ConnectionKind.ADB ->
            "ADB 连接失败：请确认已安装 platform-tools、手机开启 USB 调试并点了「允许 USB 调试」。"
        ConnectionKind.USB_DRIVE ->
            "USB 盘符读取失败：请把 USB 模式切到「传输文件」并确认手机以大容量存储出现；" +
                "原生 MTP 设备不分配盘符，暂不支持，可在弹窗里手动选择本机已挂载的目录。"
        ConnectionKind.NONE ->
            "未检测到手机，请检查 USB 连接 / 驱动 / USB 调试：" +
                "ADB 需要「允许 USB 调试」；盘符方式需要「传输文件」模式（原生 MTP 不支持）。"
    }
}
