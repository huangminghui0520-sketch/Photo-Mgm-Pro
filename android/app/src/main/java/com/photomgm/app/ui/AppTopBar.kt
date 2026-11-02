// app/ui/AppTopBar.kt —— 顶部栏：左侧「巡查日期」选择器 + 右侧「设置」按钮
// ★ 2026-10-07 UI 重建：保持 Row + background 实现（不用 Surface 包整栏——
//   iQOO/OriginOS 上 Surface 容器曾拦截子元素触摸，见 docs/UI-REDESIGN.md P1）。
package com.photomgm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶部栏。
 *
 * @param dateText 当前巡查日期（ISO 形式，如 2026-10-07）
 * @param onDateClick 点击日期 → 打开日历弹窗
 * @param onSettingsClick 点击齿轮 → 打开设置弹窗
 */
@Composable
internal fun AppTopBar(
    dateText: String,
    onDateClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 左：巡查日期 ──
        Text(
            "巡查日期",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onDateClick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                dateText,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
            )
        }

        Spacer(Modifier.weight(1f))

        // ── 右：设置 ──
        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
