package io.github.yangyang2857.afguard

import android.content.Context
import android.os.SystemClock
import io.github.yangyang2857.afguard.internal.AuditLog
import io.github.yangyang2857.afguard.internal.AuditSink
import io.github.yangyang2857.afguard.internal.ConsentStore
import io.github.yangyang2857.afguard.internal.InMemoryConsentStore
import io.github.yangyang2857.afguard.internal.NoopAuditSink
import io.github.yangyang2857.afguard.internal.Redactor
import io.github.yangyang2857.afguard.internal.RingBufferAuditSink

/**
 * 用户确认请求。传给宿主的 [ConfirmationHandler]。
 *
 * 刻意只给这些字段：够 UI 把"谁在调、调什么、会碰到什么数据"讲清楚，
 * 又不会把参数原文扩散到 UI 层日志里。
 */
data class ConfirmRequest(
    val appName: String,
    val callerPackage: String,
    val functionId: String,
    val risk: Risk,
    val sensitiveKinds: Set<PiiKind>,
    val timeoutMillis: Long
)

/**
 * 确认处理器。由宿主实现，负责真正弹一个界面/通知/悬浮窗。
 *
 * 约定：
 * - 返回 true 表示用户同意；
 * - 必须在 [ConfirmRequest.timeoutMillis] 内返回，否则 Guard 按超时处理（不阻塞 Agent 无限等待）；
 * - 不要在这个回调里再调 Guard，会死锁。
 */
fun interface ConfirmationHandler {
    fun confirm(request: ConfirmRequest): Boolean
}

/**
 * 全局配置。故意做成可变单例，降低接入成本：
 * 一个 Application.onCreate 里配完就结束，不需要依赖注入框架。
 */
object GuardConfig {

    /** 总开关。关掉后完全回到原生行为，用于线上出问题时紧急止血。 */
    @Volatile
    var enabled: Boolean = true

    /** 未标注 @Guard 的函数走这个策略。默认保守，不是默认放行。 */
    @Volatile
    var defaultPolicy: Policy = Policy.CONFIRM_ONCE

    /** CONFIRM_ONCE 的有效期。默认 7 天。 */
    @Volatile
    var consentTtlMillis: Long = 7L * 24 * 60 * 60 * 1000

    /** 确认等待上限。默认 15 秒。超时按拒绝处理，避免 Agent 挂死。 */
    @Volatile
    var confirmationTimeoutMillis: Long = 15_000L

    /** 未配置确认处理器时，需要确认的调用一律拒绝（宁可不做，也不悄悄放行）。 */
    @Volatile
    var confirmationHandler: ConfirmationHandler? = null

    /** 清单为空时不做限制。 */
    @Volatile
    var callerAllowList: CallerAllowList = CallerAllowList.DISABLED

    /** 宿主可注入自定义解析器；默认用 [DefaultCallerResolver]。 */
    @Volatile
    var callerResolver: CallerResolver = DefaultCallerResolver

    /** 同意状态存储。默认进程内。 */
    @Volatile
    var consentStore: ConsentStore = InMemoryConsentStore()

    /** 返回值的脱敏器。 */
    @Volatile
    var redactor: Redactor = Redactor.default()

    /** 审计出口。默认内存环形缓冲，便于做可视化。 */
    @Volatile
    var auditSink: AuditSink = RingBufferAuditSink()

    /** 时钟。测试时可注入假时钟。 */
    @Volatile
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @Volatile
    private var appContext: Context? = null

    /** 必须在 Application.onCreate 里调用一次。取 App 名与调用方包名都要用它。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        // 审计出口必须在此刻挂上，否则所有 audit() 都会静默丢进空列表。
        AuditLog.install(auditSink)
    }

    internal fun appContextOrNull(): Context? = appContext

    internal fun appLabel(): String = appContext?.let { appName(it) } ?: "This app"

    internal fun appName(context: Context): String = runCatching {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }.getOrDefault(context.packageName)

    /** 应用配置。必须在任何调用发生前完成。 */
    fun apply(sink: AuditSink = auditSink) {
        AuditLog.install(sink)
    }

    /** 关闭全部拦截（仅审计）。线上兜底开关。 */
    fun goSilent() {
        enabled = false
        auditSink = NoopAuditSink
    }
}
