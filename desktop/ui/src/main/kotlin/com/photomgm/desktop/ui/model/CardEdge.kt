// ui/model/CardEdge.kt —— 卡片描边的语义（谁抢到这条 1 dp 的边）
//
// ★ 一条边只能表达一件事，所以"谁优先"必须是**纯函数**，而不是散在 Composable 里的
//   一串 if。否则下次加一种状态（比如"导出失败的事件"）时，必然有人往那个 when 里补一档，
//   条件顺序一乱，同一屏里两张卡片就会用同一条边表达两件事。
//
// ★ 优先级按"复核时先看哪个"排：
//     ① 未匹配池 —— 它不是事件，是列表末尾的待处理单元（暖底 + 暖边，连底色都不同）；
//     ② 有没有照片 —— 一列卡片里最先扫的就是"这张有没有图"，红边回答它；
//     ③ 需不需要人处理 —— 待确认（无照片 / 待定时间 / 有 hint），强调色边；
//     ④ 静止 —— 普通分隔线。
//   有照片 + 待确认时给红边：待确认的信息并没有丢（序号用强调色、问题标签还在），
//   只是那条边让给"有图"这个更基础的事实。
package com.photomgm.desktop.ui.model

enum class CardEdge {
    /** 未匹配照片池：暖底暖边。 */
    UNMATCHED,

    /** 已挂照片：红色边。 */
    PHOTOS,

    /** 需要人处理（待确认）：强调色边。 */
    REVIEW,

    /** 普通卡片：分隔线。 */
    REST,
}

fun cardEdge(hasPhotos: Boolean, review: Boolean, unmatched: Boolean): CardEdge = when {
    unmatched -> CardEdge.UNMATCHED
    hasPhotos -> CardEdge.PHOTOS
    review -> CardEdge.REVIEW
    else -> CardEdge.REST
}
