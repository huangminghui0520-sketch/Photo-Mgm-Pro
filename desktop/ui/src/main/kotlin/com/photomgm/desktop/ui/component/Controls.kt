// ui/component/Controls.kt —— 原子控件
//
// 只放"到处都在用、且状态必须一致"的东西。状态集固定为六种：
//   default / hover / selected / focus / disabled / dragging
// 其中 dragging 只出现在照片格（见 PhotoGrid），其余控件不实现假状态。
//
// ★ 所有可点区域最小高度 30 dp。桌面端 26 dp 以下的点击目标在 125% 缩放下
//   实际只有 ~21 物理像素，误点率明显上升；这条不是审美，是可点性问题。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmRadius
import com.photomgm.desktop.ui.theme.PmSize
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun PmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.PRIMARY,
    icon: ImageVector? = null,
    height: Dp = PmSize.control,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg = when (kind) {
        ButtonKind.PRIMARY -> when {
            !enabled -> PmColor.surface2
            hovered -> PmColor.accentHover
            else -> PmColor.accent
        }

        ButtonKind.DANGER -> when {
            !enabled -> PmColor.surface2
            hovered -> PmColor.danger.copy(alpha = 0.88f)
            else -> PmColor.danger
        }

        ButtonKind.SECONDARY -> when {
            !enabled -> PmColor.surface2
            hovered -> PmColor.surface2
            else -> PmColor.surface
        }

        ButtonKind.GHOST -> if (hovered && enabled) PmColor.surface2 else Color.Transparent
    }
    val fg = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.DANGER -> if (enabled) PmColor.surface else PmColor.ink4
        ButtonKind.SECONDARY -> if (enabled) PmColor.ink else PmColor.ink4
        ButtonKind.GHOST -> if (enabled) PmColor.ink2 else PmColor.ink4
    }
    val border = when {
        kind == ButtonKind.SECONDARY && enabled -> BorderStroke(1.dp, PmColor.lineStrong)
        else -> null
    }

    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(bg)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(PmRadius.control)) else Modifier)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = PmSpace.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(PmSpace.s))
        }
        Text(text, style = PmType.label, color = fg, maxLines = 1)
    }
}

@Composable
fun PmIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 30.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> PmColor.accentSoft
        hovered && enabled -> PmColor.surface2
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> PmColor.ink4
        selected -> PmColor.accent
        else -> PmColor.ink2
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(PmRadius.control))
            .background(bg)
            .then(
                if (enabled) {
                    Modifier.pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(16.dp))
    }
}

/** 分段控件：用于"视图参数"这类互斥且需要显示当前值的选择（去重三态、检查器页签）。 */
@Composable
fun Segmented(
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(PmSize.control)
            .clip(RoundedCornerShape(PmRadius.control))
            .border(1.dp, PmColor.lineStrong, RoundedCornerShape(PmRadius.control))
            .background(PmColor.surface),
    ) {
        options.forEachIndexed { index, (label, hint) ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(PmSize.control)
                    .background(if (selected) PmColor.accentSoft else Color.Transparent)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        onSelect(index)
                    }
                    .padding(horizontal = PmSpace.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = PmType.label,
                    color = if (selected) PmColor.accent else PmColor.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

enum class Tone { NEUTRAL, ACCENT, OK, WARN, DANGER, INFO }

fun Tone.fg(): Color = when (this) {
    Tone.NEUTRAL -> PmColor.ink2
    Tone.ACCENT -> PmColor.accent
    Tone.OK -> PmColor.ok
    Tone.WARN -> PmColor.warn
    Tone.DANGER -> PmColor.danger
    Tone.INFO -> PmColor.info
}

fun Tone.bg(): Color = when (this) {
    Tone.NEUTRAL -> PmColor.surface2
    Tone.ACCENT -> PmColor.accentSoft
    Tone.OK -> PmColor.okSoft
    Tone.WARN -> PmColor.warnSoft
    Tone.DANGER -> PmColor.dangerSoft
    Tone.INFO -> PmColor.infoSoft
}

/** 小角标（不承担点击，只承担"这一行属于哪一类"）。 */
@Composable
fun Tag(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PmRadius.control))
            .background(tone.bg())
            .padding(horizontal = PmSpace.s, vertical = 1.dp),
    ) {
        Text(text, style = PmType.micro, color = tone.fg(), maxLines = 1)
    }
}

/** 可点筛选片：选中态用强调色描边 + 浅底，不做填充色块（避免与主按钮抢注意力）。 */
@Composable
fun FilterChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    tone: Tone = Tone.NEUTRAL,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> PmColor.accentSoft
        hovered -> PmColor.surface2
        else -> PmColor.surface
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(PmRadius.pill))
            .background(bg)
            .border(
                1.dp,
                if (selected) PmColor.accent else PmColor.line,
                RoundedCornerShape(PmRadius.pill),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = PmSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = PmType.label,
            color = if (selected) PmColor.accent else PmColor.ink2,
            maxLines = 1,
        )
        if (count != null) {
            Spacer(Modifier.width(PmSpace.xs))
            Text(
                count.toString(),
                style = PmType.numeric,
                color = when {
                    selected -> PmColor.accent
                    count > 0 && tone != Tone.NEUTRAL -> tone.fg()
                    else -> PmColor.ink3
                },
            )
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) {
        Box(modifier.width(1.dp).fillMaxHeight().background(PmColor.line))
    } else {
        Box(modifier.fillMaxWidth().height(1.dp).background(PmColor.line))
    }
}

@Composable
fun EmptyBlock(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PmSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = PmColor.ink4, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(PmSpace.m))
        Text(title, style = PmType.bodyStrong, color = PmColor.ink2)
        Spacer(Modifier.height(PmSpace.xs))
        Text(body, style = PmType.caption, color = PmColor.ink3)
        if (action != null) {
            Spacer(Modifier.height(PmSpace.l))
            action()
        }
    }
}

/** 表单字段：标签在上、控件在下、说明在最后一行（说明用 ink3，不用 ink4——那是对比度不合格的灰）。 */
@Composable
fun Field(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PmType.label, color = PmColor.ink2)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(PmSpace.xs))
        content()
        if (hint != null) {
            Spacer(Modifier.height(PmSpace.xs))
            Text(hint, style = PmType.caption, color = PmColor.ink3)
        }
    }
}

/**
 * 复选框：16 dp 方框 + 右侧标签。
 *
 * ★ 整行可点（含标签与说明），不是只有 16 dp 的方框可点。桌面端在一个 16 dp 的目标上
 *   瞄准，是在用精度换时间；把它扩成整行，点击面积从 256 像素变成几千像素，零成本。
 */
@Composable
fun PmCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PmRadius.control))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = PmSpace.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(PmRadius.control))
                .background(if (checked) PmColor.accent else PmColor.surface)
                .border(1.dp, if (checked) PmColor.accent else PmColor.lineStrong, RoundedCornerShape(PmRadius.control)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(PmSpace.m))
        Column {
            Text(label, style = PmType.body, color = PmColor.ink)
            if (hint != null) {
                Spacer(Modifier.height(PmSpace.xxs))
                Text(hint, style = PmType.caption, color = PmColor.ink3)
            }
        }
    }
}
