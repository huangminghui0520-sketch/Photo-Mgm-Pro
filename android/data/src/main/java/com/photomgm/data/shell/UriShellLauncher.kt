// data/shell/UriShellLauncher.kt —— 把"打开一个导出的文件"交给系统壳层
//
// ★ T06（A1）：从 `app/AppViewModel.openExportedFile()` 里抽出的平台职责。
//
// 为什么抽到 `:data` 而不是留在 ViewModel：
//   这段逻辑（content:// 判断、FileProvider 授权、ACTION_VIEW + chooser、失败提示）是**平台壳层
//   交互**，与"导出成功后要不要提供打开入口"这个业务决策无关。`AppViewModel` 该回答的是
//   "要不要开"，不该知道 FileProvider 的 authority 怎么拼。
//
// ★ AR3（SAF 授权泄漏 Activity）—— 端口只接 `Context`，**不持任何 Activity**：
//   · 构造时立刻取 `applicationContext`，即使调用方误传 Activity，本对象也不会长期持有它
//     （Activity 重建/旋转后持有旧实例 = 泄漏 + 在已销毁的 Activity 上 startActivity 会崩）；
//   · 因为拿的不是 Activity，启动必须带 `FLAG_ACTIVITY_NEW_TASK`，否则
//     `AndroidRuntimeException: Calling startActivity() from outside of an Activity context
//     requires the FLAG_ACTIVITY_NEW_TASK flag`；
//   · 反过来，`takePersistableUriPermission` 那种需要 Activity 结果回调的授权**不在这里**，
//     仍留在 `:app`（见 AppViewModel 里的 SAF 目录授予流程）—— 那里必须由用户手势触发。
package com.photomgm.data.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 用系统已安装的应用打开一个本 App 导出的文件（目前只有台账 xlsx）。
 *
 * @param context 任意 Context；内部只保留 `applicationContext`（见文件头 AR3 说明）。
 */
class UriShellLauncher(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /** [openDocument] 的结果。三态而非 Boolean：三种情况给用户的提示完全不同。 */
    enum class Result {
        /** 已交给系统选择器（是否真有应用能打开由系统决定，见 [openDocument] 注释）。 */
        STARTED,

        /** 拿不到可用的 URI（路径不对 / FileProvider 未配置），连启动都没做。 */
        URI_UNAVAILABLE,

        /** 系统里没有能处理该 MIME 的应用。 */
        NO_VIEWER_APP,
    }

    /**
     * 打开 [shownPath]（导出记录的 `lastExport`，可能是 `content://` 也可能是物理路径）。
     *
     * 为什么**不预检** `resolveActivity`：Android 11+ 的包可见性收紧后，预检对很多合法应用
     * 返回 null（看不到 ≠ 不存在），会误报"未找到应用"。这里直接用 `createChooser` 启动，
     * 让系统在选择器阶段给出真实答案，失败再按 [Result.NO_VIEWER_APP] 提示。
     *
     * @param mimeType 目标文件的 MIME，决定系统用哪类应用打开。
     * @param chooserTitle 选择器标题（用户可见文案）。
     */
    fun openDocument(shownPath: String, mimeType: String, chooserTitle: String): Result {
        val uri = toReadableUri(shownPath) ?: return Result.URI_UNAVAILABLE
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 见文件头：本对象只持有 applicationContext，必须新开任务栈
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appContext.startActivity(Intent.createChooser(intent, chooserTitle))
            Result.STARTED
        }.getOrElse { Result.NO_VIEWER_APP }
    }

    /**
     * `content://` 原样使用；物理路径转成 FileProvider 的 `content://`。
     *
     * 物理路径**必须**转：直接 `file://` 抛 `FileUriExposedException`（API 24+ 的 StrictMode），
     * 除非临时关 StrictMode——那是把"不该共享 file://"的安全约束绕过去，不做。
     *
     * FileProvider 的 authority 固定为 `${packageName}.fileprovider`，与 `AndroidManifest.xml`
     * 里 `<provider android:authorities="${applicationId}.fileprovider">` 对应。
     * ⚠ 该 provider 声明在 `:app` 的 manifest 里（library 模块无需重复声明）：本类只被 `:app`
     *   使用；若将来有第二个宿主，需确认其 manifest 也声明了同名 authority，否则这里会
     *   稳定返回 [Result.URI_UNAVAILABLE]（`getUriForFile` 找不到 provider 会抛异常，已被 runCatching 兜住）。
     */
    private fun toReadableUri(shownPath: String): Uri? {
        if (shownPath.startsWith("content://")) {
            return runCatching { Uri.parse(shownPath) }.getOrNull()
        }
        return runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                File(shownPath),
            )
        }.getOrNull()
    }
}
