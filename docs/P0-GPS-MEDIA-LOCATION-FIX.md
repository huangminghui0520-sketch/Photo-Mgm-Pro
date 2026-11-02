# P0 修复报告：照片 GPS 被系统剥离（`ACCESS_MEDIA_LOCATION`）

> 一句话结论：**根因是缺一个运行时权限，不是读取代码有 bug。** Android 10+ 在 App 读取媒体字节
> 的**那一刻**把 EXIF 里的 GPS 值区清零（标签保留、长度不变、值变成 `nan`），看起来就像"照片本来没有定位"。
> 已按「manifest 声明 → 运行时一并申请 → 未授权时说清降级口径 → 作废旧缓存」四处最小改动修复，
> 并在真机上用正/负向两轮验证。

- 关联缺陷：`docs/ANDROID-REAL-DEVICE-P2-REPORT.md` §8 P0-1
- 关联计划：`docs/REFACTOR-EXECUTION-PLAN.md` §9 前提 **P-2b**（要求"T11 之前修复"）
- 提交：`fix(android): 声明并申请 ACCESS_MEDIA_LOCATION，修复 GPS 被系统剥离`

---

## 1. 现象：三个观测点，指向同一个原因

真机 P-2（vivo V2232A / Android 16 / API 36）第一轮实测：

| 观测点 | 实测 | 说明 |
| --- | --- | --- |
| App 缓存 `cache_photos` | **65 条记录 `latitude`/`longitude` 全为 `null`**，`captureTimeMs` 正常 | 说明 EXIF 时间读到了、位置没读到 |
| 导出 ZIP 内照片 EXIF | GPS 标签存在，值为 **`(nan, nan, nan)`**，文件长度与素材**完全相同** | 不是重编码（重编码会改长度），是读写过程中被改写 |
| 去重结果 | 未出现误并（素材里每个事件内照片间隔 30s） | 恰好绕过了缺陷，不能当作没问题 |

判据工具：`scripts/golden/check_gps.py`（本次新增，见 §5.3）。

---

## 2. 根因

### 2.1 平台机制：剥离发生在"读取那一刻"

Android 10（API 29）起，媒体读取走 MediaProvider + FUSE。App 打开媒体文件时，FUSE 守护进程按
`hasAccessMediaLocation` 决定是否 **原地脱敏**：保留 EXIF 结构、把 GPS 值区写成 0 / NaN。

两个后果直接解释了现象：

1. **读路径受影响，不是写路径**——所以"导出的照片丢位置"其实是"读出来就是空的"，App 只是把读到的空值写进 ZIP；
2. **长度不变**——所以逐字节 diff 只看到十几个字节差异，容易误判为"编码器把 EXIF 弄坏了"。

### 2.2 我们缺什么

修复前 `android/app/src/main/AndroidManifest.xml` 只有：

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
```

`MainActivity` 运行时也只申请了这一个。**`ACCESS_MEDIA_LOCATION` 从未出现过**——`dumpsys package`
里连这一行都没有（只有声明过的权限才会出现在 `requested permissions` 列表里），这是最直接的证据。

### 2.3 为什么"只改 manifest"不够

`ACCESS_MEDIA_LOCATION` 是 **dangerous 级运行时权限**（API 29 引入）。只在 manifest 声明而不运行时申请，
系统视为未授予 → 继续剥离。所以修复必须"声明 + 申请"成对出现。

### 2.4 为什么单测、模拟器都没暴露

| 环境 | 为什么测不出 |
| --- | --- |
| Robolectric 单测 | 不走真实 MediaProvider/FUSE，`ExifInterface` 读到的是测试桩数据，脱敏逻辑根本不在链路上 |
| 程序化生成的测试图 | `TestImages.kt` 生成的图未必带 GPS，"读到 null"看起来完全正常 |
| 模拟器 | 理论可复现，但一直卡在 AEHD 启动失败（`Unable to open AEHD device`），本轮改走真机 |

**结论**：这是**只有真机 + 真实 MediaStore + 带 GPS 素材**三者同时具备才会暴露的缺陷。

---

## 3. 最小修复方案：四处改动，每一处都不可省

| # | 改动 | 文件 | 为什么必须 |
| --- | --- | --- | --- |
| 1 | 声明 `ACCESS_MEDIA_LOCATION` | `AndroidManifest.xml` | 不声明则运行时申请直接失败（系统拒绝授予未声明的权限） |
| 2 | 运行时**一并**申请，并按 SDK 版本裁剪清单 | `MediaPermissions.kt`（新增）+ `MainActivity.kt` | 29 分水岭（无该权限概念）/ 33 分水岭（`READ_MEDIA_IMAGES` 取代 `READ_EXTERNAL_STORAGE`） |
| 3 | 未授权时**说清降级口径** | `AppViewModel.kt` + `SettingsDialog.kt` | 静默降级会让用户把去重结果当准的用（真实损失是"以为已去重、实际导出两份"） |
| 4 | **作废旧缓存** | `SettingsStore.kt` | 老用户升级后"打开 App 从缓存恢复"不会再读 EXIF，权限修了也白修 |

额外的第 5 处不是 App 代码，而是**验收手段**：真机脚本加 `pm grant` + 双观测点断言（§5）。

### 3.1 为什么用"纯函数 + 版本位"，而不是就地改

- **权限清单写成纯函数** `MediaPermissions.plan(sdkInt)`：33/29 两个分水岭以前散在 Composable 里，
  只能靠换真机试；抽出来后 `MediaPermissionsTest` 在 JVM 上把 26/28/29/33/36 全跑一遍。
- **降级判据同源**：`geoAvailable(sdkInt, granted)` 与申请清单出自同一处，避免"申请两个、判定一个"的漂移。
- **缓存版本位不用运行时权限状态**：权限状态在 `init` 时未必已定（对话框还没回来），拿它当判据会让
  "刚授予"的用户反复丢缓存。改用静态常量 `media-location-v1`，语义是"这批缓存是在能读 GPS 的
  读取逻辑下算出来的"；补授权限后的口径由 `onMediaPermissionsResult` 提示重新解析。

---

## 4. 实际改动

### 4.1 App 代码

| 文件 | 改动 |
| --- | --- |
| `android/app/src/main/AndroidManifest.xml` | 新增 `ACCESS_MEDIA_LOCATION` 声明 + 一段说明"为什么会剥离、为什么必须运行时授予" |
| `android/app/src/main/java/com/photomgm/app/MediaPermissions.kt` | **新增**。`plan(sdkInt)` / `mediaPermission(sdkInt)` / `supportsGeo(sdkInt)` / `geoAvailable(sdkInt, granted)` + 降级提示文案常量 |
| `.../MainActivity.kt` | `RequestPermission` → `RequestMultiplePermissions`；结果回调转交 `vm.onMediaPermissionsResult` |
| `.../AppViewModel.kt` | `UiState` 增 `geoAvailable: Boolean?`；`init` 同步判定一次；新增 `onMediaPermissionsResult`（记录 + 未授权提示 + 补授权限后提示重新解析） |
| `.../SettingsStore.kt` | 新增 `KEY_CACHE_GEO`/`GEO_CACHE_VERSION`；`markCacheZone()` → `markCacheVersion()`（同时写时区与 GPS 两个版本位）；四份派生缓存统一走 `cacheUsable()`（全有或全无） |
| `.../ui/SettingsDialog.kt` | 「重复照片」区在 `geoAvailable == false` 时显示红色提示条，写明降级口径与恢复方式 |

### 4.2 测试

| 文件 | 覆盖 |
| --- | --- |
| `android/app/src/test/.../MediaPermissionsTest.kt`（新增） | 权限清单随 SDK 变化（26/28/29/33/36）；授权结果 → 可读性判定（含"照片权限给了但位置没给"这一修复前真机状态）；降级文案必须含"位置""文件名序号" |
| `android/app/src/test/.../SettingsStoreGeoCacheTest.kt`（新增） | 无 GPS 版本位 / 只有时区位 / 位不匹配 → 四份缓存全废；两位齐备 → 正常读出；写入自动补位；作废不牵连用户数据 |
| `android/app/src/test/.../SettingsStoreZoneMigrationTest.kt` | 既有测试补写 GPS 位，把变量隔离在"时区"这一道（避免一个测试同时管两件事） |

### 4.3 工具链

| 文件 | 改动 |
| --- | --- |
| `scripts/golden/check_gps.py`（新增） | 两个检查口：`--prefs`（cache_photos 的 lat/lng 统计）、`--photos`（ZIP/目录里每张图的 EXIF GPS）；`--expect any\|none` 支持负向验证 |
| `scripts/golden/run_android_real.sh` | ① `pm grant ACCESS_MEDIA_LOCATION`（`GEO_PERM=0` 跑负向）；② 校验 grant 真的进了 uid；③ 新增 9.5 步双观测点断言；④ 预检"屏幕已解锁 + uiautomator 可用"；⑤ dump 落盘改 `/data/local/tmp`；⑥ 解析完成增 `cache_parsed` 判据 |
| `scripts/golden/run_android.sh` | 同步 grant 一行（与真机脚本保持一致） |

---

## 5. 验证

### 5.1 静态与单测

```bash
cd android && ./gradlew.bat test --console=plain
```

覆盖 `:app` / `:data` / `:shared:algorithm` 三模块 debug+release 单测，全绿（含新增 15 条断言）。

### 5.2 真机正向（`GEO_PERM=1`，权限已授予）

见 §6 实测表。

### 5.3 判据工具本身要先被验证

`check_gps.py` 写完后先用**修复前的产物**跑了一遍，确认它能复现缺陷（否则"修好了"可能只是判据不敏感）：

```
# 修复前的 App 缓存
$ python scripts/golden/check_gps.py --prefs .p2work/dev-settings.xml
  照片 65 张 | 读到 GPS 0 张 | 无 GPS 65 张 → FAIL（expect=auto）

# 修复前的导出 ZIP（GPS 标签在、值为 nan）
$ python scripts/golden/check_gps.py --photos '.p2work/run-a/raw/照片分类_20260814_121837.zip'
  照片 56 张 | 读到 GPS 0 张 | 无 GPS 56 张 → FAIL（expect=auto）
```

★ 这里踩到一个"判据假绿"：初版 `_dms()` 只判 `== 0.0`，而剥离后的值是 `nan`（`nan == 0.0` 为 False），
于是 52 张全 `nan` 被算成"有 GPS"，报 PASS。已改为 NaN 一律视为无 GPS——**验收工具必须先能测出旧缺陷**。

---

## 6. 真机实测（正向：已授予位置权限）

> 环境：vivo V2232A / Android 16 (API 36) / arm64-v8a / Asia/Shanghai
> 命令：`SKIP_INSTALL=1 KEEP_PHOTOS=1 bash scripts/golden/run_android_real.sh .p2work/run-p0fix2`

| 观测点 | 修复前（`run-a`） | 修复后（`run-p0fix2`） | 判定 |
| --- | --- | --- | --- |
| `cache_photos` 有 GPS | **0 / 65** | **59 / 65** | ✅ 通过 |
| 导出 ZIP 照片有 GPS | **0 / 56**（标签在、值 `nan`） | **52 / 56** | ✅ 通过 |
| ZIP 照片 vs 素材逐字节 | 52 张各差 14 字节（GPS 值区被清零） | **56 / 56 完全相同** | ✅ 通过 |
| 导出耗时 | — | 解析 <5s、导出落盘 ~6s、整脚本 1 分 12 秒 | ✅ 无退化 |
| 双跑幂等（V2） | 通过 | **57 张照片指纹 0 差异** | ✅ 通过 |

**6 张没有 GPS 的照片全部对得上账**（不是"又漏了"）：

| 照片 | 为什么没有 GPS |
| --- | --- |
| `IMG_20260814_112000/112030/112000_original.jpg` | 素材**设计上就没有** GPS（用于测"无定位照片"分支） |
| `IMG_20260814_120000/151000.jpg` | 素材是**无 EXIF** 图（连拍摄时间都没有，靠文件名兜底） |
| `IMG_20260814_1030.heic` | 误筛文件（HEIC，不在候选扩展名内，无 EXIF GPS） |

导出 ZIP 里 4 张无 GPS = 上面 6 张中进入事件的 4 张（HEIC 与 2 张未匹配不在 ZIP 内）。

**快照对比**：与修复前基线的 57 条比，**52 条照片 sha256 变化**、4 条无 GPS 的照片字节不变 + 1 条新增（xlsx）。
这不是回归，正是"GPS 被写回去了"的字节级证据：变的那 52 条恰好是有 GPS 的那 52 张。

**唯一不稳定的条目**：`巡查台账_<TS>.xlsx` 两次运行 sha256 不同（台账内部含导出时刻），
与 §P1-2 的既有结论一致——比较时要用 `--ignore` 屏蔽，不能整类跳过。

### 6.1 负向验证：结果不干净，登记为待复现

想证明"没权限就一定读不到"，做了两次尝试：

| 尝试 | 做法 | 结果 | 是否有效 |
| --- | --- | --- | --- |
| ① | `GEO_PERM=0`（`pm revoke` 后跑脚本） | 仍读到 59 张 | ❌ 无效：App 启动即申请权限，自动化点击把系统对话框点成"允许"了 |
| ② | `pm clear` → 只授权照片权限 → 确认 `ACCESS_MEDIA_LOCATION: granted=false` → 解析 | **前 9 张（按拍摄时间序）无 GPS，其余 50 张仍读到** | ⚠️ 结论不成立 |

第 ② 次是本报告最反直觉的发现：**剥离不是一刀切**。同样的未授权状态，时间最早的 9 张丢了 GPS，
后面的照常读到。`dumpsys` 确认全程 `granted=false`，且这 9 张的 `captureTimeMs` 正常（说明 EXIF 打开成功、只有 GPS 段被清）。

推测方向（未验证，需专项复现）：
- MediaProvider/FUSE 对"该 uid 是否有位置权限"的判定有**缓存或短时窗口**，首批读取走了"未授权"分支，之后复用了错误/过期的判定结果；
- 也可能是重装/清数据后 uid 的权限状态尚未同步到 MediaProvider，前几次 open 落在旧快照上。

**为什么这条必须记下来**（而不是"反正修好了"）：
1. 它说明"未授权 = 所有照片无定位"是不准确的，**部分可用**才是可能的状态；
2. 部分可用比全部不可用**更危险**：`ExifContentDedupStrategy` 只在"双方 GPS 均有效"时用距离、
   "双方均无效"时走降级，**一方有一方无直接判为不重复**——于是本该合并的双份会漏并，
   而用户完全看不出来（照片只是多了一份）；
3. 它给 T11 一条明确要求：**不能假设 GPS 是"全有或全无"**，去重判据必须能处理混合状态。

复现清单（下次遇到时照做）：

```bash
adb shell pm clear com.photomgm.app
adb shell pm grant com.photomgm.app android.permission.READ_MEDIA_IMAGES
adb shell dumpsys package com.photomgm.app | grep 'ACCESS_MEDIA_LOCATION: granted'   # 必须是 false
# 注入 prefs → 启动 → 解析 → 拉 cache_photos → check_gps.py --expect none
# 若"部分有 GPS"，记录丢 GPS 的名单与张数（本次：前 9 张，按拍摄时间序）
```

---

## 7. 边界：这次**没有**做什么

| 项 | 说明 | 风险 |
| --- | --- | --- |
| 只覆盖"路径读取" | 现在 `MediaStorePhotoSourceProvider` 用 `DATA` 列取物理路径，`ExifInterface(path)` 直读；未改用 `MediaStore.setRequireOriginal(uri)` 的 `content://` 读法 | 若某天 `DATA` 为空改走 URI，需要再补 `setRequireOriginal`，否则 GPS 会再次为 null（但那时 `Photo.latitude` 会是 null 而非崩，可被 `check_gps.py` 发现） |
| 未处理"部分照片授权" | Android 14 的 `READ_MEDIA_VISUAL_USER_SELECTED`（用户只选几张）未申请 | 用户选"选择部分照片"时照片数会偏少；不影响 GPS 判定 |
| 未加"去设置页"引导 | 权限被"不再询问"后，只在设置页给出文字说明 + 重新解析提示 | 用户需自行去系统设置授权；属可接受的最小改动 |
| 未重建安卓黄金基线 | 旧基线是在"GPS 被剥离"前提下拍的 | T11 之前必须重建（见 §8.2） |

---

## 8. 对后续任务的影响

### 8.1 对 T03（纯函数上移 A：EXIF 时间 / SHA-256 / 扩展名）

| 影响 | 具体调整 |
| --- | --- |
| **不得拆分 `readAll`** | 现在 `ExifPhotoReader.readAll` **一次** `ExifInterface` 构造同时读时间 + GPS（C2 优化，203→67ms）。把时间解析上移到 `shared/domain/exif/ExifTime.kt` 时，**只搬字符串 → epoch 的纯函数**，不能顺手拆成"读时间一次、读 GPS 一次" |
| **`readGps` 的 null 有两种含义** | ①照片本身无 GPS；②权限被剥离。上移接口/写文档时必须写明"null 不等于 0，也不等于坏数据"，否则下游很容易写成 `?: 0.0` |
| **D9 的"取并集"要连带补样本** | T03 让 EXIF 时间多认 `yyyy-MM-dd` 格式后，可读面扩大；建议同时补一张"短横格式 + 带 GPS"的样本，避免出现"时间读到了、GPS 没读到"的错觉 |
| **L3 验证前提变了** | T03 的"黄金样本必须全等"要在**GPS 已可读**的前提下做。PC 基线不受影响（PC 从未被剥离），安卓侧基线需要按新产物重建一次 |

### 8.2 对 T11（ViewModel 瘦身）

| 影响 | 具体调整 |
| --- | --- |
| **"踩坑修复搬运清单"从 4 条变 5 条** | 原计划要求搬：导出重入闸门、`runCatchingCancellable`、`dupGroups` 原子发布、缓存时区版本位。现在必须再加一条：**GPS 语义版本位 + 降级口径**（`markCacheVersion` + `geoAvailable`） |
| **`geoAvailable` 必须跟着 `UiState` 投影走** | T11 删 `PhotoRepository.kt`、把逻辑委派给 shared 用例时，`geoAvailable` 属于"平台能力状态"，不属于算法；一旦在瘦身中被抹掉，设置页的降级提示随之消失，用户重新回到"以为去重是准的" |
| **去重调用点要显式传 `DedupConfig`** | 现在 `groupDupContent` 有默认参数（时间 1s / GPS 10m / 序号 1000）。T11 重构调用点时，禁止"因为 GPS 恒 null 就把 `gpsToleranceM` 去掉"——GPS 现在真的能读到，容差是有效参数 |
| **降级分支不能被当作死代码删掉** | `ExifContentDedupStrategy` 的"双方 GPS 均无效 → 时间 + 派生后缀 + 序号"分支，在权限被拒 / 照片本无定位时仍然生效；T11 必须为它保留单测（`GpsGrouperEngineEdgeTest` / `Dedup` 相关） |
| **基线重建** | GPS 可读后去重会从降级分支切回「时间 + GPS 距离」分支，照片分组可能变化 → `docs/golden/android-baseline.json` / `android-ledger-baseline.json` 必须重建，否则 T11 的"黄金样本全等"是在和错误基线比 |

### 8.3 一句话回答"为什么必须先修 P0 再开 T03"

T03 要搬的是 **EXIF 读取的语义**，T11 要搬的是 **去重的判据**。两者都建立在
"`Photo.latitude` 是什么"这个前提上。修复前这个前提是"恒 null"——这是**环境造成的假事实**。
按假事实重构，会得到"看起来很对、真机上全错"的代码，而且错误被写进共享层后，两端一起错。

---

## 9. 回滚

单提交，`git revert` 即可。回滚后：

- App 回到"GPS 恒 null"，去重走降级分支（功能可用，但判据退化）；
- `cache_geo_version` 位会留在 prefs 里，回滚后的版本读缓存时 `cacheUsable()` 仍成立——
  **不会**因为回滚触发全量重算，但缓存里的 `latitude` 是旧包写的 null（与新包行为一致，无副作用）。
