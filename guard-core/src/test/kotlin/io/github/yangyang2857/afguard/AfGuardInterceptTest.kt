package io.github.yangyang2857.afguard

import android.os.Bundle
import io.github.yangyang2857.afguard.audit.DecisionKind
import io.github.yangyang2857.afguard.audit.RingBufferAuditSink
import io.github.yangyang2857.afguard.internal.FunctionMeta
import io.github.yangyang2857.afguard.internal.GeneratedGuardTable
import io.github.yangyang2857.afguard.internal.InMemoryConsentStore
import io.github.yangyang2857.afguard.internal.MetadataRegistry
import io.github.yangyang2857.afguard.internal.RateSpec
import io.github.yangyang2857.afguard.internal.Redactor
import io.github.yangyang2857.afguard.internal.ValidatorRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 拦截链的行为测试。
 *
 * 为什么要有这一层：真机验收清单（docs/DEVICE_TEST.md 阶段 A）里 10 项检查，
 * 大部分本质上是"给定策略与参数，拦截链应该返回什么"。这些判定不该依赖某台手机，
 * 也不该每次改代码都靠人点按钮回归 —— 所以在这里用固定时钟 + 假元数据表钉死。
 *
 * 仍然只能靠真机验证的只剩三件：确认弹窗长什么样、Activity 销毁后的降级、
 * 杀进程后环形缓冲是否清空（见清单 A3 视觉部分 / A9 / A11）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AfGuardInterceptTest {

    private lateinit var sink: RingBufferAuditSink
    private lateinit var table: FakeTable

    private var now = 1_000L
    private var callerNow = CallerIdentity(packageName = "com.example.agent", uid = 10_200)

    @Before
    fun setUp() {
        now = 1_000L
        callerNow = CallerIdentity(packageName = "com.example.agent", uid = 10_200)
        sink = RingBufferAuditSink()
        table = FakeTable()

        GuardConfig.enabled = true
        GuardConfig.defaultPolicy = Policy.CONFIRM_ONCE
        GuardConfig.consentTtlMillis = 7L * 24 * 60 * 60 * 1000
        GuardConfig.confirmationTimeoutMillis = 15_000L
        GuardConfig.confirmationHandler = null
        GuardConfig.callerAllowList = CallerAllowList.DISABLED
        GuardConfig.callerResolver = CallerResolver { callerNow }
        GuardConfig.consentStore = InMemoryConsentStore()
        GuardConfig.redactor = Redactor.default()
        GuardConfig.auditSink = sink
        GuardConfig.clock = { now }
        GuardConfig.apply(sink)

        MetadataRegistry.setOverride(table)
        ValidatorRegistry.setOverride(table)
    }

    @After
    fun tearDown() {
        MetadataRegistry.setOverride(null)
        ValidatorRegistry.setOverride(null)
        GuardConfig.enabled = true
        GuardConfig.confirmationHandler = null
        GuardConfig.callerAllowList = CallerAllowList.DISABLED
        GuardConfig.clock = { android.os.SystemClock.elapsedRealtime() }
    }

    // ---------------------------------------------------------------- 策略

    @Test
    fun `ALLOW 直接执行并留下 ALLOWED 审计`() {
        table.put(FunctionMeta("listNotes", policy = Policy.ALLOW))

        val result = AfGuard.intercept("listNotes", Bundle()) { "notes" }

        val ok = result as GuardResult.Ok
        assertEquals("notes", ok.value)
        assertFalse(ok.redacted)

        val record = sink.snapshot().first()
        assertEquals(DecisionKind.ALLOWED, record.decision)
        assertEquals("com.example.agent", record.callerPackage)
        assertEquals(10_200, record.callerUid)
        assertNotNull(record.argsDigest)
        assertFalse(record.degraded)
    }

    @Test
    fun `ALWAYS_DENY 不执行业务且不可重试`() {
        table.put(FunctionMeta("wipeAllNotes", policy = Policy.ALWAYS_DENY, risk = Risk.HIGH))
        var executed = false

        val result = AfGuard.intercept("wipeAllNotes", Bundle()) { executed = true }

        val error = (result as GuardResult.Err).error
        assertEquals(GuardErrorCode.FUNCTION_BLOCKED, error.code)
        assertFalse(error.retryable)
        assertFalse(executed)
        assertEquals(DecisionKind.DENIED_POLICY, sink.snapshot().first().decision)
        assertTrue(error.toJson().contains("\"retryable\":false"))
    }

    @Test
    fun `策略拒绝发生在限流之前 不消耗配额`() {
        table.put(
            FunctionMeta(
                "hardDeny", policy = Policy.ALWAYS_DENY,
                rateSpec = RateSpec(calls = 1, perSeconds = 60, scope = RateScope.PER_CALLER, burst = 1)
            )
        )

        repeat(3) {
            val result = AfGuard.intercept("hardDeny", Bundle()) { "should not run" }
            assertEquals(GuardErrorCode.FUNCTION_BLOCKED, (result as GuardResult.Err).error.code)
        }
    }

    @Test
    fun `CONFIRM_EACH 同意后执行`() {
        table.put(FunctionMeta("deleteNote", policy = Policy.CONFIRM_EACH, risk = Risk.HIGH))
        var prompts = 0
        GuardConfig.confirmationHandler = ConfirmationHandler {
            prompts++
            true
        }

        val result = AfGuard.intercept("deleteNote", Bundle()) { "deleted" }

        assertEquals("deleted", (result as GuardResult.Ok).value)
        assertEquals(1, prompts)
    }

    @Test
    fun `CONFIRM_EACH 拒绝后返回 USER_DENIED 且不执行`() {
        table.put(FunctionMeta("deleteNote", policy = Policy.CONFIRM_EACH, risk = Risk.HIGH))
        var executed = false
        GuardConfig.confirmationHandler = ConfirmationHandler { false }

        val result = AfGuard.intercept("deleteNote", Bundle()) { executed = true }

        val error = (result as GuardResult.Err).error
        assertEquals(GuardErrorCode.USER_DENIED, error.code)
        assertFalse(error.retryable)
        assertFalse(executed)
        assertEquals(DecisionKind.DENIED_USER, sink.snapshot().first().decision)
    }

    @Test
    fun `没有确认处理器时默认拒绝 而不是放行`() {
        table.put(FunctionMeta("deleteNote", policy = Policy.CONFIRM_EACH, risk = Risk.HIGH))
        var executed = false
        GuardConfig.confirmationHandler = null

        val result = AfGuard.intercept("deleteNote", Bundle()) { executed = true }

        assertEquals(GuardErrorCode.USER_DENIED, (result as GuardResult.Err).error.code)
        assertFalse(executed)
    }

    @Test
    fun `CONFIRM_ONCE 在有效期内不重复弹窗`() {
        table.put(FunctionMeta("exportNotes", policy = Policy.CONFIRM_ONCE, risk = Risk.MEDIUM))
        var prompts = 0
        GuardConfig.confirmationHandler = ConfirmationHandler {
            prompts++
            true
        }

        AfGuard.intercept("exportNotes", Bundle()) { "v1" }
        val second = AfGuard.intercept("exportNotes", Bundle()) { "v2" }

        assertEquals("v2", (second as GuardResult.Ok).value)
        assertEquals(1, prompts)
    }

    @Test
    fun `CONFIRM_ONCE 的同意到期后重新弹窗`() {
        table.put(FunctionMeta("exportNotes", policy = Policy.CONFIRM_ONCE, risk = Risk.MEDIUM))
        var prompts = 0
        GuardConfig.confirmationHandler = ConfirmationHandler {
            prompts++
            true
        }

        AfGuard.intercept("exportNotes", Bundle()) { "v1" }
        now += GuardConfig.consentTtlMillis + 1
        AfGuard.intercept("exportNotes", Bundle()) { "v2" }

        assertEquals(2, prompts)
    }

    @Test
    fun `CRITICAL 风险即使写了 CONFIRM_ONCE 也每次都问`() {
        table.put(FunctionMeta("transferMoney", policy = Policy.CONFIRM_ONCE, risk = Risk.CRITICAL))
        var prompts = 0
        GuardConfig.confirmationHandler = ConfirmationHandler {
            prompts++
            true
        }

        AfGuard.intercept("transferMoney", Bundle()) { "ok" }
        AfGuard.intercept("transferMoney", Bundle()) { "ok" }

        assertEquals(2, prompts)
    }

    // ---------------------------------------------------------------- 限流

    @Test
    fun `令牌桶用满后返回 RATE_LIMITED 并给出等待时间`() {
        table.put(
            FunctionMeta(
                "rateLimitedFn", policy = Policy.ALLOW,
                rateSpec = RateSpec(calls = 5, perSeconds = 60, scope = RateScope.PER_CALLER, burst = 5)
            )
        )

        repeat(5) {
            assertTrue(AfGuard.intercept("rateLimitedFn", Bundle()) { "ok" } is GuardResult.Ok)
        }
        val blocked = AfGuard.intercept("rateLimitedFn", Bundle()) { "ok" }

        val error = (blocked as GuardResult.Err).error
        assertEquals(GuardErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.retryAfterMs != null && error.retryAfterMs!! > 0)
        assertTrue(error.retryable)
        assertEquals(DecisionKind.RATE_LIMITED, sink.snapshot().first().decision)
    }

    @Test
    fun `补充令牌后放行 说明限流按时间恢复`() {
        table.put(
            FunctionMeta(
                "refillFn", policy = Policy.ALLOW,
                rateSpec = RateSpec(calls = 1, perSeconds = 60, scope = RateScope.PER_CALLER, burst = 1)
            )
        )

        assertTrue(AfGuard.intercept("refillFn", Bundle()) { "ok" } is GuardResult.Ok)
        assertTrue(AfGuard.intercept("refillFn", Bundle()) { "ok" } is GuardResult.Err)

        now += 61_000L
        assertTrue(AfGuard.intercept("refillFn", Bundle()) { "ok" } is GuardResult.Ok)
    }

    @Test
    fun `PER_CALLER 限流互不牵连`() {
        table.put(
            FunctionMeta(
                "perCallerFn", policy = Policy.ALLOW,
                rateSpec = RateSpec(calls = 1, perSeconds = 60, scope = RateScope.PER_CALLER, burst = 1)
            )
        )

        assertTrue(AfGuard.intercept("perCallerFn", Bundle()) { "ok" } is GuardResult.Ok)
        assertTrue(AfGuard.intercept("perCallerFn", Bundle()) { "ok" } is GuardResult.Err)

        callerNow = CallerIdentity(packageName = "com.example.other", uid = 10_300)
        assertTrue(AfGuard.intercept("perCallerFn", Bundle()) { "ok" } is GuardResult.Ok)
    }

    // ---------------------------------------------------------------- 校验

    @Test
    fun `校验失败时业务不执行`() {
        table.put(FunctionMeta("searchNotes", policy = Policy.ALLOW))
        table.reject(
            "searchNotes",
            GuardError.invalidArgument("searchNotes", "keyword", "keyword must not be empty", "length 1..64")
        )
        var executed = false

        val result = AfGuard.intercept("searchNotes", Bundle()) { executed = true }

        val error = (result as GuardResult.Err).error
        assertEquals(GuardErrorCode.INVALID_ARGUMENT, error.code)
        assertEquals("keyword", error.field)
        assertTrue(error.retryable)
        assertFalse(executed)
        assertEquals(DecisionKind.INVALID_ARGUMENT, sink.snapshot().first().decision)
    }

    @Test
    fun `缺参数抛出 MISSING_ARGUMENT 而不是崩到宿主`() {
        table.put(FunctionMeta("deleteNote", policy = Policy.ALLOW))

        val result = AfGuard.intercept("deleteNote", Bundle()) { call ->
            call.requireString("id")
        }

        val error = (result as GuardResult.Err).error
        assertEquals(GuardErrorCode.MISSING_ARGUMENT, error.code)
        assertEquals("id", error.field)
    }

    @Test
    fun `业务异常收敛成 UPSTREAM_ERROR`() {
        table.put(FunctionMeta("boom", policy = Policy.ALLOW))

        val result = AfGuard.intercept("boom", Bundle()) { throw IllegalStateException("db gone") }

        val error = (result as GuardResult.Err).error
        assertEquals(GuardErrorCode.UPSTREAM_ERROR, error.code)
        assertFalse(error.retryable)
        assertFalse(error.message.contains("db gone"))
        assertEquals(DecisionKind.UPSTREAM_ERROR, sink.snapshot().first().decision)
    }

    // ---------------------------------------------------------------- 脱敏与审计

    @Test
    fun `返回值里的手机号在离开前被替换`() {
        table.put(FunctionMeta("exportNotes", policy = Policy.ALLOW, redactOutput = true))

        val result = AfGuard.intercept("exportNotes", Bundle()) { "联系我 13800138000" }

        val ok = result as GuardResult.Ok
        assertTrue(ok.redacted)
        assertFalse((ok.value as String).contains("13800138000"))
        assertTrue((ok.value as String).contains("[REDACTED:PHONE]"))

        val record = sink.snapshot().first()
        assertEquals(1, record.piiRedacted)
    }

    @Test
    fun `审计只记参数指纹 不记原文`() {
        table.put(FunctionMeta("deleteNote", policy = Policy.ALLOW))

        AfGuard.intercept("deleteNote", Bundle().apply { putString("id", "secret-token-42") }) { "ok" }

        val record = sink.snapshot().first()
        assertNotNull(record.argsDigest)
        assertFalse(record.argsDigest!!.contains("secret-token-42"))
        assertFalse(record.toString().contains("secret-token-42"))
    }

    @Test
    fun `关闭总开关后完全透传且不审计`() {
        table.put(FunctionMeta("wipeAllNotes", policy = Policy.ALWAYS_DENY, risk = Risk.HIGH))
        GuardConfig.enabled = false

        val result = AfGuard.intercept("wipeAllNotes", Bundle()) { "ran anyway" }

        assertEquals("ran anyway", (result as GuardResult.Ok).value)
        assertEquals(0, sink.snapshot().size)
    }

    // ---------------------------------------------------------------- 调用方白名单

    @Test
    fun `STRICT 白名单外的调用方被拒`() {
        table.put(FunctionMeta("listNotes", policy = Policy.ALLOW))
        GuardConfig.callerAllowList = CallerAllowList(
            mode = CallerAllowList.Mode.STRICT,
            packages = setOf("com.example.trusted")
        )
        var executed = false

        val result = AfGuard.intercept("listNotes", Bundle()) { executed = true }

        val error = (result as GuardResult.Err).error
        assertEquals(GuardErrorCode.NOT_AUTHORIZED, error.code)
        assertFalse(error.retryable)
        assertFalse(executed)
        assertEquals(DecisionKind.DENIED_CALLER, sink.snapshot().first().decision)
    }

    @Test
    fun `STRICT 白名单内放行 特权调用方也放行`() {
        table.put(FunctionMeta("listNotes", policy = Policy.ALLOW))
        GuardConfig.callerAllowList = CallerAllowList(
            mode = CallerAllowList.Mode.STRICT,
            packages = setOf("com.example.agent")
        )

        assertTrue(AfGuard.intercept("listNotes", Bundle()) { "ok" } is GuardResult.Ok)

        callerNow = CallerIdentity(packageName = "com.example.stranger", uid = 10_500, isPrivileged = true)
        assertTrue(AfGuard.intercept("listNotes", Bundle()) { "ok" } is GuardResult.Ok)
    }

    // ---------------------------------------------------------------- 替身

    /**
     * 假的 KSP 生成表：同时充当元数据表和校验器，行为和生成类一致。
     * 用它可以精确构造"某函数带了什么策略/限流/校验"，不受 sample 代码影响。
     */
    private class FakeTable : GeneratedGuardTable {
        private val metas = mutableMapOf<String, FunctionMeta>()
        private val errors = mutableMapOf<String, GuardError>()

        fun put(meta: FunctionMeta) {
            metas[meta.functionId] = meta
        }

        fun reject(functionId: String, error: GuardError) {
            errors[functionId] = error
        }

        override fun metadataOf(functionId: String): FunctionMeta? = metas[functionId]

        override fun validate(functionId: String, args: Bundle): GuardError? = errors[functionId]
    }
}
