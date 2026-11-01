// ui/component/DateField.kt —— 巡查日期输入（文本框 + 前后一天 + 今天 + 星期）
//
// ★ 只放在设置弹窗里，但仍是"项目主键"：日期决定扫哪些照片、也决定日志里只有月日的时间补哪一年。
//   所以它必须一眼可见，且改完立刻生效（core.updateSettings 会把状态置为需重新分析）。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.format.dateText
import com.photomgm.desktop.ui.format.nowDate
import com.photomgm.desktop.ui.format.parseDate
import com.photomgm.desktop.ui.theme.PmSpace
import com.photomgm.desktop.ui.theme.PmType
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun PmDateField(
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 文本态与日期态分开：中间输入（"2026-13-01"）不该被弹回，也不该被当成有效值。
    var draft by remember(date) { mutableStateOf(dateText(date)) }
    val weekday = date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.CHINA)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PmTextField(
            value = draft,
            onValueChange = { raw ->
                draft = raw
                parseDate(raw)?.let { parsed -> if (parsed != date) onChange(parsed) }
            },
            modifier = Modifier.width(126.dp),
            textStyle = PmType.numeric,
            placeholder = "yyyy-MM-dd",
        )
        Spacer(Modifier.width(PmSpace.xs))
        PmIconButton(Icons.Filled.ChevronLeft, "前一天", { onChange(date.minusDays(1)) }, size = 28.dp)
        PmButton("今天", { onChange(nowDate()) }, kind = ButtonKind.GHOST)
        PmIconButton(Icons.Filled.ChevronRight, "后一天", { onChange(date.plusDays(1)) }, size = 28.dp)
        Spacer(Modifier.width(PmSpace.xs))
        Tag("周$weekday", Tone.NEUTRAL)
    }
}

/** 路径取"尾部"而不是头部：截头会把唯一有辨识度的末级目录名切掉。 */
fun shortPath(path: String, keep: Int = 34): String {
    if (path.length <= keep) return path
    val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.size <= 2) return path
    return "…/" + parts.takeLast(2).joinToString("/")
}
