# Changelog

<!-- Keep a Changelog 格式，版本号遵循语义化版本 -->
[Keep a Changelog]: https://keepachangelog.com/zh-CN/1.1.0/

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
