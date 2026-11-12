package io.github.yangyang2857.afguard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Looper
import io.github.yangyang2857.afguard.ConfirmRequest
import io.github.yangyang2857.afguard.PiiKind
import io.github.yangyang2857.afguard.Risk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.atomic.AtomicReference

/**
 * [DialogConfirmationHandler] 的四条硬约束，都对应真机清单里"以前只能手动点"的项：
 *
 * - 主线程调用不能等自己 → 直接判否（否则 Agent 卡到超时，甚至死锁）
 * - 没有可见界面 → 拒绝，不静默放行（清单 A9）
 * - 超时 → 拒绝 **且** 收掉残留弹窗（清单 A8）
 * - 用户点允许 → 才返回 true
 *
 * 跑在 Robolectric 上，不需要设备，改坏即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DialogConfirmationHandlerTest {

    /** 只用来承载 AlertDialog 的最小宿主。 */
    class HostActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(android.R.style.Theme_Material_Light)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var activity: HostActivity
    private lateinit var handler: DialogConfirmationHandler

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        activity = controller.get()
        handler = DialogConfirmationHandler()
    }

    private fun request(timeoutMillis: Long) = ConfirmRequest(
        appName = "AFG Sample",
        callerPackage = "com.example.agent",
        functionId = "deleteNote",
        risk = Risk.HIGH,
        sensitiveKinds = setOf(PiiKind.PHONE),
        timeoutMillis = timeoutMillis,
    )

    /** 后台线程调用，模拟 Agent 的调用线程。 */
    private fun confirmFromWorker(timeoutMillis: Long): Pair<Thread, AtomicReference<Boolean>> {
        val answer = AtomicReference(false)
        val worker = Thread { answer.set(handler.confirm(request(timeoutMillis))) }
        worker.start()
        return worker to answer
    }

    /** 环境自检：Robolectric 里起得来的弹窗，才谈得上验后面的行为。 */
    /**
     * 弹窗是后台线程 post 到主线程才建的，主线程单次 idle 时它可能还没入队，
     * 所以这里轮询推主线程队列，直到窗口出现或超时。
     */
    private fun awaitDialog(timeoutMs: Long = 2_000) = run {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found: android.app.AlertDialog? = null
        while (found == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            found = ShadowAlertDialog.getLatestAlertDialog()
            if (found == null) Thread.sleep(10)
        }
        found
    }

    @Test
    fun `AlertDialog 在本测试环境中可以显示`() {
        val dialog = AlertDialog.Builder(activity).setTitle("self check").create()
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
        dialog.dismiss()
    }

    @Test
    fun `主线程调用直接拒绝且不弹窗`() {
        handler.attach(activity)

        assertFalse("在主线程上等主线程就是等自己", handler.confirm(request(2_000)))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun `没有 Activity 时拒绝`() {
        val (worker, answer) = confirmFromWorker(1_000)
        worker.join(3_000)

        assertFalse("无可见界面即拒，绝不静默放行", answer.get())
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun `detach 之后拒绝且不再弹窗`() {
        handler.attach(activity)
        handler.detach(activity)

        val (worker, answer) = confirmFromWorker(1_000)
        worker.join(3_000)

        assertFalse(answer.get())
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun `点允许返回 true`() {
        handler.attach(activity)
        val (worker, answer) = confirmFromWorker(3_000)

        val dialog = awaitDialog()
        assertNotNull("确认请求必须真的把弹窗显示出来", dialog)
        assertTrue(dialog!!.isShowing)

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        worker.join(3_000)

        assertTrue(answer.get())
    }

    @Test
    fun `点拒绝返回 false`() {
        handler.attach(activity)
        val (worker, answer) = confirmFromWorker(3_000)

        val dialog = awaitDialog()
        assertNotNull("确认请求必须真的把弹窗显示出来", dialog)

        dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        worker.join(3_000)

        assertFalse(answer.get())
    }

    @Test
    fun `超时返回 false 并收掉残留弹窗`() {
        handler.attach(activity)
        // 最短等待 1 秒，这里正好用下限：不点，等它自己放弃
        val (worker, answer) = confirmFromWorker(DialogConfirmationHandler.MIN_TIMEOUT_MS)

        val dialog = awaitDialog()
        assertNotNull("确认请求必须真的把弹窗显示出来", dialog)

        worker.join(5_000)
        assertFalse("超时按拒绝处理", answer.get())

        // 超时分支里的 dismiss 也是 post 到主线程的，再推一次
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("弹窗必须消失，否则用户对着已结束的请求点允许", dialog!!.isShowing)
    }
}
