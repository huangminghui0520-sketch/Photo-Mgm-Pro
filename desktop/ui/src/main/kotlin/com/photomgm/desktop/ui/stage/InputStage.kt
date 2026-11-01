// ui/stage/InputStage.kt —— 步骤 1：输入
//
// 结构依据（逐条对应 core 的真实行为，不是排版偏好）：
//   · 巡查日期 + 源目录 = `analyze()` 的输入参数，改了就**必须重跑**（updateSettings 会把
//     信息置为"请重新分析"）。所以它们和日志放在同一页、同一个主动作之下。
//   · 日志文本是可以边打字边落盘的（setLogText 自带 700ms 防抖落盘），不需要"保存"按钮。
//   · 输出目录不参与分析，只参与导出（canExport 要求非空）。放这里只是因为"开工前一次配齐"，
//     但它必须视觉上弱于前两项——它不影响分析结果。
//   · 分析是**一次原子动作**：解析 → 扫描 → 去重 → EXIF → 分类。界面上只有一个按钮。
//     旧实现把"解析"和"分类"分成两个按钮，与 core 的实际接口不对应，会让用户反复点。
package com.photomgm.desktop.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.Field
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.Panel
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmTextArea
import com.photomgm.desktop.ui.component.PmTextField
import com.photomgm.desktop.ui.component.ReadonlyRow
import com.photomgm.desktop.ui.component.Tag
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.format.dateText
import com.photomgm.desktop.ui.format.nowDate
import com.photomgm.desktop.ui.format.parseDate
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.inputLayout
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun InputStage(
    state: AppState,
    ui: UiState,
    contentWidth: Int,
    actions: Actions,
) {
    val layout = inputLayout(contentWidth)

    Column(Modifier.fillMaxSize().padding(horizontal = PmSpace.xl, vertical = PmSpace.l)) {
        StageHeader(
            step = "①",
            title = "输入",
            subtitle = "设好日期与源目录，把当天巡查日志放进来，然后一次性完成分析。",
        )
        Spacer(Modifier.height(PmSpace.l))

        if (layout.twoPane) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.width(layout.materialsW.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    MaterialsPanel(state, actions)
                }
                Spacer(Modifier.width(PmSpace.l))
                LogPanel(state, ui, actions, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                MaterialsPanel(state, actions)
                Spacer(Modifier.height(PmSpace.l))
                LogPanel(state, ui, actions, Modifier.fillMaxWidth().height(340.dp))
            }
        }

        Spacer(Modifier.height(PmSpace.l))
        AnalyzeFooter(state, actions)
    }
}

@Composable
private fun StageHeader(step: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(step, style = PmType.display, color = PmColor.accent)
        Spacer(Modifier.width(PmSpace.m))
        Column {
            Text(title, style = PmType.display, color = PmColor.ink)
            Text(subtitle, style = PmType.caption, color = PmColor.ink3)
        }
    }
}

@Composable
private fun MaterialsPanel(state: AppState, actions: Actions) {
    Panel(title = "巡查参数", subtitle = "改动日期或源目录会要求重新分析") {
        Field(
            label = "巡查日期",
            hint = "只扫文件名包含这天的照片；日志里没有年份也按这天补全",
        ) {
            DateRow(state.settings.patrolDate, actions)
        }

        Spacer(Modifier.height(PmSpace.l))

        Field(
            label = "源目录（${state.settings.sourceDirs.size}）",
            hint = if (state.settings.sourceDirs.isEmpty()) "至少选一个装着当天照片的文件夹" else null,
            trailing = {
                PmButton("添加", actions.addSourceDirs, kind = ButtonKind.SECONDARY, icon = Icons.Filled.Add)
            },
        ) {
            if (state.settings.sourceDirs.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PmRadius.control))
                        .background(PmColor.surface2)
                        .padding(PmSpace.m),
                ) {
                    Text(
                        "尚未选择。分析仍可先跑日志解析，但不会扫到任何照片。",
                        style = PmType.caption,
                        color = PmColor.ink3,
                    )
                }
            } else {
                Column {
                    state.settings.sourceDirs.forEach { dir ->
                        SourceRow(dir, actions)
                        Spacer(Modifier.height(PmSpace.xs))
                    }
                }
            }
        }

        // ★ 导出目录不在这里：它不参与分析，只影响导出。
        //   放在输入页会占掉左栏的最后一行（实测把"导出目录"整个挤出可视区），
        //   而它真正被用到的地方是导出阶段（那里有"更换目录"与阻断提示）。
    }
}

@Composable
private fun DateRow(date: LocalDate, actions: Actions) {
    // 文本态与日期态分开：日记里"2026-13-01"这种中间输入不该被弹回，也不该被当成有效值。
    var draft by remember(date) { mutableStateOf(dateText(date)) }
    val weekday = date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.CHINA)

    // ★ 340 dp 的左栏放不下"前一天/今天/后一天"三个文字按钮（实测最后一个被裁掉）。
    //   改成两个箭头图标 + 一个"今天"：语义不变，宽度减半。
    Row(verticalAlignment = Alignment.CenterVertically) {
        PmTextField(
            value = draft,
            onValueChange = { raw ->
                draft = raw
                parseDate(raw)?.let { parsed -> if (parsed != date) actions.setPatrolDate(parsed) }
            },
            modifier = Modifier.width(126.dp),
            textStyle = PmType.numeric,
            placeholder = "yyyy-MM-dd",
        )
        Spacer(Modifier.width(PmSpace.xs))
        PmIconButton(
            Icons.Filled.ChevronLeft,
            "前一天",
            { actions.setPatrolDate(date.minusDays(1)) },
            size = 28.dp,
        )
        PmButton("今天", { actions.setPatrolDate(nowDate()) }, kind = ButtonKind.GHOST)
        PmIconButton(
            Icons.Filled.ChevronRight,
            "后一天",
            { actions.setPatrolDate(date.plusDays(1)) },
            size = 28.dp,
        )
        Spacer(Modifier.width(PmSpace.xs))
        Tag("周$weekday", Tone.NEUTRAL)
    }
}

@Composable
private fun SourceRow(dir: String, actions: Actions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.control))
            .background(PmColor.surface2)
            .padding(start = PmSpace.m, end = PmSpace.xs, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = PmColor.ink3,
            modifier = Modifier.width(14.dp).height(14.dp),
        )
        Spacer(Modifier.width(PmSpace.s))
        Text(
            shortPath(dir),
            style = PmType.caption,
            color = PmColor.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PmIconButton(Icons.Filled.OpenInNew, "在资源管理器中打开", { actions.revealPath(dir) }, size = 24.dp)
        PmIconButton(Icons.Filled.Close, "移出源目录", { actions.removeSourceDir(dir) }, size = 24.dp)
    }
}

@Composable
private fun LogPanel(state: AppState, ui: UiState, actions: Actions, modifier: Modifier) {
    // 打字时不回写 draft（否则光标会跳）；只有"外部导入"才通过 logRevision 强制同步。
    var draft by remember { mutableStateOf(state.logText) }
    LaunchedEffect(ui.logRevision) { draft = state.logText }

    val lines = remember(draft) { if (draft.isBlank()) 0 else draft.count { it == '\n' } + 1 }

    Panel(
        title = "巡查日志",
        subtitle = "粘贴原文，或从 .txt 导入（支持 UTF-8 / GBK）",
        modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$lines 行", style = PmType.numeric, color = PmColor.ink3)
                Spacer(Modifier.width(PmSpace.m))
                PmButton("导入 .txt", actions.importLogFile, kind = ButtonKind.SECONDARY, icon = Icons.Filled.UploadFile)
                Spacer(Modifier.width(PmSpace.xs))
                PmButton(
                    "清空",
                    { actions.setLogText("") },
                    kind = ButtonKind.GHOST,
                    enabled = draft.isNotEmpty(),
                )
            }
        },
    ) {
        PmTextArea(
            value = draft,
            onValueChange = {
                draft = it
                actions.setLogText(it)
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = "在此粘贴巡查日志……\n\n日志里的「时间 + 桩号 + 病害描述」会被解析成事件；\n日期以左侧「巡查日期」为准，用于补全只有月日的时间。",
        )

        val parsed = state.parsed
        if (parsed != null) {
            Spacer(Modifier.height(PmSpace.m))
            Hairline()
            Spacer(Modifier.height(PmSpace.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上次分析结果", style = PmType.label, color = PmColor.ink2)
                Spacer(Modifier.width(PmSpace.m))
                Tag("事件 ${parsed.eventCount}", if (parsed.eventCount > 0) Tone.OK else Tone.WARN)
                if (parsed.skippedLineCount > 0) {
                    Spacer(Modifier.width(PmSpace.xs))
                    Tag("跳过 ${parsed.skippedLineCount} 行", Tone.WARN)
                }
                Spacer(Modifier.weight(1f))
                if (parsed.inspectors != null) {
                    Text("巡查人 ${parsed.inspectors}", style = PmType.caption, color = PmColor.ink3, maxLines = 1)
                }
            }
            if (parsed.vehicle != null || parsed.recorder != null) {
                Spacer(Modifier.height(PmSpace.xs))
                Row {
                    if (parsed.vehicle != null) {
                        Text("车辆 ${parsed.vehicle}", style = PmType.caption, color = PmColor.ink3)
                        Spacer(Modifier.width(PmSpace.l))
                    }
                    if (parsed.recorder != null) {
                        Text("记录 ${parsed.recorder}", style = PmType.caption, color = PmColor.ink3)
                    }
                }
            }
            if (parsed.notices.isNotEmpty()) {
                Spacer(Modifier.height(PmSpace.xs))
                parsed.notices.take(2).forEach {
                    Text("· $it", style = PmType.caption, color = PmColor.warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.events.isEmpty() && state.status == com.photomgm.desktop.core.api.CoreStatus.READY) {
                Spacer(Modifier.height(PmSpace.xs))
                Text(
                    "解析出 0 个事件：检查日志是否为当天、日期是否选对。",
                    style = PmType.caption,
                    color = PmColor.danger,
                )
            }
        }
    }
}

@Composable
private fun AnalyzeFooter(state: AppState, actions: Actions) {
    val hasInput = state.logText.isNotBlank() || state.settings.sourceDirs.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.panel))
            .background(PmColor.surface)
            .padding(horizontal = PmSpace.l, vertical = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = PmColor.ink3, modifier = Modifier.width(14.dp).height(14.dp))
        Spacer(Modifier.width(PmSpace.s))
        Column(Modifier.weight(1f)) {
            Text(
                "分析 = 解析日志 → 扫描 ${state.settings.sourceDirs.size} 个源目录 → 粗筛 → 派生去重 → 读 EXIF → GPS 分组 → 分类，一次完成",
                style = PmType.caption,
                color = PmColor.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hasInput) {
                Text("先粘贴日志或选源目录，否则分析拿不到任何东西。", style = PmType.caption, color = PmColor.warn)
            }
        }
        Spacer(Modifier.width(PmSpace.l))
        KeyValueInline("日期", dateText(state.settings.patrolDate))
        Spacer(Modifier.width(PmSpace.l))
        PmButton(
            text = if (state.events.isEmpty()) "开始分析" else "重新分析",
            onClick = actions.analyze,
            kind = ButtonKind.PRIMARY,
            icon = Icons.Filled.PlayArrow,
            enabled = hasInput,
            height = 34.dp,
        )
    }
}

@Composable
private fun KeyValueInline(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = PmType.numeric, color = PmColor.ink)
        Text(label, style = PmType.caption, color = PmColor.ink3)
    }
}

/**
 * 路径取"尾部"而不是头部。
 *
 * ★ 340 dp 宽只能放 ~38 字符。截头（C:/Users/28988/WorkBuddy/Worktrees/Phot…）会把
 *   唯一有辨识度的末级目录名切掉；截尾（…/demo-data/demo/src-a）才让人一眼分清是哪个盘。
 */
private fun shortPath(path: String): String {
    if (path.length <= 34) return path
    val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.size <= 2) return path
    return "…/" + parts.takeLast(2).joinToString("/")
}
