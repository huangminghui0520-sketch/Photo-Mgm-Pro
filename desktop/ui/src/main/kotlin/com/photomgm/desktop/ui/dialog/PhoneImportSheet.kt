// ui/dialog/PhoneImportSheet.kt —— 手机导入照片弹窗
//
// ★ 顶部是**唯一执行入口**（裁定 F2）：「从手机同步到电脑源目录」按钮画在 header 里、
//   滚动区之外，内容再长也始终可见；底部操作条只留「取消」，不出现第二个开始按钮。
// ★ 只读「同步到」一行随区间实时更新，让用户点之前就看见落点；规则本体在 shared/domain，
//   经 DesktopCore 门面转发（F6），UI 不 import domain.*。
// ★ 弹窗内进度条：不改 Busy / BusyOverlay，也不碰 AppShell 的忙碌覆盖层。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.api.AppState
import com.photomgm.desktop.core.PhoneDirEntry
import com.photomgm.desktop.core.PhoneLinkMode
import com.photomgm.desktop.core.PhoneSyncProgress
import com.photomgm.desktop.core.PhoneSyncRequest
import com.photomgm.desktop.core.PhoneSyncResult
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.DateTimeField
import com.photomgm.desktop.ui.component.Field
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmScrollArea
import com.photomgm.desktop.ui.component.Segmented
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.bg
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.model.PhoneImportForm
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.conflictPrompt
import com.photomgm.desktop.ui.model.defaultPhoneImportForm
import com.photomgm.desktop.ui.model.dialogHeight
import com.photomgm.desktop.ui.model.phoneImportRange
import com.photomgm.desktop.ui.model.syncBlockReason
import com.photomgm.desktop.ui.model.syncTargetText
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

@Composable
fun PhoneImportSheet(
    state: AppState,
    ui: UiState,
    onUi: (UiState) -> Unit,
    actions: Actions,
    windowWidth: Int,
    windowHeight: Int,
) {
    val width = minOf(600, windowWidth - 48).coerceAtLeast(420)
    val maxH = dialogHeight(windowHeight)

    // 只在弹窗首次进入时生成默认区间（巡查日期 00:00 ~ 23:59），用户改后不重置。
    val patrolDate = state.settings.patrolDate
    var form by remember {
        mutableStateOf(
            defaultPhoneImportForm(patrolDate, state.settings.sourceDirs, actions.phoneDefaultRange(patrolDate)),
        )
    }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PhoneSyncProgress?>(null) }
    var result by remember { mutableStateOf<PhoneSyncResult?>(null) }
    var dirsExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var manualDirs by remember { mutableStateOf(listOf<PhoneDirEntry>()) }

    val mode = PhoneLinkMode.values()[form.modeIndex.coerceIn(0, PhoneLinkMode.values().size - 1)]
    val phoneOptions = remember(mode, manualDirs) { actions.phoneDirs(mode) + manualDirs }

    // R5 冲突后用户点「仍要继续」：把确认标记带上再跑一次，绝不静默合并。
    fun launchSync(current: PhoneImportForm) {
        val reason = syncBlockReason(current, state.settings.sourceDirs, running, actions.phoneValidateRange)
        if (reason != null) {
            result = PhoneSyncResult.failure(reason)
            return
        }
        val range = phoneImportRange(current) ?: return
        running = true
        result = null
        progress = null
        actions.startPhoneSync(
            PhoneSyncRequest(
                sourceDir = current.sourceDir,
                start = range.first,
                end = range.second,
                phoneDir = current.phoneDir,
                mode = mode,
                confirmConflict = current.confirmConflict,
            ),
            { p -> progress = p },
            { r -> running = false; result = r; progress = null },
        )
    }

    SheetHost(onDismiss = { onUi(ui.copy(sheet = null)) }) { dragHandle ->
        Column(
            Modifier
                .width(width.dp)
                .height(maxH.dp)
                .clip(RoundedCornerShape(PmRadius.panel))
                .background(PmColor.surface),
        ) {
            // ── 顶部常驻：唯一执行入口 ──────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(dragHandle)
                    .padding(start = PmSpace.xl, end = PmSpace.m, top = PmSpace.l, bottom = PmSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("手机导入照片", style = PmType.display, color = PmColor.ink)
                Spacer(Modifier.width(PmSpace.l))
                PmButton(
                    "从手机同步到电脑源目录",
                    { launchSync(form) },
                    kind = ButtonKind.PRIMARY,
                    icon = Icons.Filled.FolderOpen,
                    enabled = !running,
                )
                Spacer(Modifier.weight(1f))
                PmIconButton(Icons.Filled.Close, "关闭", { onUi(ui.copy(sheet = null)) })
            }
            Hairline()

            PmScrollArea(
                Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(start = PmSpace.xl, end = PmSpace.xl, top = PmSpace.l, bottom = PmSpace.m),
            ) {
                Field(label = "照片时间区间", hint = "可编辑、可跨自然日；精确到分钟，秒不参与文件夹命名。") {
                    Row(verticalAlignment = Alignment.Top) {
                        DateTimeField(
                            label = "开始",
                            date = form.startDate,
                            time = form.startTime,
                            onDateChange = { form = form.copy(startDate = it, confirmConflict = false) },
                            onTimeChange = { form = form.copy(startTime = it, confirmConflict = false) },
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(PmSpace.l))
                        DateTimeField(
                            label = "结束",
                            date = form.endDate,
                            time = form.endTime,
                            onDateChange = { form = form.copy(endDate = it, confirmConflict = false) },
                            onTimeChange = { form = form.copy(endTime = it, confirmConflict = false) },
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(PmSpace.m))

                Field(
                    label = "同步到哪个源目录（${state.settings.sourceDirs.size}）",
                    hint = "照片会写进该源目录下按区间命名的子文件夹；源目录在右上角「设置」里添加。",
                ) {
                    SelectBlock(
                        value = form.sourceDir,
                        expanded = sourceExpanded,
                        onToggle = { sourceExpanded = !sourceExpanded },
                        options = state.settings.sourceDirs.map { it to it },
                        onSelect = { form = form.copy(sourceDir = it, confirmConflict = false); sourceExpanded = false },
                        emptyText = "尚未设置电脑源目录：关闭弹窗后到「设置 → 照片源目录」添加。",
                    )
                }

                Spacer(Modifier.height(PmSpace.m))

                Field(
                    label = "手机目录",
                    trailing = {
                        PmButton(
                            "手动选择本机目录",
                            {
                                actions.pickPhoneDir()?.let { picked ->
                                    val entry = PhoneDirEntry(picked, "本机目录 · $picked")
                                    if (manualDirs.none { it.id == picked }) manualDirs = manualDirs + entry
                                    form = form.copy(phoneDir = picked, confirmConflict = false)
                                }
                            },
                            kind = ButtonKind.SECONDARY,
                            enabled = !running,
                        )
                    },
                    hint = "原生 MTP 设备不分配盘符，暂不支持：请把 USB 切到「传输文件」并以盘符出现，或手动选择本机已挂载的目录。ADB 方式需开启 USB 调试并点「允许」。",
                ) {
                    SelectBlock(
                        value = form.phoneDir,
                        expanded = dirsExpanded,
                        onToggle = { dirsExpanded = !dirsExpanded },
                        options = phoneOptions.map { it.id to it.label },
                        onSelect = { form = form.copy(phoneDir = it, confirmConflict = false); dirsExpanded = false },
                        emptyText = "未检测到手机目录：可手动选择本机目录，或检查 USB 连接 / USB 调试授权。",
                    )
                }

                Spacer(Modifier.height(PmSpace.m))

                Field(label = "连接方式", hint = "自动 = 先 ADB 后 USB 盘符；一次同步只走一种方式，不混用。ADB 不可用时自动降级，不报致命错。") {
                    Segmented(
                        options = listOf("自动" to "", "ADB" to "", "USB 盘符" to ""),
                        selectedIndex = form.modeIndex,
                        onSelect = { form = form.copy(modeIndex = it, phoneDir = "", confirmConflict = false) },
                    )
                }

                Spacer(Modifier.height(PmSpace.m))

                // 只读落点：随区间 / 源目录实时更新，不参与编辑。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PmRadius.control))
                        .background(PmColor.surface2)
                        .padding(PmSpace.m),
                ) {
                    Text(
                        syncTargetText(form, actions.phoneFolderNameFor),
                        style = PmType.body,
                        color = PmColor.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val p = progress
                if (p != null) {
                    Spacer(Modifier.height(PmSpace.m))
                    Text(
                        "当前方式：${p.connectionLabel} · 已处理 ${p.done}/${p.total} · 写入 ${p.written} · 跳过 ${p.skipped} · ${p.current}",
                        style = PmType.caption,
                        color = PmColor.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(PmSpace.xs))
                    val fraction = if (p.total <= 0) 0f else (p.done.toFloat() / p.total).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(PmRadius.pill))
                            .background(PmColor.surface2),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .clip(RoundedCornerShape(PmRadius.pill))
                                .background(PmColor.accent),
                        )
                    }
                }

                val r = result
                if (r != null) {
                    Spacer(Modifier.height(PmSpace.m))
                    val tone = when {
                        r.error != null -> Tone.DANGER
                        r.conflict != null -> Tone.WARN
                        r.cancelled -> Tone.WARN
                        else -> Tone.OK
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PmRadius.control))
                            .background(tone.bg())
                            .padding(PmSpace.m),
                    ) {
                        Text(r.summary, style = PmType.body, color = tone.fg())
                    }
                    val prompt = conflictPrompt(r.conflict)
                    if (prompt != null) {
                        Spacer(Modifier.height(PmSpace.xs))
                        Text(prompt, style = PmType.caption, color = PmColor.ink2)
                        Spacer(Modifier.height(PmSpace.s))
                        PmButton(
                            "仍要继续",
                            {
                                val confirmed = form.copy(confirmConflict = true)
                                form = confirmed
                                launchSync(confirmed)
                            },
                            kind = ButtonKind.SECONDARY,
                            enabled = !running,
                        )
                    }
                }
            }

            Hairline()
            // 底部操作条：只有「取消」。同步进行中点它同时取消任务，已写入文件与状态都保留。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PmSpace.xl, vertical = PmSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (running) "正在同步…" else "断点续传：跳过已完成、重写半截、冲突不覆盖",
                    style = PmType.caption,
                    color = PmColor.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PmButton(
                    "取消",
                    {
                        if (running) actions.cancelPhoneSync()
                        onUi(ui.copy(sheet = null))
                    },
                    kind = ButtonKind.GHOST,
                )
            }
        }
    }
}

// 就地展开的选择列表（不开独立 Popup）。
// 为什么不用 DropdownMenu：Compose Desktop 的 Popup 是独立窗口，与 SheetHost 当初弃用
// Dialog 的理由一样 —— 125% 缩放下会按另一套 DPI 渲染。展开成滚动区内的一段列表，
// 尺寸、焦点、截图都留在同一个窗口里。
@Composable
private fun SelectBlock(
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    emptyText: String,
) {
    Column(Modifier.fillMaxWidth()) {
        PmButton(
            text = value.ifBlank { "请选择" },
            onClick = onToggle,
            kind = ButtonKind.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            Spacer(Modifier.height(PmSpace.xs))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PmRadius.control))
                    .background(PmColor.surface2)
                    .padding(PmSpace.xs),
            ) {
                if (options.isEmpty()) {
                    Text(emptyText, style = PmType.caption, color = PmColor.warn, modifier = Modifier.padding(PmSpace.s))
                } else {
                    options.forEach { (id, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 30.dp)
                                .clip(RoundedCornerShape(PmRadius.control))
                                .background(if (id == value) PmColor.accentSoft else PmColor.surface2)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelect(id) }
                                .padding(horizontal = PmSpace.s, vertical = PmSpace.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = PmType.caption,
                                color = if (id == value) PmColor.accent else PmColor.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
