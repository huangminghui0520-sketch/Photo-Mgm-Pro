// ui/component/DateTimeField.kt —— 日期 + 时分两段输入（手机导入弹窗专用）
//
// ★ 刻意不复用 PmDateField：那是纯 LocalDate 的浮层日历，没有时分、也不能表达区间。
//   这里只要两个普通文本框：yyyy-MM-dd + HH:mm，与原日历零耦合（裁定「不改日期选择器」）。
//   解析与校验都在 model/PhoneImportUi.kt 的纯函数里，本文件只画控件、只回传字符串。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType

// 一个时刻 = 日期字段 + 时分字段。
// 宽度写死：两个 [DateTimeField] 并排时，日期列与时刻列的起点必须上下对齐，
// 自适应会让「开始」和「结束」两行错开几个像素，扫一眼像填错了行。
@Composable
fun DateTimeField(
    label: String,
    date: String,
    time: String,
    onDateChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PmType.label, color = PmColor.ink2)
        }
        Spacer(Modifier.height(PmSpace.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PmTextField(
                value = date,
                onValueChange = onDateChange,
                placeholder = "yyyy-MM-dd",
                enabled = enabled,
                modifier = Modifier.width(132.dp),
            )
            Spacer(Modifier.width(PmSpace.s))
            PmTextField(
                value = time,
                onValueChange = onTimeChange,
                placeholder = "HH:mm",
                enabled = enabled,
                modifier = Modifier.width(84.dp),
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(PmSpace.xs))
            Text(hint, style = PmType.caption, color = PmColor.ink3)
        }
    }
}
