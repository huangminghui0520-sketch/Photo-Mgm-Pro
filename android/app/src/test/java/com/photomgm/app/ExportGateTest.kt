// app/ExportGateTest —— 导出门禁（exportBusy）的确定性时序用例（E / issue #6）
//
// 为什么单独一个文件：
//   导出门禁的语义是"协程生命周期"层面的——置位在 launch 之前、复位在 Job 完成时。
//   要断言这种语义，必须能**控制**导出协程何时开始、何时被取消。原先 `Dispatchers.IO` 写死在
//   exportZip 里，只能靠 `await { ... }` 轮询真实墙钟（慢且不确定），也无法构造"还没开始就取消"。
//   现在 AppViewModel.exportDispatcher 可注入，配合 StandardTestDispatcher + runTest 的虚拟时间，
//   这两类语义都能直接断言。
package com.photomgm.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportGateTest {

    private lateinit var app: Application
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // 必须复位：Main 是进程级单例，残留会让同批其它用例（依赖 Android 主线程）行为异常
        Dispatchers.resetMain()
    }

    /**
     * 建一个把导出调度器接到**本用例 testScheduler** 上的 ViewModel。
     *
     * ★ 必须用 `StandardTestDispatcher(testScheduler)`，不能 `StandardTestDispatcher()`：
     *   后者会自带一个独立 scheduler，`runTest` 里的 `advanceUntilIdle()` 推的是 TestScope 的
     *   scheduler，两个 scheduler 各自排队 → 推进永远推不动被测协程（用例会假死/假绿）。
     */
    private fun TestScope.newVm(): AppViewModel {
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        return AppViewModel(app).also { it.exportDispatcher = testDispatcher }
    }

    /**
     * 门禁释放（提前返回分支）。
     *
     * 两个必须同时成立：① 提前返回也要复位 exportProgress（否则导出入口永远显示"正在导出"）；
     * ② 提前返回也要释放门禁（否则第二次点击被静默吞掉，用户以为"导出没反应"）。
     *
     * ★ 与原 AppViewModelTest 里那条轮询用例是**同一判据**，只是改成 runTest 驱动：
     *   不再 `await` 真实时间，`advanceUntilIdle()` 一步推进到导出结束，结论确定。
     */
    @Test
    fun `导出提前返回后门禁与进度都被复位_可再次导出`() = runTest {
        val vm = newVm()
        vm.setOutputDir(null)                       // 未设置输出目录 → 走提前返回分支
        advanceUntilIdle()                          // 让构造/设置引发的流水线预约先跑完
        assertFalse("前置条件：空闲状态才能进入导出", vm.state.value.busy)

        vm.exportZip()
        advanceUntilIdle()

        assertNotNull("未设置输出目录时应给出提示", vm.state.value.message)
        assertNull(
            "提前返回必须复位 exportProgress，否则导出入口永久显示'正在导出'",
            vm.state.value.exportProgress,
        )
        assertFalse("提前返回必须释放门禁", vm.exportBusy.get())

        // 关键：门禁已释放 → 第二次点击必须能进入流程（而不是被静默吞掉）
        vm.clearMessage()
        assertNull(vm.state.value.message)
        vm.exportZip()
        advanceUntilIdle()
        assertNotNull("门禁未释放 → 第二次导出被静默吞掉（用户点不动）", vm.state.value.message)
    }

    /**
     * ★ 本轮新增（issue #6 要求的判据）：导出协程被 cancel 后，门禁必须恢复 false。
     *
     * 构造的是**最刁钻**的那种取消：`exportZip()` 已经 CAS 占住门禁、Job 已创建，但调度器
     * 还没被推进过——协程体一次都没执行。此时取消，写在 body 里的 `finally` 根本不会触发，
     * 旧实现下门禁会永远停在 true，导出入口从此永久禁用且无任何提示。
     *
     * 因此修复把复位从 body 的 finally 挪到 `job.invokeOnCompletion`：它挂在 Job 生命周期上，
     * body 跑没跑、正常结束、抛异常、被取消，都必定回调一次。
     */
    @Test
    fun `导出协程被取消后门禁恢复_导出入口不会永久禁用`() = runTest {
        val vm = newVm()
        vm.setOutputDir(null)
        advanceUntilIdle()

        vm.exportZip()
        // 调度器尚未推进：body 一次都没跑，此时只应有"门禁已占位 + 进度已归零"这两个同步副作用
        assertTrue("exportZip 必须在 launch 之前同步占住门禁", vm.exportBusy.get())
        assertNull("此时不应有任何导出结果", vm.state.value.exportMessage)

        // 关键：在协程体开始执行之前就取消（等价于 ViewModel 在导出排队阶段被销毁）
        vm.viewModelScope.cancel()
        advanceUntilIdle()

        assertFalse(
            "协程未执行即被取消，门禁也必须释放，否则导出按钮永久点不动（且没有任何提示）",
            vm.exportBusy.get(),
        )
        assertNull("取消后进度必须归零", vm.state.value.exportProgress)
        assertNull("取消不应产生'导出失败'之类的误导性结果", vm.state.value.exportMessage)
    }

    /** 对照组：正常走完的导出（提前返回分支）结束后，门禁同样必须回到空闲。 */
    @Test
    fun `导出正常结束后门禁回到空闲`() = runTest {
        val vm = newVm()
        vm.setOutputDir(null)
        advanceUntilIdle()

        vm.exportZip()
        advanceUntilIdle()
        assertFalse("导出结束（无论成功/失败/提前返回）门禁都必须释放", vm.exportBusy.get())
        assertNull(vm.state.value.exportProgress)

        // 门禁空闲 → 还能再发起一次（CAS 能重新置位）
        vm.exportZip()
        assertTrue("空闲状态下再次点击应能占住门禁", vm.exportBusy.get())
        advanceUntilIdle()
        assertFalse(vm.exportBusy.get())
    }
}
