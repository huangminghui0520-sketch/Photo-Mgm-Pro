// desktop/core/WindowState.kt —— 窗口几何（★ 桌面端独有，不在跨端契约里）
//
// ★ T09 的减法、T10 的落点：它原来挂在 `Settings` 上，于是一起上了共享层。
//   问题是安卓端永远没有窗口 —— 那份 `Settings` 就要背一个恒为 null 的字段，
//   而每一处读 settings 的代码都得解释"这个 null 不是我漏填"。
//   现在它只活在桌面侧：由 `PcSettingsStorage` 存进同一份 settings.json 的 `window` 键，
//   由 UI 通过 `DesktopCore.windowState` / `saveWindow` 读写。
//
// ★ 但仍放在**根包**而不是 `adapter` 子包：UI 要用它，而 UI 不该去认识"适配器"这一层。
//   它是桌面的**数据契约**，与 `app.api` 里的那些并列，只是作用域小一号。
package com.photomgm.desktop.core

/** 窗口位置与尺寸（纯数据；本程序不做任何窗口操作）。 */
data class WindowState(val x: Int, val y: Int, val width: Int, val height: Int)
