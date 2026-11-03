// ui/app/AppShell.kt —— 窗口壳：顶栏 / 消息 / 页面 / 状态栏 / 弹窗
//
// ★ 全窗口只有四层，每层回答一个固定问题：
//     顶栏   —— 这是什么工具、现在能不能导出？（品牌 + 状态 + 导出 + 设置）
//     消息   —— 出什么事了？（错误必须由人确认才消失，不自动隐藏）
//     页面   —— 唯一的页面：巡查日期 + 日志条 + 事件卡片列表（见 ui/page/WorkspacePage）
//     状态栏 —— 数据是什么口径？有哪些快捷键？（永不遮挡内容）
//
// ★ 快捷键在这里一次性收口（onPreviewKeyEvent）：F5 分析、Ctrl+O 导入日志、Ctrl+E 导出、
//   Ctrl+, 设置、Esc 关闭覆盖层 / 清空选择。键盘只是**加速**，不是唯一入口 ——
//   分析在日志条旁、导入 .txt 在输入框右上、导出/设置在顶栏、关闭在弹窗右上角，全都点得到，
//   所以状态栏右下角仍然写按键表，而不是"点哪里"。
// ★ 唯一不吃键盘的是日历（见 ui/component/DateField.kt）：它有意不接方向键/回车/Tab
//   这类"在日期网格里移动"的操作，选日期一律用鼠标点格子。日期在这里被当成一个
//   一次性的选择器，不是需要连续编辑的表格 —— 多一套网格键盘导航，收益远小于走查成本。
//
// ★ 上一版有"步骤栏 + 四个互斥页面"，这一版把步骤栏整个删掉：
//   单页结构里没有"第几步"，只有"在不在看某个事件"——那件事由弹窗表达。
package com.photomgm.desktop.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.api.AppState
import app.api.CoreStatus
import com.photomgm.desktop.ui.component.BusyOverlay
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.InlineNotice
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.bg
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.dialog.AssignSheet
import com.photomgm.desktop.ui.dialog.ConfirmReanalyzeSheet
import com.photomgm.desktop.ui.dialog.DatePickerLayer
import com.photomgm.desktop.ui.dialog.PhotosSheet
import com.photomgm.desktop.ui.dialog.SettingsSheet
import com.photomgm.desktop.ui.model.Busy
import com.photomgm.desktop.ui.model.LocalNotice
import com.photomgm.desktop.ui.model.NoticeTone
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.exportBlockReason
import com.photomgm.desktop.ui.model.exportKey
import com.photomgm.desktop.ui.page.WorkspacePage
import com.photomgm.desktop.ui.page.patrolDateNotice
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSize
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun AppShell(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(PmColor.canvas)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.F5 -> {
                        if (state.events.isNotEmpty()) onUi(ui.copy(sheet = Sheet.ConfirmReanalyze)) else actions.analyze()
                        true
                    }

                    event.isCtrlPressed && event.key == Key.O -> {
                        actions.importLogFile(); true
                    }

                    event.isCtrlPressed && event.key == Key.E -> {
                        // 与顶栏同一个判定：条件不足时给出原因，而不是"按了没反应"。
                        val reason = exportBlockReason(state, ui.busy)
                        if (reason == null) actions.export()
                        else onUi(ui.copy(notice = LocalNotice(reason, NoticeTone.WARN)))
                        true
                    }

                    event.isCtrlPressed && event.key == Key.Comma -> {
                        onUi(ui.copy(sheet = Sheet.Settings)); true
                    }

                    event.key == Key.Escape -> {
                        // Esc 只做一件事：关掉当前覆盖层/选择。不退出、不改数据。
                        // 顺序＝层次：日历浮层在最上，其次弹窗，最后才是照片勾选。
                        when {
                            ui.datePickerOpen -> onUi(ui.copy(datePickerOpen = false))
                            ui.sheet != null -> onUi(ui.copy(sheet = null))
                            // 清勾选时连锚点一起清：留着锚点，下一次 Shift 会从"上一批的起点"连选。
                            else -> onUi(ui.copy(selectedPhotoIds = emptySet(), selectionAnchor = null))
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        val width = maxWidth.value.toInt()
        val height = maxHeight.value.toInt()
        val density = LocalDensity.current
        // 日期字段在窗口里的像素位置：由 LogBar 的字段上报，供日历浮层贴边定位。
        val dateAnchor = remember { mutableStateOf(IntRect.Zero) }

        // 渲染日志：把"这个宽度下排成了什么样"写成可 diff 的一行（DPI 走查与回归对比用）。
        // ★ 正常运行时 UiLog 静默，这里直接跳过 —— 不构造字符串、不触碰任何 IO。
        LaunchedEffect(width, height, ui.sheet, ui.busy, ui.logEntryVisible, state.events.size) {
            if (!UiLog.isEnabled) return@LaunchedEffect
            val sheet = when (val s = ui.sheet) {
                null -> if (ui.logEntryVisible) "none+logentry" else "none"
                Sheet.Settings -> "settings"
                is Sheet.Photos -> "photos:${s.target}"
                is Sheet.Assign -> "assign:${s.mode}:${s.photoIds.size}"
                Sheet.ConfirmReanalyze -> "confirm"
            }
            UiLog.line(
                "render w=$width h=$height tile=${com.photomgm.desktop.ui.model.tileSize(width)} " +
                    "maxTiles=${com.photomgm.desktop.ui.model.maxTiles(width)} sheet=$sheet busy=${ui.busy?.name ?: "-"}",
            )
            UiLog.line(
                "state status=${state.status} events=${state.events.size} photos=${state.photos.size} " +
                    "unmatched=${state.unmatchedPhotoIds.size} ledger=${state.ledger.size} " +
                    "parsed=${state.parsed?.eventCount ?: 0} filter=${ui.filter} q='${ui.query}'",
            )
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(state, ui, onUi, actions)
            Hairline()
            MessageArea(state, ui, onUi, actions)

            Box(Modifier.fillMaxWidth().weight(1f)) {
                WorkspacePage(state, ui, onUi, actions, width) { rect -> dateAnchor.value = rect }
                if (ui.busy != null) {
                    BusyOverlay(
                        busy = ui.busy,
                        state = state,
                        onCancel = if (ui.busy == Busy.ANALYZE) actions.cancelAnalyze else actions.cancelExport,
                    )
                }
            }

            Hairline()
            StatusBar(state, ui, onUi)
        }

        // 日历浮层：画在整页之上、不参与布局（页面高度/滚动位置都不变）。
        if (ui.datePickerOpen && ui.sheet == null) {
            DatePickerLayer(
                date = state.settings.patrolDate,
                anchor = dateAnchor.value,
                windowWidth = with(density) { maxWidth.roundToPx() },
                windowHeight = with(density) { maxHeight.roundToPx() },
                onPick = { picked ->
                    actions.setPatrolDate(picked)
                    onUi(
                        ui.copy(
                            datePickerOpen = false,
                            notice = patrolDateNotice(state, picked) ?: ui.notice,
                        ),
                    )
                },
                onClose = { onUi(ui.copy(datePickerOpen = false)) },
            )
        }

        when (ui.sheet) {
            null -> Unit
            Sheet.Settings -> SettingsSheet(state, ui, onUi, actions, width, height)
            Sheet.ConfirmReanalyze -> ConfirmReanalyzeSheet(state, ui, onUi, actions)
            is Sheet.Photos -> PhotosSheet(state, ui, onUi, actions, width, height)
            is Sheet.Assign -> AssignSheet(state, ui, onUi, actions, width, height)
        }
    }
}

@Composable
private fun TopBar(state: AppState, ui: UiState, onUi: (UiState) -> Unit, actions: Actions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PmSize.topBar)
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(PmColor.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("巡", style = PmType.label, color = PmColor.surface)
        }
        Spacer(Modifier.width(PmSpace.m))
        Text("巡查照片归档", style = PmType.subtitle, color = PmColor.ink)

        Spacer(Modifier.width(PmSpace.l))
        Box(Modifier.width(1.dp).height(18.dp).background(PmColor.line))
        Spacer(Modifier.width(PmSpace.l))

        Spacer(Modifier.width(PmSpace.m))
        StatusPill(state.status, ui.busy)

        Text(
            "本机处理 · ${algorithm.AppZone.ID}",
            style = PmType.caption,
            color = PmColor.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )

        // 顶栏只留"导出 / 设置"两个全局动作：分析动作紧贴在日志输入框旁边，
        // 两处都放会让人以为要做两件事，且禁用态在白色顶栏上看起来只是一行灰字。
        //
        // ★ 导出按钮**始终可点**（只在忙的时候禁用）：
        //   以前是 `enabled = state.canExport`，最常见的"没设导出目录"会让它整块变灰，
        //   用户看到的是一个点不动的主按钮 —— 反馈里就变成了"界面上没有导出入口"。
        //   现在点它会得到一句明确的"该先做什么"，而不是什么都不发生。
        PmButton(
            "导出",
            {
                val reason = exportBlockReason(state, ui.busy)
                if (reason == null) {
                    actions.export()
                } else {
                    onUi(ui.copy(notice = LocalNotice(reason, NoticeTone.WARN)))
                }
            },
            kind = ButtonKind.PRIMARY,
            icon = Icons.Filled.FileDownload,
            enabled = ui.busy == null,
        )
        Spacer(Modifier.width(PmSpace.s))
        PmButton(
            "设置",
            { onUi(ui.copy(sheet = Sheet.Settings)) },
            kind = ButtonKind.SECONDARY,
            icon = Icons.Filled.Settings,
        )
    }
}

@Composable
private fun StatusPill(status: CoreStatus, busy: Busy?) {
    val (label, tone) = when {
        busy == Busy.ANALYZE -> "分析中" to Tone.ACCENT
        busy == Busy.EXPORT -> "导出中" to Tone.ACCENT
        status == CoreStatus.IDLE -> "待输入" to Tone.NEUTRAL
        status == CoreStatus.READY -> "可复核" to Tone.OK
        status == CoreStatus.FAILED -> "上次失败" to Tone.DANGER
        status == CoreStatus.CLOSED -> "已关闭" to Tone.NEUTRAL
        else -> "处理中" to Tone.ACCENT
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(PmRadius.pill))
            .background(tone.bg())
            .padding(horizontal = PmSpace.s, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(PmRadius.pill)).background(tone.fg()))
        Spacer(Modifier.width(PmSpace.xs))
        Text(label, style = PmType.micro, color = tone.fg())
    }
}

@Composable
private fun MessageArea(state: AppState, ui: UiState, onUi: (UiState) -> Unit, actions: Actions) {
    val error = state.error
    if (error != null && ui.dismissedError != error) {
        InlineNotice(
            tone = Tone.DANGER,
            text = error,
            onDismiss = { onUi(ui.copy(dismissedError = error)) },
        )
    }
    val message = state.message
    if (message != null) {
        InlineNotice(
            tone = if (message.contains("失败") || message.contains("未找到")) Tone.WARN else Tone.INFO,
            text = message,
            onDismiss = actions.clearMessage,
        )
    }
    val notice = ui.notice
    if (notice != null) {
        InlineNotice(
            // 语气由数据决定（model.Feedback 已经选好了 OK / WARN / DANGER）：
            // 界面不该再猜一次，否则同一个动作的结果在两个地方会有两种颜色。
            tone = when (notice.tone) {
                NoticeTone.INFO -> Tone.INFO
                NoticeTone.OK -> Tone.OK
                NoticeTone.WARN -> Tone.WARN
                NoticeTone.DANGER -> Tone.DANGER
            },
            text = notice.text,
            onDismiss = { onUi(ui.copy(notice = null)) },
        )
    }
}

@Composable
private fun StatusBar(state: AppState, ui: UiState, onUi: (UiState) -> Unit) {
    val stats = WorkQueue.stats(state)
    val result = state.lastExport
    Row(
        Modifier
            .fillMaxWidth()
            .height(PmSize.statusBar)
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            statusText(state.status, ui.busy),
            style = PmType.caption,
            color = when (state.status) {
                CoreStatus.FAILED -> PmColor.danger
                CoreStatus.READY -> PmColor.ok
                else -> PmColor.ink2
            },
        )
        Spacer(Modifier.width(PmSpace.m))
        Text(
            "事件 ${stats.events} · 照片 ${stats.photos} · 已归属 ${stats.matched} · 未匹配 ${stats.unmatched} · 待确认 ${stats.needReview}",
            style = PmType.caption,
            color = PmColor.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (result != null) {
            Spacer(Modifier.width(PmSpace.m))
            Text(
                "上次导出 ${result.copied}/${result.totalTargets}",
                style = PmType.caption,
                color = PmColor.ink3,
                modifier = Modifier.clickable(indication = null, interactionSource = null) {
                    // 重看结果条：把"收起过"的记录清掉即可，不重新导出。
                    onUi(ui.copy(dismissedResultKey = null, seenExportKey = state.exportKey))
                },
            )
        }
        Text(
            "F5 分析 · Ctrl+O 导入日志 · Ctrl+E 导出 · Ctrl+, 设置 · Esc 关闭",
            style = PmType.caption,
            color = PmColor.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun statusText(status: CoreStatus, busy: Busy?): String = when {
    busy == Busy.ANALYZE -> "正在分析…"
    busy == Busy.EXPORT -> "正在导出…"
    status == CoreStatus.IDLE -> "未开始"
    status == CoreStatus.READY -> "就绪"
    status == CoreStatus.FAILED -> "失败"
    status == CoreStatus.CLOSED -> "已关闭"
    else -> "处理中…"
}
