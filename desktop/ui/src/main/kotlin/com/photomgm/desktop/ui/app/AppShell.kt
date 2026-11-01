// ui/app/AppShell.kt —— 窗口骨架：步骤栏 / 顶栏 / 内容区 / 状态栏
//
// ★ 全窗口只有四个固定区域，且每个区域回答一个固定问题：
//     顶栏  —— 我在处理哪一天？（作业身份）
//     步骤栏 —— 我在整条流程的哪一步？（可跳回，但不可跳到"还没结果"的步骤）
//     内容区 —— 当前步骤的全部工作（由 CoreStatus 投影，见 model/Stage.kt）
//     状态栏 —— 现在能不能操作、数据是什么口径？（永不遮挡内容）
//
// ★ 为什么步骤栏是"固定 64 dp 图标条"而不是"可折叠的 300 dp 左栏"：
//   上一版把源目录/日志/设置放进 300 dp 左栏常驻，可这三样只在第一步用得上；
//   到了复核阶段它占着 1/3 的宽度不干活，逼得照片区只剩 2 列。
//   把"导航"压缩到 64 dp，把宽度还给照片——这是 984 dp 工作区下的必然选择。
package com.photomgm.desktop.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.InlineNotice
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.bg
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.format.dateText
import com.photomgm.desktop.ui.model.Busy
import com.photomgm.desktop.ui.model.Stage
import com.photomgm.desktop.ui.model.Step
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.WorkQueue
import com.photomgm.desktop.ui.model.exportLayout
import com.photomgm.desktop.ui.model.inputLayout
import com.photomgm.desktop.ui.model.railWidth
import com.photomgm.desktop.ui.model.reviewLayout
import com.photomgm.desktop.ui.model.step
import com.photomgm.desktop.ui.stage.BusyOverlay
import com.photomgm.desktop.ui.stage.ExportStage
import com.photomgm.desktop.ui.stage.InputStage
import com.photomgm.desktop.ui.stage.ResultStage
import com.photomgm.desktop.ui.stage.ReviewStage
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
                        actions.analyze(); true
                    }

                    event.isCtrlPressed && event.key == Key.O -> {
                        actions.importLogFile(); true
                    }

                    event.isCtrlPressed && event.key == Key.E -> {
                        if (state.events.isNotEmpty()) actions.goStage(Stage.EXPORT); true
                    }

                    event.key == Key.Escape -> {
                        onUi(ui.copy(selectedPhotoIds = emptySet(), inspectorOpen = false, confirmReset = false)); true
                    }

                    else -> false
                }
            },
    ) {
        val width = maxWidth.value.toInt()

        // 渲染日志：把"这个宽度下排成了什么样"写成可 diff 的一行。
        // DPI 三档走查（M6b）与回归对比都靠它，不靠人肉截图对眼。
        LaunchedEffect(width, ui.stage, ui.busy) {
            val extra = when {
                ui.busy != null -> " stage=busy:${ui.busy!!.name}"
                ui.stage == Stage.REVIEW -> reviewLayout(width).let {
                    " stage=REVIEW rail=${it.railW} list=${it.listW} grid=${it.gridW} cols=${it.gridCols} " +
                        "inspector=${if (it.inspectorAsDrawer) "drawer" else it.inspectorW.toString()}"
                }

                ui.stage == Stage.INPUT -> inputLayout(width).let { " stage=INPUT rail=${it.railW} twoPane=${it.twoPane}" }
                ui.stage == Stage.EXPORT -> exportLayout(width).let { " stage=EXPORT rail=${it.railW} side=${it.sideW} table=${it.tableW}" }
                else -> " stage=RESULT"
            }
            UiLog.line("render w=$width$extra")
            val s = state
            UiLog.line(
                "state status=${s.status} events=${s.events.size} photos=${s.photos.size} " +
                    "unmatched=${s.unmatchedPhotoIds.size} ledger=${s.ledger.size} " +
                    "parsed=${s.parsed?.eventCount ?: 0} selected=${ui.selection}",
            )
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(state, ui, actions)
            Hairline()
            MessageArea(state, ui, onUi, actions)

            Row(Modifier.fillMaxWidth().weight(1f)) {
                StepRail(state, ui, actions, width)
                Hairline(vertical = true)

                Box(Modifier.weight(1f).fillMaxSize()) {
                    val busy = ui.busy
                    if (busy != null) {
                        BusyOverlay(
                            busy = busy,
                            state = state,
                            onCancel = if (busy == Busy.ANALYZE) actions.cancelAnalyze else actions.cancelExport,
                        )
                    } else {
                        when (ui.stage) {
                            Stage.INPUT -> InputStage(state, ui, width, actions)
                            Stage.REVIEW -> ReviewStage(state, ui, width, actions, onUi)
                            Stage.EXPORT -> ExportStage(state, ui, width, actions, onUi)
                            Stage.RESULT -> ResultStage(state, width, actions)
                        }
                    }
                }
            }

            Hairline()
            StatusBar(state, ui)
        }
    }
}

@Composable
private fun TopBar(state: AppState, ui: UiState, actions: Actions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PmSize.topBar)
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.l),
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
        Text("巡查照片分类", style = PmType.subtitle, color = PmColor.ink)
        Spacer(Modifier.width(PmSpace.l))
        Box(Modifier.width(1.dp).height(18.dp).background(PmColor.line))

        Spacer(Modifier.width(PmSpace.l))
        Row(
            Modifier
                .clip(RoundedCornerShape(PmRadius.control))
                .clickable(indication = null, interactionSource = null) { actions.goStage(Stage.INPUT) }
                .padding(horizontal = PmSpace.s, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("巡查日期", style = PmType.caption, color = PmColor.ink3)
            Spacer(Modifier.width(PmSpace.xs))
            Text(dateText(state.settings.patrolDate), style = PmType.numeric, color = PmColor.ink)
        }

        Spacer(Modifier.width(PmSpace.m))
        StatusPill(state.status, ui.busy)

        Text(
            "全部数据只在本机处理 · ${algorithm.AppZone.ID}",
            style = PmType.caption,
            color = PmColor.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
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
            action = {
                Text(
                    "可复制给工程师排障",
                    style = PmType.caption,
                    color = PmColor.ink3,
                    modifier = Modifier.padding(horizontal = PmSpace.m),
                )
            },
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
}

@Composable
private fun StepRail(state: AppState, ui: UiState, actions: Actions, windowWidth: Int) {
    val railW = railWidth(windowWidth)
    val stats = WorkQueue.stats(state)
    val current = ui.busy?.step ?: ui.stage.step

    Column(
        Modifier
            .width(railW.dp)
            .fillMaxSize()
            .background(PmColor.surface)
            .padding(vertical = PmSpace.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Step.values().forEach { step ->
            val enabled = step == Step.INPUT || state.events.isNotEmpty()
            RailItem(
                step = step,
                selected = step == current,
                enabled = enabled,
                badge = when (step) {
                    Step.REVIEW -> stats.needReview.takeIf { it > 0 }
                    Step.EXPORT -> state.unmatchedPhotoIds.size.takeIf { it > 0 }
                    else -> null
                },
                onClick = { if (enabled) actions.goStage(stageOf(step)) },
            )
            Spacer(Modifier.height(PmSpace.m))
        }
    }
}

private fun stageOf(step: Step): Stage = when (step) {
    Step.INPUT -> Stage.INPUT
    Step.REVIEW -> Stage.REVIEW
    Step.EXPORT -> Stage.EXPORT
}

@Composable
private fun RailItem(
    step: Step,
    selected: Boolean,
    enabled: Boolean,
    badge: Int?,
    onClick: () -> Unit,
) {
    val icon: ImageVector = when (step) {
        Step.INPUT -> Icons.Filled.Edit
        Step.REVIEW -> Icons.Filled.FactCheck
        Step.EXPORT -> Icons.Filled.FileDownload
    }
    val fg = when {
        !enabled -> PmColor.ink4
        selected -> PmColor.accent
        else -> PmColor.ink2
    }

    Column(
        Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(PmRadius.card))
            .background(if (selected) PmColor.accentSoft else PmColor.surface)
            .clickable(enabled = enabled, indication = null, interactionSource = null, onClick = onClick)
            .padding(vertical = PmSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(icon, contentDescription = step.label, tint = fg, modifier = Modifier.size(18.dp))
            if (badge != null && enabled) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(PmRadius.pill))
                        .background(PmColor.warn)
                        .padding(horizontal = 3.dp),
                ) {
                    Text("$badge", style = PmType.micro, color = PmColor.surface)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(step.label, style = PmType.caption, color = fg, maxLines = 1)
    }
}

@Composable
private fun StatusBar(state: AppState, ui: UiState) {
    val stats = WorkQueue.stats(state)
    Row(
        Modifier
            .fillMaxWidth()
            .height(PmSize.statusBar)
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.l),
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
        Text(
            "F5 分析 · Ctrl+O 导入日志 · Ctrl+E 导出 · Esc 取消选择",
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
