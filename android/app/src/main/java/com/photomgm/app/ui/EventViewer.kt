// app/ui/EventViewer.kt —— 全屏照片查看器弹窗
// ★ 2026-10-07 按交互需求重写（删除旧实现，从零写）：
//   单击图片 = 放大 2.5x / 再单击还原 1x（用户明确要求移除双击放大）
//   双指 = 平滑缩放（1x–5x，以图片中心为锚；缩放状态下沉为本层 state 委托，手势闭包实时读取）
//   未放大单指左右滑动 = 翻页（系统相册默认方向：左滑下一张、右滑上一张）
//   放大后单指拖动 = 平移图片
//   放大（scale>1）时隐藏顶部工具条与底部胶片条，缩小回 1x 自动恢复
package com.photomgm.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.photomgm.app.ThumbnailSpec
import algorithm.model.Photo
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** 查看器需要展示的异常信息（来自分类引擎的 Intervention）。 */
data class PhotoAnomaly(val label: String, val detail: String)

private val viewerTimeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * 全屏照片查看器（弹窗）。
 *
 * @param photos 该事件的照片（已按拍摄时间升序）
 * @param initialIndex 打开时定位到第几张
 * @param anomalies photoId → 异常提示；空集合表示该照片正常
 * @param locationText 事件桩号（显示在标题下方）
 * @param onSetLedger 点「设为台账」：由调用方弹出槽位选择
 * @param onDismiss 关闭
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventViewer(
    photos: List<Photo>,
    initialIndex: Int,
    anomalies: Map<Long, PhotoAnomaly>,
    locationText: String,
    onSetLedger: (Photo) -> Unit,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) return

    val safeIndex = initialIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex) { photos.size }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ViewerContent(
            photos = photos,
            pagerState = pagerState,
            scope = scope,
            anomalies = anomalies,
            locationText = locationText,
            onSetLedger = onSetLedger,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 查看器内容层（不含 Dialog 包装）。
 * 单独抽出的原因：Robolectric 下 ComposeView.draw() 抓不到 Dialog 独立窗口的内容，
 * 只有把内容层暴露出来，渲染测试才能真正截到查看器的样子。
 *
 * ★ 缩放状态（scale/offsetX/offsetY）必须在本层用 state 委托管理：
 *   pointerInput 手势闭包捕获组合期参数，若状态从外部传入，闭包里的值永远是手势启动时的旧值，
 *   导致双指缩放每次从 1x 重算（表现为无法持续放大缩小/抖动）。本层托管后闭包每帧读取最新值。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ViewerContent(
    photos: List<Photo>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    scope: kotlinx.coroutines.CoroutineScope,
    anomalies: Map<Long, PhotoAnomaly>,
    locationText: String,
    onSetLedger: (Photo) -> Unit,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 放大时隐藏工具条与底部胶片条（避免放大图片溢出盖住缩略图造成"缩略图跟着移动"的错觉）
    val chromeShown = scale <= 1f

    // 切页即复位缩放与平移
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    val current = photos[pagerState.currentPage]
    val currentAnomaly = anomalies[current.id]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ── 主图区：横向分页（相册默认方向：左滑下一张、右滑上一张） ──
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
        ) { page ->
            val photo = photos[page]
            val isCurrent = page == pagerState.currentPage
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // W5(#3)：大图预览**故意**不走 ThumbnailSpec，保持 bare ref 让 Coil 按容器尺寸
                // 自动算缓存键 —— 它要的是清晰大图，与缩略图本就是两份不同尺寸的缓存，
                // 各自带尺寸维度才不会互相覆盖（裸 ref 键正是当初把尺寸维度挤掉的原因）。
                AsyncImage(
                    model = photo.sourceRef,
                    contentDescription = photo.displayName,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 64.dp)
                        .graphicsLayer {
                            if (isCurrent) {
                                scaleX = scale; scaleY = scale
                                translationX = offsetX; translationY = offsetY
                            }
                        }
                        // ── 单击 = 放大 / 还原（无双击） ──
                        // ★ key 含 pagerState.currentPage：Pager 预组合相邻页时 isCurrent=false（手势空注册），
                        //   翻页后若 key 不变，pointerInput 不重启、isCurrent 仍是旧值 → 手势永远失效
                        //   （症状：左右翻页后单击放大/双指缩放均无反应）。currentPage 变化即重启手势。
                        .pointerInput(photo.id, pagerState.currentPage) {
                            if (!isCurrent) return@pointerInput
                            detectTapGestures(
                                onTap = {
                                    if (scale > 1f) {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    } else {
                                        scale = 2.5f; offsetX = 0f; offsetY = 0f
                                    }
                                },
                            )
                        }
                        // ── 双指缩放 / 放大后单指平移 / 未缩放单指交 Pager 翻页 ──
                        // ★ 2026-10-07 修复：改用 PointerEventPass.Initial 消费双指事件。
                        //   旧实现默认在 Main 阶段消费——与父级 HorizontalPager 的滚动检测同阶段竞争：
                        //   缩放前若单指轻微移动，Pager 抢先启动滚动 → currentPage 变化 →
                        //   LaunchedEffect 把 scale 重置回 1x → 缩放永远无法累积（表现为"双指缩放未实现"）。
                        //   Initial 阶段（父→子传递）先拦截并消费双指事件后，Pager 在 Main 阶段
                        //   完全看不到这些事件，永不启动滚动。
                        // ★ key 同样含 currentPage：翻页后重启手势，避免预组合页的空手势残留。
                        // ★ 2026-10-07 修复"放大后拖动到边缘才切换照片"：平移分支旧条件是
                        //   multi && pressed.size==1（必须先双指缩放过）。单击放大（scale>1）后单指
                        //   拖动不满足该条件 → 事件放行 → Pager 接管 → 拖到边缘才翻页切换。
                        //   现改为 pressed.size==1 && scale>1 即平移消费（与是否双指无关）。
                        .pointerInput(photo.id, pagerState.currentPage) {
                            if (!isCurrent) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var started = false // 双指第一帧只记基线，不应用（防单指→双指瞬间跳变）
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    when {
                                        pressed.size >= 2 -> {
                                            if (started) {
                                                val zoomChange = event.calculateZoom()
                                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                                scale = newScale
                                                // 缩放以图片中心为锚：期间偏移归零，防抖动
                                                offsetX = 0f; offsetY = 0f
                                            } else {
                                                started = true
                                            }
                                            // 全部消费（含未移动指针）→ Pager 完全收不到
                                            event.changes.forEach { it.consume() }
                                        }
                                        pressed.size == 1 && scale > 1f -> {
                                            // 放大状态下单指 = 平移图片（无论单击放大还是双指缩放而来），
                                            // 消费移动帧 → Pager 收不到 → 不再"拖到边缘切换照片"
                                            val panChange = event.calculatePan()
                                            offsetX += panChange.x
                                            offsetY += panChange.y
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                        // 未缩放单指：Initial 不消费 → Main 阶段交给 Pager 翻页 / tap 判定
                                        else -> Unit
                                    }
                                } while (pressed.isNotEmpty())
                            }
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // ── 顶部工具条（放大时隐藏） ──
        if (chromeShown) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    Text(
                        "${pagerState.currentPage + 1} / ${photos.size}" +
                                current.captureTimeMs?.let {
                                    " · " + Instant.ofEpochMilli(it).atZone(algorithm.AppZone.ID)
                                        .format(viewerTimeFmt)
                                }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Text(
                        locationText.ifBlank { current.displayName },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { onSetLedger(current) }) {
                    Text("设为台账", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // ── 异常角标（正常照片不显示任何角标） ──
        currentAnomaly?.let { a ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = if (chromeShown) 68.dp else 12.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning, null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        a.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── 底部胶片条（仅 1x 且多张时显示；放大时隐藏） ──
        if (chromeShown && photos.size > 1) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    photos.forEachIndexed { idx, p ->
                        val isCur = idx == pagerState.currentPage
                        val hasAnomaly = anomalies.containsKey(p.id)
                        // ★ W5(#3)：LocalContext.current 是 @Composable 读，而 remember 的 calculation
                        //   带 @DisallowComposableCalls —— 必须在外面先取 context，不能写进 lambda 里。
                        val ctx = LocalContext.current
                        val thumbReq = remember(ctx, p.sourceRef) {
                            ThumbnailSpec.request(ctx, p.sourceRef)
                        }
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .then(
                                    if (isCur) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)
                                    ) else Modifier
                                )
                                .background(Color(0xFF333333))
                                .clickable {
                                    scope.launch { pagerState.scrollToPage(idx) }
                                },
                        ) {
                            // W5(#3)：胶片条与事件网格共用 ThumbnailSpec 的尺寸/缓存键 ——
                            // 只有两边逐字段一致，预加载写进内存缓存的那份才可能被这里命中。
                            AsyncImage(
                                model = thumbReq,
                                contentDescription = p.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (hasAnomaly) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape),
                                )
                            }
                        }
                    }
                }
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size} · 单击放大 · 左右滑动切换 · 双指缩放",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
