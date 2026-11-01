// ui/component/Scroll.kt —— 带可见滚动条 + 边缘渐隐的滚动区
//
// ★ 为什么必须画这根条：Compose 的滚动默认**没有任何可视线索**。设置弹窗在小窗口下
//   （886×503 时可用高度只有 420 dp）"导出内容"整段落在折叠线以下，用户看不到勾选框，
//   也就等于关不掉台账——这不是样式问题，是功能丢失。
// ★ 为什么还要渐隐：滚动条只回答"能不能滚"，不回答"下面被切掉的是半行还是一整段"。
//   内容在折叠线上被硬切时（这里正好切在勾选框中间），读起来像渲染坏了；给它 20 dp 的
//   白到透明的过渡，同样的裁切就读成"还有内容，可以往下滚"。
// ★ 反过来，内容装得下时**两样都不画**：一条满长的灰条 + 一层灰边会让人以为"下面还有"，
//   于是把已经能看全的内容又滚一遍。
package com.photomgm.desktop.ui.component

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.photomgm.desktop.ui.theme.PmColor
import com.photomgm.desktop.ui.theme.PmSpace

/** 小于这个像素数的剩余量，不值得用一层渐隐去提示。 */
private const val FADE_MIN = 12

@Composable
fun PmScrollArea(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(PmSpace.xl),
    /** 渐隐色：必须与滚动区所在容器的底色一致，否则会看到一条白色的边。 */
    fadeColor: Color = PmColor.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(contentPadding),
            content = content,
        )
        // 只差几个像素时不要画渐隐：一层 20 dp 的白雾盖住 7 dp 的溢出，会让最后一整行
        // 文字发灰，看起来像渲染坏了，而不是"下面还有"。
        val remaining = scroll.maxValue - scroll.value
        if (scroll.value > FADE_MIN) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Brush.verticalGradient(listOf(fadeColor, fadeColor.copy(alpha = 0f)))),
            )
        }
        if (remaining > FADE_MIN) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Brush.verticalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor))),
            )
        }
        if (scroll.maxValue > FADE_MIN) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = PmSpace.m, bottom = PmSpace.m, end = PmSpace.xxs)
                    .width(10.dp),
            )
        }
    }
}
