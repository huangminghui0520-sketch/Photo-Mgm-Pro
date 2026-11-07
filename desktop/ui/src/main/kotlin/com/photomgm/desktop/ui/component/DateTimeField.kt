// ui/component/DateTimeField.kt —— 时刻组件（**调试用**）
//
// 调试用：生产代码请用 PhoneImportSheet 内的新组合（PmDateField + PmTimePicker）；
// 本组件保留是为了手工走查 / Demo 时能在一处同时改日期与时分，
// **不参与手机导入弹窗的实际链路**（那边要「开始 / 结束」两行并排，一行一个组件会拉高两倍）。
//
// ★ 改造前它自己画两个文本框（yyyy-MM-dd / HH:mm），与日历零耦合；
//   现在日期换 PM 里那颗日历（[PmDateField]），时间换 [PmTimePicker] ——
//   全项目只有一套选日期、一套选时间的交互，调试路径也不例外，否则"调试时看到的"
//   和生产里能点的东西不是同一套，走查结论会失真。
//
// ★ 面板为什么在这里**就地展开**（而不是像主页那样挂根覆盖层）：
//   主页浮层要跨页面定位、还要躲开日志框，所以走 AppShell 的根覆盖层；
//   本组件只给调试用，调用点就在一个普通 Column 里，就地展开的布局位移没人受影响，
//   也就不必为它再复制一套"窗口像素锚点"的逻辑。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType
import java.time.LocalDate

// 一个时刻 = 日期（日历）+ 时分（两列）。
// 日期 / 时 / 分是三个独立回调：谁改了都只回传自己那一项，拼装由 model 负责，
// 组件不持有业务值（本组件除了"日历开着没有"之外没有别的状态）。
@Composable
fun DateTimeField(
    label: String,
    date: LocalDate,
    hour: Int,
    minute: Int,
    onDateChange: (LocalDate) -> Unit,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    // 日历开合是**组件自己的**瞬时状态：调试组件没有外层覆盖层可托管，
    // 也不需要被外部程序化打开/关闭。
    var open by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PmType.label, color = PmColor.ink2)
        }
        Spacer(Modifier.height(PmSpace.xs))
        Row(verticalAlignment = Alignment.Top) {
            PmDateField(
                date = date,
                onChange = onDateChange,
                open = open,
                onToggle = { if (enabled) open = !open },
                // 就地展开，不需要窗口锚点；签名要求这个参数，给空实现并说明理由。
                onAnchor = {},
            )
            Spacer(Modifier.width(PmSpace.l))
            PmTimePicker(
                hour = hour,
                minute = minute,
                onHourChange = onHourChange,
                onMinuteChange = onMinuteChange,
                enabled = enabled,
            )
        }
        if (open) {
            Spacer(Modifier.height(PmSpace.xs))
            PmCalendarPanel(
                selected = date,
                onPick = {
                    onDateChange(it)
                    open = false
                },
                onClose = { open = false },
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(PmSpace.xs))
            Text(hint, style = PmType.caption, color = PmColor.ink3)
        }
    }
}
