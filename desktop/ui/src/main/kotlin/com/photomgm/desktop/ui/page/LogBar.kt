// ui/page/LogBar.kt —— 页面顶部的输入条：巡查日期 + 巡查日志
//
// ★ 交互依据（用户明确要求）：日志框只承担"把文本送进来"，不承担"阅读和编辑"。
//   所以它只有两种形态，且默认短：
//     ① 展开 —— 一块 72 dp 高的粘贴区 + 一个主按钮「开始分析」；
//     ② 收起 —— 一行摘要（解析出几个事件、几张照片），右侧「重新分析」「重新输入」。
//   解析成功后自动切到 ②（见 model/UiState.reduce ①），把纵向空间让给事件卡片。
//   要看/改日志原文时点「重新输入」，原文还在（core.logText 持久化，不会丢）。
//
// ★ 巡查日期为什么在这里、且**两种形态下都在**：
//   日期和日志是 analyze() 的两个输入（日期决定扫哪些文件名、也决定"只有月日"的日志补哪一年），
//   所以它跟日志同处一条输入带，而不是进设置弹窗 —— 改日期 → 重新分析，是一条不断开的动作链，
//   中间隔一次"打开弹窗 / 翻到某一节 / 关弹窗"就断了。
//   日志可以收，日期不能收：日期是项目主键，它必须随时看得见、随时改得到。
//   代价是一条日期列常驻 ~70 dp，换来"我在处理哪一天"永远不用猜。
//
// ★ 主按钮为什么只有一个：core 的 analyze() 是一条原子流水线
//   （解析日志 → 扫目录 → 粗筛 → 派生死重 → 读 EXIF → GPS 分组 → 分类），
//   界面上拆成"解析""匹配"两个按钮，用户必然两边都点一遍。
package com.photomgm.desktop.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmDateField
import com.photomgm.desktop.ui.component.PmTextArea
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.format.dateText
import com.photomgm.desktop.ui.model.Sheet
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

/**
 * 日期列的固定宽：日期字段 196 + 前后一天 28×2 + 今天 48 + 三个 4 dp 间隙。
 *
 * ★ 写死而不是自适应：这一列的宽度决定了右侧日志框的左边缘，宽度一变，输入的起点就跳；
 *   而且两种形态（输入态 / 摘要态）必须同宽，否则切换时整条带会重新排版一次。
 */
private val DATE_COL_W = 312.dp

@Composable
fun LogBar(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.xl, vertical = PmSpace.l),
        verticalAlignment = Alignment.Top,
    ) {
        DateSection(state, ui, onUi, actions)
        Spacer(Modifier.width(PmSpace.xl))
        if (ui.logEntryVisible) {
            LogEntry(state, ui, onUi, actions, Modifier.weight(1f))
        } else {
            LogSummary(state, ui, onUi, Modifier.weight(1f))
        }
    }
}

/**
 * 巡查日期（日期字段 + 前后一天 + 今天）。
 *
 * 日历面板就地展开在这一列下方（不是浮层）：输入带本身就是页面第一段，
 * 展开时把下面的日志/列表往下推，行为可预测，也不需要在页面里做第二套覆盖层。
 */
@Composable
private fun DateSection(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
) {
    Column(Modifier.width(DATE_COL_W)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("巡查日期", style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.weight(1f))
            Text("只处理这天的照片", style = PmType.caption, color = PmColor.ink3)
        }
        Spacer(Modifier.height(PmSpace.xs))
        PmDateField(
            date = state.settings.patrolDate,
            onChange = { picked ->
                actions.setPatrolDate(picked)
                // 日期改了但结果还是旧的：用一条可关闭的提示说清楚"下一步点哪儿"，
                // 而不是把联系人拦在确认弹窗前面（改日期只是输入，不销毁任何东西）。
                if (state.events.isNotEmpty()) {
                    onUi(
                        ui.copy(
                            localMessage = "巡查日期已改为 ${dateText(picked)}：" +
                                "点「重新分析」按新日期重扫照片，已有的事件与人工调整会重算。",
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun LogEntry(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
    modifier: Modifier = Modifier,
) {
    // 打字时不回写 draft（否则光标会跳）；只有"外部导入"才通过 logRevision 强制同步。
    var draft by remember { mutableStateOf(state.logText) }
    LaunchedEffect(ui.logRevision) { draft = state.logText }

    val lines = remember(draft) { if (draft.isBlank()) 0 else draft.count { it == '\n' } + 1 }
    val canAnalyze = draft.isNotBlank() || state.settings.sourceDirs.isNotEmpty()

    Row(modifier, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("巡查日志", style = PmType.label, color = PmColor.ink2)
                Spacer(Modifier.width(PmSpace.m))
                Text(
                    if (lines == 0) "粘贴日志原文，或导入 .txt（UTF-8 / GBK）" else "$lines 行",
                    style = PmType.caption,
                    color = PmColor.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                PmButton("导入 .txt", actions.importLogFile, kind = ButtonKind.GHOST, icon = Icons.Filled.UploadFile)
                Spacer(Modifier.width(PmSpace.xs))
                PmButton(
                    "清空",
                    {
                        draft = ""
                        actions.setLogText("")
                    },
                    kind = ButtonKind.GHOST,
                    enabled = draft.isNotEmpty(),
                )
            }
            Spacer(Modifier.height(PmSpace.xs))
            PmTextArea(
                value = draft,
                onValueChange = {
                    draft = it
                    actions.setLogText(it)
                },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                placeholder = "在此粘贴巡查日志（整段粘贴即可，不用整理格式）",
            )
        }

        Spacer(Modifier.width(PmSpace.l))

        // 按钮与日志框**底边**对齐：日期列比日志列矮，若整行按顶部对齐，
        // 「开始分析」会浮到标题行的高度上，看起来像在给"巡查日志"这四个字做按钮。
        Column(Modifier.align(Alignment.Bottom), horizontalAlignment = Alignment.End) {
            PmButton(
                text = if (state.events.isEmpty()) "开始分析" else "重新分析",
                onClick = {
                    // 重新分析会丢弃人工调整（core 没有 undo），有结果时必须先确认。
                    if (state.events.isNotEmpty()) onUi(ui.copy(sheet = Sheet.ConfirmReanalyze))
                    else actions.analyze()
                },
                enabled = canAnalyze,
                height = 38.dp,
                icon = Icons.Filled.PlayArrow,
            )
            Spacer(Modifier.height(PmSpace.xs))
            Text("解析日志 + 匹配照片", style = PmType.caption, color = PmColor.ink3)
            if (!canAnalyze) {
                Spacer(Modifier.height(2.dp))
                Text("先粘贴日志或选择源目录", style = PmType.caption, color = PmColor.warn)
            }
        }
    }
}

@Composable
private fun LogSummary(state: AppState, ui: UiState, onUi: (UiState) -> Unit, modifier: Modifier = Modifier) {
    val parsed = state.parsed
    val events = state.events.size
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = PmColor.ok, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(PmSpace.s))
        Text("日志已解析", style = PmType.label, color = PmColor.ink)
        Spacer(Modifier.width(PmSpace.m))
        Text(
            buildString {
                append("$events 个事件")
                append(" · ${state.photos.size} 张照片")
                if (state.unmatchedPhotoIds.isNotEmpty()) append(" · ${state.unmatchedPhotoIds.size} 张未匹配")
                parsed?.inspectors?.let { append(" · 巡查人 $it") }
                parsed?.vehicle?.let { append(" · 车辆 $it") }
            },
            style = PmType.caption,
            color = PmColor.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (parsed != null && parsed.skippedLineCount > 0) {
            Tag("跳过 ${parsed.skippedLineCount} 行", Tone.WARN)
            Spacer(Modifier.width(PmSpace.s))
        }
        if (state.status == CoreStatus.READY && events == 0) {
            Tag("0 个事件，检查日志与日期", Tone.DANGER)
            Spacer(Modifier.width(PmSpace.s))
        }
        if (events > 0) {
            PmButton(
                "重新分析",
                { onUi(ui.copy(sheet = Sheet.ConfirmReanalyze)) },
                kind = ButtonKind.GHOST,
            )
            Spacer(Modifier.width(PmSpace.xs))
            PmButton(
                "重新输入",
                { onUi(ui.copy(logEntryVisible = true)) },
                kind = ButtonKind.GHOST,
                icon = Icons.Filled.Edit,
            )
        }
    }
}

/** 日志解析出来的提醒（跳过的行、日期不符等）：直接显示在日志摘要下方，不藏进设置。 */
@Composable
fun LogNotices(state: AppState) {
    val notices = state.parsed?.notices ?: return
    if (notices.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PmSpace.xl, vertical = PmSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(PmSpace.s),
    ) {
        notices.take(2).forEach { notice ->
            Text(
                "· $notice",
                style = PmType.caption,
                color = PmColor.warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
