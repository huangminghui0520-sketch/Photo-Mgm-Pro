// app/core/adapter/AndroidLogTextStorage.kt —— `app.port.LogTextStorage` 的安卓实现（T11）
//
// 只回答「怎么把一段日志原文落下去 / 读回来」，防抖时机由共享的 `DebouncedTextStore` 决定
// （端口注释明确要求这一点，见 `app/port/LogTextStorage.kt`）。
//
// ★ `persist` 必须同步写完：`DebouncedTextStore.flush()` 的语义是"返回即已落盘"——
//   离开页面 / 开始分析前都会调它。`SettingsStore.logsText` 的 setter 是同步写
//   `PrefsSettingsStorage.logsText`（独立 prefs 文件 + commit），满足这条要求。
package com.photomgm.app.core.adapter

import app.port.LogTextStorage
import com.photomgm.app.SettingsStore

/** 日志原文落盘（独立 prefs 文件，见 `PrefsSettingsStorage.logsText`）。 */
class AndroidLogTextStorage(private val store: SettingsStore) : LogTextStorage {

    override fun load(): String = store.logsText

    override fun persist(text: String) {
        store.logsText = text
    }
}
