package io.github.yangyang2857.afguard.sample

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import io.github.yangyang2857.afguard.ConfirmRequest
import io.github.yangyang2857.afguard.ConfirmationHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 确认界面。库不提供 UI，只提供一个同步回调，这里用 AlertDialog 实现。
 *
 * 关键点：`confirm()` 是阻塞调用，通常发生在后台线程（Agent 的调用线程）；
 * 弹窗必须在主线程。所以这里用 CountDownLatch 把两边接起来。
 * 直接在主线程调 AfGuard.intercept() 并同步等待弹窗结果会死锁 —— 这是最常见的接错方式。
 */
class DialogConfirmationHandler : ConfirmationHandler {

    @Volatile
    private var activity: Activity? = null

    @Volatile
    private var dialog: AlertDialog? = null

    fun attach(host: Activity) {
        activity = host
    }

    fun detach(host: Activity) {
        if (activity !== host) return
        val pending = dialog
        dialog = null
        activity = null
        host.runOnUiThread { pending?.dismiss() }
    }

    override fun confirm(request: ConfirmRequest): Boolean {
        // 没有可见界面就没有"用户确认"这回事，一律拒绝，绝不静默放行
        val host = activity ?: return false

        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)

        host.runOnUiThread {
            if (host.isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            dialog = AlertDialog.Builder(host)
                .setTitle("Agent 请求调用「${request.functionId}」")
                .setMessage(describe(request))
                .setPositiveButton("允许") { _, _ ->
                    granted.set(true)
                    latch.countDown()
                }
                .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }

        return try {
            val answered = latch.await(request.timeoutMillis, TimeUnit.MILLISECONDS)
            if (!answered) Log.w(TAG, "confirmation timed out: ${request.functionId}")
            granted.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun describe(r: ConfirmRequest): String = buildString {
        append("应用：").append(r.appName).append('\n')
        append("调用方：").append(r.callerPackage).append('\n')
        append("函数：").append(r.functionId).append('\n')
        append("风险：").append(r.risk).append('\n')
        if (r.sensitiveKinds.isNotEmpty()) {
            append("涉及数据：").append(r.sensitiveKinds.joinToString()).append('\n')
        }
        append('\n')
            .append(r.timeoutMillis / 1000)
            .append(" 秒内未选择，将按拒绝处理。")
    }

    private companion object {
        const val TAG = "AFG/Confirm"
    }
}
