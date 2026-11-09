# Third-Party Notices

本仓库随 Windows 桌面版分发了以下第三方二进制文件。它们的版权归各自所有者，按原许可证分发。

## Android SDK Platform-Tools

- **文件**：
  - `desktop/ui/appResources/common/tools/platform-tools/adb.exe`
  - `desktop/ui/appResources/common/tools/platform-tools/AdbWinApi.dll`
  - `desktop/ui/appResources/common/tools/platform-tools/AdbWinUsbApi.dll`
- **版本**：37.0.1-15733141（`adb version` 1.0.41）
- **来源**：https://developer.android.com/studio/releases/platform-tools
- **SHA256**：
  - `adb.exe`：`b4a6b455702684652cccf7b46258b29e653538904359a58fd4931cf3ef286b3f`
  - `AdbWinApi.dll`：`c1d653030b4bde65d3e07e4d0b0979e17be56df1436cdd15528630f27808050d`
  - `AdbWinUsbApi.dll`：`0710e894d9b40f71a670c13c694079d564c92c1279da382cfe4850983aaebe1b`
- **许可**：Android SDK Platform-Tools 受 [Android SDK License](https://developer.android.com/studio/terms) 约束。本应用仅在用户主动使用「手机导入照片」功能时调用 `adb.exe`，不修改、不重新分发其源代码。
- **签名**：三个文件均带 Google LLC 的有效 Authenticode 签名（证书有效期 2024-04-08 ~ 2027-04-11）。
