package io.github.yangyang2857.afguard.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import io.github.yangyang2857.afguard.ConfirmRequest
import io.github.yangyang2857.afguard.ConfirmationHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用系统 AlertDialog 做"要不要允许这次调用"的确认。
 *
 * 为什么不是默认实现：库本身不替宿主编排 UI 生命周期（Activity 可能被回收、App 可能没有
 * 前台界面），所以 core 只留一个 fun interface。这里给一份能直接用的实现。
 *
 * 已知的两个坑，都在代码里处理了：
 * 1. **主线程调用必然死锁** —— 弹窗要主线程，而主线程正卡在 [confirm] 里等结果。直接判否，
 *    让 Guard 走 DENIED_USER 而不是卡死 Agent。
 * 2. **超时后弹窗还挂着** —— 用户点"允许"时调用早已结束，策略不能因此变成同意。所以用
 *    [AtomicBoolean] 记真实点击，超时返回 false；弹窗本身 dismiss 掉。
 */
class DialogConfirmationHandler(
    private val activity: () -> Activity?,
    private val copy: Prompts = Prompts.Default
) : ConfirmationHandler {

    override fun confirm(request: ConfirmRequest): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 不要在调用线程上等主线程，那是在等自己。
            return false
        }
        val host = activity() ?: return false

        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        val posted = Handler(Looper.getMainLooper()).post {
            val current = activity()
            if (current == null || current.isFinishing || current.isDestroyed) {
                latch.countDown()
                return@post
            }
            runCatching {
                AlertDialog.Builder(current)
                    .setTitle(copy.title(request))
                    .setMessage(copy.message(request))
                    .setPositiveButton(copy.allowLabel) { _, _ -> allowed.set(true); latch.countDown() }
                    .setNegativeButton(copy.denyLabel) { _, _ -> latch.countDown() }
                    .setCancelable(false)
                    .create()
                    .show()
            }.onFailure { latch.countDown() }
        }
        if (!posted) {
            // 主线程已经在退出，post 不进去，别白等。
            return false
        }

        val timeout = request.timeoutMillis.coerceAtLeast(MIN_TIMEOUT_MS)
        val answered = runCatching { latch.await(timeout, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        return answered && allowed.get()
    }

    companion object {
        /** 最低等待 1 秒：再短用户来不及看清弹窗，等于随机拒绝。 */
        const val MIN_TIMEOUT_MS = 1_000L
    }
}

/** 文案。抽出来是为了让宿主改语气，而不是 fork 整个 handler。 */
interface Prompts {
    fun title(request: ConfirmRequest): String
    fun message(request: ConfirmRequest): String
    val allowLabel: String
    val denyLabel: String

    object Default : Prompts {
        override fun title(request: ConfirmRequest): String =
            "允许「${request.appName}」执行 ${request.functionId}？"

        override fun message(request: ConfirmRequest): String = buildString {
            append("调用方：").append(request.callerPackage).append('\n')
            append("风险等级：").append(request.risk.name)
            if (request.sensitiveKinds.isNotEmpty()) {
                append('\n')
                append("涉及敏感数据：")
                append(request.sensitiveKinds.joinToString("、") { it.name })
            }
            append('\n')
            append("（${request.timeoutMillis / 1000} 秒内不选择，按拒绝处理）")
        }

        override val allowLabel: String = "允许一次"
        override val denyLabel: String = "拒绝"
    }
}

/** Application 里一行接上：GuardConfig.confirmationHandler = GuardUi.dialogHandler(activityProvider)。 */
object GuardUi {
    @JvmStatic
    fun dialogHandler(activity: () -> Activity?, prompts: Prompts = Prompts.Default): ConfirmationHandler =
        DialogConfirmationHandler(activity, prompts)
}
