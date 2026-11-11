package io.github.yangyang2857.afguard.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.yangyang2857.afguard.AfGuard
import io.github.yangyang2857.afguard.GuardConfig
import io.github.yangyang2857.afguard.GuardResult
import io.github.yangyang2857.afguard.GuardedCall
import io.github.yangyang2857.afguard.internal.AuditRecord
import io.github.yangyang2857.afguard.internal.RingBufferAuditSink
import io.github.yangyang2857.afguard.ui.DialogConfirmationHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 演示界面。
 *
 * 每个按钮都等价于一次 Agent 发起的调用：走 [AfGuard.intercept]，而不是直接调业务函数。
 * 差别就在这里 —— 业务函数一行都不用改，拦截发生在调用边界上。
 */
class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var logView: TextView
    private lateinit var auditView: TextView

    private val confirmHandler: DialogConfirmationHandler
        get() = (application as SampleApp).confirmationHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        confirmHandler.attach(this)
        setContentView(buildUi())
        append("准备就绪。点按钮 = 模拟一次 Agent 调用。")
    }

    override fun onDestroy() {
        confirmHandler.detach(this)
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(title("AppFunction Guard 演示"))
        root.addView(hint("拦截链：白名单 → 策略 → 限流 → 参数校验 → 用户确认 → 执行 → 脱敏 → 审计"))

        root.addView(section("读写策略"))
        root.addView(
            row(
                button("listNotes · ALLOW") { callListNotes() },
                button("searchNotes(\"\") · 校验") { callSearchWithEmptyKeyword() }
            )
        )
        root.addView(
            row(
                button("deleteNote · 待确认") { callDeleteNote("n_001") },
                button("exportNotes · 脱敏") { callExportNotes() }
            )
        )
        root.addView(
            row(
                button("wipeAllNotes · 永久拒绝") { callWipeAll() },
                button("连发 9 次 deleteNote") { burstDelete() }
            )
        )

        root.addView(section("审计与数据"))
        root.addView(
            row(
                button("查看最近 20 条审计") { showAudit() },
                button("重置演示数据") { resetData() }
            )
        )

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, dp(12))
        }
        auditView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }

        root.addView(logView)
        root.addView(auditView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 0, 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.GRAY)
        setPadding(0, 0, 0, dp(12))
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { onClick() }
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { b ->
            addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------- 调用场景

    private fun callListNotes() {
        val args = Bundle().apply { putInt("limit", 10) }
        invoke("listNotes", args) { call ->
            NoteFunctions.listNotes(call.intOr("limit", 10))
        }
    }

    /** 空字符串违反 @TextLimit(min = 1)，应该在进入业务代码之前就被拦下。 */
    private fun callSearchWithEmptyKeyword() {
        val args = Bundle().apply { putString("keyword", "") }
        invoke("searchNotes", args) { call ->
            NoteFunctions.searchNotes(call.requireString("keyword"))
        }
    }

    private fun callDeleteNote(id: String) {
        val args = Bundle().apply { putString("id", id) }
        invoke("deleteNote", args) { call ->
            NoteFunctions.deleteNote(call.requireString("id"))
        }
    }

    private fun callExportNotes() {
        val args = Bundle().apply { putString("format", "plain") }
        invoke("exportNotes", args) { call ->
            NoteFunctions.exportNotes(call.requireString("format"))
        }
    }

    private fun callWipeAll() {
        invoke("wipeAllNotes", Bundle()) { _ ->
            NoteFunctions.wipeAllNotes()
        }
    }

    /** 连发 9 次，撞上 @RateLimit(calls = 5, perSeconds = 60, burst = 2)。 */
    private fun burstDelete() {
        append("→ 连发 9 次 deleteNote（5 次/60s，突发 2）")
        executor.execute {
            repeat(9) { index ->
                val args = Bundle().apply { putString("id", "n_00${index % 3 + 1}") }
                val result = AfGuard.intercept("deleteNote", args) { call ->
                    NoteFunctions.deleteNote(call.requireString("id"))
                }
                val line = when (result) {
                    is GuardResult.Ok -> "#${index + 1} 通过  ${result.value}"
                    is GuardResult.Err -> buildString {
                        append("#").append(index + 1).append(" ").append(result.error.code.name.lowercase())
                        result.error.retryAfterMs?.let { append("  等 ").append(it).append("ms 再试") }
                    }
                }
                runOnUiThread { append("  $line") }
            }
        }
    }

    // -------------------------------------------------------------- 执行封装

    private fun invoke(functionId: String, args: Bundle, block: (GuardedCall) -> Any?) {
        append("→ $functionId  ${describe(args)}")
        executor.execute {
            val startedAt = System.currentTimeMillis()
            val result = AfGuard.intercept(functionId, args, block)
            val cost = System.currentTimeMillis() - startedAt
            val text = when (result) {
                is GuardResult.Ok -> buildString {
                    append("✓ ").append(functionId).append("  通过 (").append(cost).append("ms)")
                    if (result.redacted) append("  [返回值已脱敏]")
                    append('\n').append(render(result.value))
                }
                is GuardResult.Err ->
                    "✗ $functionId  拦截 (${cost}ms)\n${result.error.toJson()}"
            }
            runOnUiThread { append(text) }
        }
    }

    private fun describe(args: Bundle): String {
        if (args.isEmpty) return "{}"
        return args.keySet().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            "$key=${args.get(key)}"
        }
    }

    private fun render(value: Any?): String = when (value) {
        null -> "  (null)"
        is List<*> -> if (value.isEmpty()) {
            "  (空列表)"
        } else {
            value.joinToString("\n") { "  · $it" }
        }
        else -> value.toString().lines().joinToString("\n") { "  $it" }
    }

    // -------------------------------------------------------------- 审计面板

    private fun showAudit() {
        val records = (GuardConfig.auditSink as? RingBufferAuditSink)?.snapshot(20).orEmpty()
        auditView.text = if (records.isEmpty()) {
            "审计：暂无记录"
        } else {
            buildString {
                append("审计：最近 ").append(records.size).append(" 条\n")
                append(records.joinToString("\n") { formatRecord(it) })
            }
        }
    }

    private fun formatRecord(record: AuditRecord): String = buildString {
        append(timeFormat.format(Date(record.timestampMillis)))
        append("  ")
        append(record.functionId.padEnd(14))
        append(" ")
        append(record.decision.name)
        if (record.piiRedacted > 0) append("  脱敏 ").append(record.piiRedacted)
        if (record.degraded) append("  校验器未生效")
        append("  ").append(record.durationMillis).append("ms")
    }

    private fun resetData() {
        NoteRepository.reset()
        append("已重置演示数据（3 条笔记）。注意：同意记录仍在有效期内。")
    }

    private fun append(text: String) {
        lines.addLast(text)
        while (lines.size > 200) lines.removeFirst()
        logView.text = lines.joinToString("\n")
    }
}
