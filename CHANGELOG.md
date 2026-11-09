# Changelog

<!-- Keep a Changelog 格式，版本号遵循语义化版本 -->
[Keep a Changelog]: https://keepachangelog.com/zh-CN/1.1.0/

## [1.0.3] - 2026-11-09（未发布）

### 修复
- 恢复「adb 版本过低」提示的正确指引（重新安装本应用），不再误报为 USB 连接问题
- S2 测试补处置文案断言，防止同类回归

### 变更
- Windows `packageVersion` / `DESKTOP_VERSION`：`1.0.2 → 1.0.3`

### 待发布
- 供应链加固（第三方二进制来源/校验记录、`.gitattributes` binary 声明、adb 候选落点收紧）
- CI 出包禁用构建缓存 + 产物存在性断言

## [1.0.2] - 2026-11-08

### 修复

- **多设备/换机时 adb 命令被拒**：`AdbBridge` 加 `-s <serial>` 设备定向，`AdbPhoneSource.id` 加 serial 前缀
- **换机后无法读取新手机目录**：`selfCheck()` 区分 offline / unauthorized / 无设备三种状态；手机导入弹窗加“重新检测”按钮
- **事件卡片缩略图不支持鼠标滚轮横向滚动**：LazyRow 加 `onPointerEvent`
- **滚轮与事件列表重叠滚动**：`consume()` 防冒泡
- **查看大图弹窗底部缩略图不支持鼠标滚轮**
- **`+N` 标记显示“比首屏多 N 张”有歧义**：改为始终显示总张数
- **未匹配卡片在“待确认/无照片/无时间”三档误显示**：收口为只在 ALL/UNMATCHED 档显示
- **未匹配卡片在“未匹配”档不显示**：修复筛选门控
- **悬停高亮不够明显**：普通卡换 `stage`，warm 卡 alpha `0.45/1.00`
- **连续空格文件名导致 `pull` 失败**：`parseLsEntryByTokens` 从原文取文件名
- **回退路径 `ls` 注释与实现相反**：修正
- **`filtered` 无法区分“区间外”与“无法读取时间”**：拆为 `outOfRange` + `unreadable`，UI 分别提示
- **单机用户升级后误报 PHONE_DIR 冲突**：`SyncStateGuard` 兼容旧格式 `phoneDir`，单机静默升级

### 变更

- Windows `packageVersion`：`1.0.1 → 1.0.2`

### 迁移说明

- `sync-state.json` 的 `phoneDir` 字段新增设备序列号前缀（`adb:<serial>:<path>`）。升级后自动兼容旧格式、不产生额外提示；换机后首次同步会提示一次 PHONE_DIR 冲突（安全网的预期行为），点「仍要继续」即可通过。

## [1.0.1] - 2026-11-07

### 修复

- 「仅原图」模式仍输出带水印主图（`groupDupContent` tie 全纳入，共享层修复，双端受益）
- 日历弹窗不贴按钮下方（改为水平居中 + 贴下方）
- 时间选择为内嵌列（改为点击弹窗）
- 事件缩略图不可横向滚动（改为 `LazyRow`）
- 事件卡片无悬停反馈（增加背景高亮）
- 手机导入弹窗执行按钮位置不符合操作流（移至底部动作区）

### 变更

- Android `versionCode`：`7 → 8`
- Android `versionName`：`1.0.0 → 1.0.1`
- Windows `packageVersion`：`1.0.0 → 1.0.1`

## [1.0.0] - 2026-11-07

### 新增

- 手机导入照片（Windows 桌面版）：按时间区间从手机导入，自动建子文件夹，支持断点续传
- 时间选择控件：日历选日期 + 时分列选时间，5 分钟步进（含 59 档）
- Android App 与 Windows 桌面版双端

### 修复

- ADB 自动查找：Windows 上扫描 `C:`–`Z:` 的 Android SDK 路径
- 手机导入提示：合并为一句 + 分步排查

### 构建

- 仓库根为唯一 Gradle 根，8 个项目统一构建
