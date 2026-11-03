// app/ViewDerivationParityTest.kt —— 双跑对比：安卓现行实现 vs shared/domain 新实现（T07）
//
// PC 侧的对比（`desktop/core/.../ViewDerivationParityTest`）覆盖了全部输入组合，
// 这一份只回答一个问题：**安卓那几个方法有没有在抄写时走样**。
//
// 所以这里不重复随机扫描，而是把安卓实际会走到的路径各测一遍：
//   · 安卓没有「复制到多事件」入口 → copyMap 恒为空（与生产一致，不假装支持）
//   · 安卓的重复组来自 `state.photos`，测试里构造不出来（`_state` 是 private，
//     没有 forTest 工厂 —— 那是 T08 的事）→ 三态展开走「无重复组」这一条，
//     与 domain 传空 dupGroups 对比，等价性仍然成立
//
// ★ T12 切换调用后本文件删除。
package com.photomgm.app

import algorithm.ClassifyResult
import androidx.test.core.app.ApplicationProvider
import domain.view.AdjustmentLayer
import domain.view.ViewDerivation
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewDerivationParityTest {

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext())
    }

    private fun classify() = ClassifyResult(
        eventPhotoMap = linkedMapOf(1 to mutableListOf(1L, 2L), 2 to mutableListOf(3L)),
        unmatched = mutableListOf(4L),
        interventions = emptyList(),
        pendingEventIds = emptyList(),
    )

    private fun layer() = AdjustmentLayer(overrideMap = vm.state.value.overrideMap)

    @Test
    fun `无调整时 effectiveMap 与共享实现一致`() {
        val c = classify()
        assertEquals(ViewDerivation.effectiveMap(c, layer()), vm.effectiveMap(c))
    }

    @Test
    fun `移动后 effectiveMap 与共享实现一致`() {
        vm.movePhoto(1L, 2)
        val c = classify()
        assertEquals(
            ViewDerivation.effectiveMap(c, layer()),
            vm.effectiveMap(c),
        )
        assertEquals(
            "移动后原事件不该还留着这张",
            listOf(2L),
            vm.effectiveMap(c)[1],
        )
    }

    @Test
    fun `批量移动后 effectiveMap 与共享实现一致`() {
        vm.movePhotos(listOf(1L, 3L), 2)
        val c = classify()
        assertEquals(ViewDerivation.effectiveMap(c, layer()), vm.effectiveMap(c))
    }

    @Test
    fun `移出事件后 effectiveUnmatched 与共享实现一致`() {
        vm.removePhotosFromEvent(listOf(2L))
        vm.movePhoto(4L, 1)
        val c = classify()
        assertEquals(
            "被移出的 2 应进未匹配池，被移入的 4 应离开池",
            ViewDerivation.effectiveUnmatched(c, layer()),
            vm.effectiveUnmatched(c),
        )
        assertEquals(listOf(2L), vm.effectiveUnmatched(c))
    }

    @Test
    fun `previewEventMap 与共享实现一致`() {
        // 安卓侧没有注入重复组的入口（见文件头），dupGroups 恒为空 → 三态展开不折叠任何照片。
        vm.movePhoto(3L, 1)
        val c = classify()
        val mode = vm.state.value.dedupKeepMode
        assertEquals(
            ViewDerivation.expandedEventMap(c, layer(), mode, emptyMap(), vm.state.value.marked),
            vm.previewEventMap(c),
        )
    }

    @Test
    fun `重复移动同一张照片不会累积`() {
        vm.movePhoto(1L, 2)
        vm.movePhoto(1L, 2)
        vm.movePhoto(1L, 2)
        assertEquals(mapOf(1L to 2), vm.state.value.overrideMap)
        assertEquals(ViewDerivation.effectiveMap(classify(), layer()), vm.effectiveMap(classify()))
    }
}
