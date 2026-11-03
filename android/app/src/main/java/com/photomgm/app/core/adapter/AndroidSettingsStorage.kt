// app/core/adapter/AndroidSettingsStorage.kt —— `app.port.SettingsStorage` 的安卓实现（T11）
//
// 这一层只做「共享契约 `Settings` ↔ 安卓业务存储 `SettingsStore`」的翻译，
// 不碰 SharedPreferences 本身（那是 T06 拆出去的 `:data` `PrefsSettingsStorage`）。
//
// ★ `load()` 必须清洗 `namingTemplate`：端口契约要求返回**业务上合法**的设置。
//   存量用户配置里可能有过 2 之类的越界值（历史版本没有校验），
//   不清洗的话共享层会拿着一个不存在的模板编号去拼文件夹名，结果是 `0_`/`2_` 这种诡异前缀。
//   清洗规则与桌面端同源（`Settings.sanitizeNamingTemplate`），不是这里另定的口径。
//
// ★ `save()` 逐字段赋值而不是"整份替换"：`SettingsStore` 上还挂着**状态缓存**
//   （parsedCache / classifyCache / photosCache / coarseCount / overrideMap / marked），
//   它们不属于 `Settings` 契约，但同住一个存储对象。任何"清空重写"都会把它们顺手抹掉——
//   下一次打开 App 就丢掉了"恢复关闭前分析结果"的能力，且不会有任何报错。
package com.photomgm.app.core.adapter

import app.api.Settings
import app.port.SettingsStorage
import com.photomgm.app.SettingsStore

/** 设置持久化：共享 [Settings] 与安卓 [SettingsStore] 之间的翻译。 */
class AndroidSettingsStorage(private val store: SettingsStore) : SettingsStorage {

    override fun load(): Settings = Settings(
        sourceDirs = store.sourceDirs,
        outputDir = store.outputDir,
        patrolDate = store.date,
        // ★ 越界值回落默认（见文件头说明）
        namingTemplate = Settings.sanitizeNamingTemplate(store.namingTemplate),
        dedupMode = store.dedupKeepMode,
    )

    override fun save(settings: Settings) {
        // 逐字段写：只覆盖设置项，状态缓存原样保留
        store.date = settings.patrolDate
        store.sourceDirs = settings.sourceDirs
        store.outputDir = settings.outputDir
        store.namingTemplate = settings.namingTemplate
        store.dedupKeepMode = settings.dedupMode
    }
}
