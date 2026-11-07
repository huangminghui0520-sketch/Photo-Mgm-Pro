// ui/dialog/PhoneImportSheet.kt —— 手机导入照片弹窗
//
// ★ 顶部是**唯一执行入口**（裁定 F2）：「从手机同步到电脑源目录」按钮画在 header 里、
//   滚动区之外，内容再长也始终可见；底部操作条只留「取消」，不出现第二个开始按钮。
// ★ 只读「同步到」一行随区间实时更新，让用户点之前就看见落点；规则本体在 shared/domain，
//   经 DesktopCore 门面转发（F6），UI 不 import domain.*。
// ★ 弹窗内进度条：不改 Busy / BusyOverlay，也不碰 AppShell 的忙碌覆盖层。
package com.photomgm.desktop.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import app.api.AppState
import com.photomgm.desktop.core.PhoneDirEntry
import com.photomgm.desktop.core.PhoneLinkMode
import com.photomgm.desktop.core.PhoneSyncProgress
import com.photomgm.desktop.core.PhoneSyncRequest
import com.photomgm.desktop.core.PhoneSyncResult
import com.photomgm.desktop.ui.app.Actions
import com.photomgm.desktop.ui.component.ButtonKind
import com.photomgm.desktop.ui.component.CALENDAR_PANEL_W
import com.photomgm.desktop.ui.component.Field
import com.photomgm.desktop.ui.component.Hairline
import com.photomgm.desktop.ui.component.PmButton
import com.photomgm.desktop.ui.component.PmCalendarPanel
import com.photomgm.desktop.ui.component.PmDateField
import com.photomgm.desktop.ui.component.PmIconButton
import com.photomgm.desktop.ui.component.PmScrollArea
import com.photomgm.desktop.ui.component.PmTimePicker
import com.photomgm.desktop.ui.component.Segmented
import com.photomgm.desktop.ui.component.Tone
import com.photomgm.desktop.ui.component.bg
import com.photomgm.desktop.ui.component.fg
import com.photomgm.desktop.ui.component.pmPress
import com.photomgm.desktop.ui.model.CalendarTarget
import com.photomgm.desktop.ui.model.PhoneImportForm
import com.photomgm.desktop.ui.model.UiState
import com.photomgm.desktop.ui.model.closeCalendar
import com.photomgm.desktop.ui.model.closeTimePicker
import com.photomgm.desktop.ui.model.conflictPrompt
import com.photomgm.desktop.ui.model.defaultPhoneImportForm
import com.photomgm.desktop.ui.model.dialogHeight
import com.photomgm.desktop.ui.model.endFieldError
import com.photomgm.desktop.ui.model.parsePhoneDate
import com.photomgm.desktop.ui.model.parsePhoneTime
import com.photomgm.desktop.ui.model.phoneImportRange
import com.photomgm.desktop.ui.model.rangeError
import com.photomgm.desktop.ui.model.syncBlockReason
import com.photomgm.desktop.ui.model.syncTargetText
import com.photomgm.desktop.ui.model.toggleCalendar
import com.photomgm.desktop.ui.model.toggleTimePicker
import com.photomgm.desktop.ui.model.withEndDate
import com.photomgm.desktop.ui.model.withEndHour
import com.photomgm.desktop.ui.model.withEndMinute
import com.photomgm.desktop.ui.model.withStartDate
import com.photomgm.desktop.ui.model.withStartHour
import com.photomgm.desktop.ui.model.withStartMinute
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSize
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType
import java.time.LocalDate
import java.time.LocalTime

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

    // 四个控件的**窗口坐标**锚点：日期 / 时刻 × 开始 / 结束。
    // 分四个而不是一个：四个控件会各自在布局后上报，共用一个变量就是"后写的覆盖先写的",
    // 打开 A 却贴到 B 的坐标上。浮层同时只开一个（model 保证互斥），所以取哪一个由 target 决定。
    //
    // ★ 这里存的是 State 而不是委托值：锚点每帧（滚动 / 拖动）都会被重写，
    //   一旦在本函数作用域里**读**它，整张弹窗（头 + 滚动区 + 结果条）就会跟着重组。
    //   传 State 进去、在浮层里读，重组就只发生在那一层。
    val startDateAnchor = remember { mutableStateOf<IntRect?>(null) }
    val startTimeAnchor = remember { mutableStateOf<IntRect?>(null) }
    val endDateAnchor = remember { mutableStateOf<IntRect?>(null) }
    val endTimeAnchor = remember { mutableStateOf<IntRect?>(null) }

    val mode = PhoneLinkMode.values()[form.modeIndex.coerceIn(0, PhoneLinkMode.values().size - 1)]
    val phoneOptions = remember(mode, manualDirs) { actions.phoneDirs(mode) + manualDirs }

    // 区间类校验（V5 / 上限 / 空值）是**实时**的：改日期或点时分都重算。
    // 顶部按钮的 enabled、字段下方的提示行、结束字段的标红边框都读这一份结果 ——
    // 三处各算一遍的话，迟早出现"按钮灰着但没有原因"。
    val validateRange = actions.phoneValidateRange
    val rangeErr = rangeError(form, validateRange)
    val endErr = endFieldError(form, validateRange)

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
        // 卡片自己的原点与尺寸：浮层要按「卡片内坐标」定位，才跟得上卡片拖动与窗口缩放。
        // 同样是 State 容器（不在此处读），理由见上面四个锚点。
        val cardOrigin = remember { mutableStateOf(IntOffset.Zero) }
        val cardSize = remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier.onGloballyPositioned { coords ->
                cardOrigin.value = coords.boundsInWindow().roundToIntRect().topLeft
                cardSize.value = coords.size
            },
        ) {
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
                        // 区间不合法时直接禁用（§3.5）：让"能不能点"在点之前就有答案，
                        // 而不是点完在结果条里报一句。原因由下方提示行给出。
                        enabled = !running && rangeErr == null,
                    )
                    Spacer(Modifier.weight(1f))
                    PmIconButton(Icons.Filled.Close, "关闭", { onUi(ui.copy(sheet = null)) })
                }
                Hairline()

                PmScrollArea(
                    Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(start = PmSpace.xl, end = PmSpace.xl, top = PmSpace.l, bottom = PmSpace.m),
                ) {
                    Field(
                        label = "照片时间区间",
                        hint = "点日期开日历，点时刻开时/分浮层（5 分钟一档）。可跨自然日，秒不参与文件夹命名。",
                    ) {
                        RangePointRow(
                            label = "开始",
                            target = CalendarTarget.START,
                            form = form,
                            enabled = !running,
                            invalid = false,
                            fallbackDate = patrolDate,
                            onForm = { form = it },
                            onDateAnchor = { startDateAnchor.value = it },
                            onTimeAnchor = { startTimeAnchor.value = it },
                        )
                        Spacer(Modifier.height(PmSpace.m))
                        RangePointRow(
                            label = "结束",
                            target = CalendarTarget.END,
                            form = form,
                            enabled = !running,
                            invalid = endErr != null,
                            fallbackDate = patrolDate,
                            onForm = { form = it },
                            onDateAnchor = { endDateAnchor.value = it },
                            onTimeAnchor = { endTimeAnchor.value = it },
                        )
                        // 实时提示：与顶部按钮的 disabled 同源（同一次 rangeError），
                        // 不会出现"按钮灰了但屏幕上没有任何原因"。
                        if (rangeErr != null) {
                            Spacer(Modifier.height(PmSpace.xs))
                            Text(rangeErr, style = PmType.caption, color = PmColor.danger)
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

            // ── 浮层：日历 / 时·分 ──────────────────────────────────────
            // 叠在卡片内容之上、不参与 Column 布局：展开/收起不会推动下面任何一段内容
            // （与主页日历同一个理由，见 component/DateField.kt 文件头）。
            // 位置统一由 calendarPanelOffset 算：水平居中于控件、贴其下方 6 dp、放不下翻上方、
            // 始终夹在卡片内。卡片被拖动时 onGloballyPositioned 重新上报，浮层跟着卡片走。
            val calendarTarget = form.calendarTarget
            val timeTarget = form.timePickerTarget
            when {
                calendarTarget != null -> {
                    val isStart = calendarTarget == CalendarTarget.START
                    SheetFloatingPanel(
                        anchor = if (isStart) startDateAnchor else endDateAnchor,
                        cardOrigin = cardOrigin,
                        cardSize = cardSize,
                        panelW = CALENDAR_PANEL_W,
                        panelH = CALENDAR_PANEL_H,
                        onDismiss = { form = form.closeCalendar() },
                    ) {
                        PmCalendarPanel(
                            selected = (if (isStart) parsePhoneDate(form.startDate) else parsePhoneDate(form.endDate))
                                ?: patrolDate,
                            onPick = { picked ->
                                form = (if (isStart) form.withStartDate(picked) else form.withEndDate(picked))
                                    .closeCalendar()
                            },
                            onClose = { form = form.closeCalendar() },
                        )
                    }
                }

                timeTarget != null -> {
                    val isStart = timeTarget == CalendarTarget.START
                    val current = (if (isStart) parsePhoneTime(form.startTime) else parsePhoneTime(form.endTime))
                        ?: LocalTime.MIDNIGHT
                    SheetFloatingPanel(
                        anchor = if (isStart) startTimeAnchor else endTimeAnchor,
                        cardOrigin = cardOrigin,
                        cardSize = cardSize,
                        panelW = TIME_PANEL_W,
                        panelH = TIME_PANEL_H,
                        onDismiss = { form = form.closeTimePicker() },
                    ) {
                        TimePanel(
                            hour = current.hour,
                            minute = current.minute,
                            // 点一格 = 回填 + 收起：时与分是"选完就走"的瞬时动作，
                            // 再要一次"确定"就是同一次决策按两次（与日历格子同一契约）。
                            onHour = { h ->
                                form = (if (isStart) form.withStartHour(h) else form.withEndHour(h)).closeTimePicker()
                            },
                            onMinute = { m ->
                                form = (if (isStart) form.withStartMinute(m) else form.withEndMinute(m))
                                    .closeTimePicker()
                            },
                        )
                    }
                }
            }
        }
    }
}

// 一行时刻：日期按钮 + 时刻按钮。两者的面板都画在卡片浮层里（见 SheetFloatingPanel）。
//
// ★ 为什么时分不再内嵌两列：内嵌时这一行有 200 dp 高，两行 400 dp，把「同步到哪儿」
//   「连接方式」「结果条」全挤到折叠线以下；而时刻与日期一样是**一次性选择** ——
//   瞬时动作不该改变页面结构（同一理由见 component/DateField.kt 文件头）。
// ★ 两行仍用同一个组合（而不是复用 DateTimeField）：DateTimeField 把日期与时分画成
//   "标签在上、控件在下"的两层，两行并排会翻倍拉高；这里要的是一行三格。
//   DateTimeField 保留为调试组件，见 component/DateTimeField.kt 文件头。
@Composable
private fun RangePointRow(
    label: String,
    target: CalendarTarget,
    form: PhoneImportForm,
    enabled: Boolean,
    invalid: Boolean,
    fallbackDate: LocalDate,
    onForm: (PhoneImportForm) -> Unit,
    /** 上报日期按钮的窗口坐标矩形，供卡片浮层定位。 */
    onDateAnchor: (IntRect) -> Unit,
    /** 上报时刻按钮的窗口坐标矩形。 */
    onTimeAnchor: (IntRect) -> Unit,
) {
    val isStart = target == CalendarTarget.START
    // 半成品输入（"2026-11-0"）在日历上无法表达：取不到合法日期就退回巡查日期，
    // 让日历至少能打开；一点格子就会把这个字段写成完整值，半成品自然被修好。
    val date = (if (isStart) parsePhoneDate(form.startDate) else parsePhoneDate(form.endDate))
        ?: fallbackDate
    val time = (if (isStart) parsePhoneTime(form.startTime) else parsePhoneTime(form.endTime))
        ?: LocalTime.MIDNIGHT
    val shape = RoundedCornerShape(PmRadius.control)

    Row(
        Modifier
            .fillMaxWidth()
            // 标红只画边框、不加内边距：加内边距会让"结束"行比"开始"行矮进去几像素，
            // 两行的日期按钮就不再对齐了。
            .then(if (invalid) Modifier.border(1.dp, PmColor.danger, shape) else Modifier),
        // 三个控件同高（30 dp），居中对齐；内嵌时分列撤掉后不再需要 Top 对齐。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(28.dp).height(PmSize.control),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(label, style = PmType.label, color = if (invalid) PmColor.danger else PmColor.ink2)
        }
        Spacer(Modifier.width(PmSpace.s))
        PmDateField(
            date = date,
            onChange = {
                if (enabled) onForm(if (isStart) form.withStartDate(it) else form.withEndDate(it))
            },
            open = form.calendarTarget == target,
            onToggle = { if (enabled) onForm(form.toggleCalendar(target)) },
            onAnchor = onDateAnchor,
        )
        Spacer(Modifier.width(PmSpace.l))
        TimeField(
            time = time,
            open = form.timePickerTarget == target,
            enabled = enabled,
            onClick = { onForm(form.toggleTimePicker(target)) },
            modifier = Modifier.onGloballyPositioned { onTimeAnchor(it.boundsInWindow().roundToIntRect()) },
        )
    }
}

/** 时刻按钮：只读显示 `HH:mm`，点一下弹出时/分浮层。 */
@Composable
private fun TimeField(
    time: LocalTime,
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PmRadius.control)
    Row(
        modifier
            .width(84.dp)
            .height(PmSize.control)
            .clip(shape)
            .background(PmColor.surface)
            .border(1.dp, if (open || hovered) PmColor.accent.copy(alpha = 0.55f) else PmColor.lineStrong, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction)
            // 与日期按钮同一个手势原语：pmPress 在**按下**即回调，时刻是一次性选择，不必等抬起。
            .pmPress { if (enabled) onClick() }
            .padding(horizontal = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(hhmm(time), style = PmType.numeric, color = if (enabled) PmColor.ink else PmColor.ink4)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = if (open) "收起时/分" else "展开时/分",
            tint = if (open) PmColor.accent else PmColor.ink4,
            modifier = Modifier.size(16.dp),
        )
    }
}

// 补零格式在 model 里（TIME_FMT 是私有的）；这里只要 4 个数字，不引第二个格式化器。
private fun hhmm(t: LocalTime): String {
    val hh = if (t.hour < 10) "0${t.hour}" else "${t.hour}"
    val mm = if (t.minute < 10) "0${t.minute}" else "${t.minute}"
    return "$hh:$mm"
}

/** 日历浮层的首帧估算高（与 DatePickerLayer 的 ESTIMATED_H 同值）。测量到实际尺寸后即被替换。 */
private val CALENDAR_PANEL_H = 264.dp

/** 时/分浮层估算尺寸：两列 48+4+48 + 左右内边距 8×2。测量后同样改用实际值。 */
private val TIME_PANEL_W = 116.dp
private val TIME_PANEL_H = 216.dp

/**
 * 卡片内浮层：把**窗口坐标**的锚点换算成卡片内坐标，再交给 [calendarPanelOffset] 定位。
 *
 * ★ 为什么画在卡片里而不是根层：浮层必须跟卡片一起动（卡片可拖动）。
 *   同一个坐标系里画 = 拖动、窗口缩放都不用额外补偿；根层浮层会出现"卡片跑了、面板留在原地"。
 * ★ 锚点与卡片尺寸都以 [State] 传入、只在本函数里读：它们每帧都可能变（滚动/拖动），
 *   在调用方作用域读会把整张弹窗一起重组。
 * ★ 点击层铺满卡片：点面板之外 = 收起面板（与主页日历同一个交互契约）。
 *   卡片之外的点仍由 SheetHost 的遮罩处理（关整个弹窗），这里不去抢。
 */
@Composable
private fun BoxScope.SheetFloatingPanel(
    anchor: State<IntRect?>,
    cardOrigin: State<IntOffset>,
    cardSize: State<IntSize>,
    panelW: Dp,
    panelH: Dp,
    onDismiss: () -> Unit,
    panel: @Composable () -> Unit,
) {
    val rect = anchor.value
    val origin = cardOrigin.value
    val box = cardSize.value
    // 锚点还没上报（首帧）或卡片尺寸未知时什么都不画：宁可晚一帧出现，也不要闪到窗口左上角。
    if (rect == null || box.width <= 0 || box.height <= 0) return

    val density = LocalDensity.current
    val measured = remember { mutableStateOf(IntSize.Zero) }
    val w = measured.value.width.takeIf { it > 0 } ?: with(density) { panelW.toPx() }.toInt()
    val h = measured.value.height.takeIf { it > 0 } ?: with(density) { panelH.toPx() }.toInt()

    val pos = calendarPanelOffset(
        anchorX = rect.left - origin.x,
        anchorY = rect.top - origin.y,
        anchorW = rect.width,
        anchorH = rect.height,
        windowW = box.width,
        windowH = box.height,
        panelW = w,
        panelH = h,
    )

    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Box(
            Modifier
                .offset { pos }
                .onSizeChanged { measured.value = it }
                .shadow(14.dp, RoundedCornerShape(PmRadius.card))
                .clip(RoundedCornerShape(PmRadius.card))
                // 面板自己吞掉点击，否则点格子会顺着穿到下层把面板关掉。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            panel()
        }
    }
}

/** 时/分浮层：两列（时 24 项 / 分 13 项含 59），点一格即回填并收起。 */
@Composable
private fun TimePanel(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(PmRadius.card)
    Column(
        Modifier
            .background(PmColor.surface)
            .border(1.dp, PmColor.line, shape)
            .padding(PmSpace.m),
    ) {
        PmTimePicker(hour = hour, minute = minute, onHourChange = onHour, onMinuteChange = onMinute)
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
