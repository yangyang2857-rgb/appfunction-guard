package io.github.yangyang2857.afguard

import android.os.Bundle
import io.github.yangyang2857.afguard.internal.AuditLog
import io.github.yangyang2857.afguard.internal.AuditRecord
import io.github.yangyang2857.afguard.internal.Decision
import io.github.yangyang2857.afguard.internal.DecisionKind
import io.github.yangyang2857.afguard.internal.FunctionMeta
import io.github.yangyang2857.afguard.internal.MetadataRegistry
import io.github.yangyang2857.afguard.internal.PolicyEngine
import io.github.yangyang2857.afguard.internal.RateLimiter
import io.github.yangyang2857.afguard.internal.ValidatorRegistry
import java.security.MessageDigest

/**
 * 拦截器入口。
 *
 * 命名说明：注解 [Guard] 占用了 `Guard` 这个名字，所以本对象叫 `AfGuard`，
 * 调用写作 `AfGuard.intercept(functionId, args) { ... }`。
 *
 * 刻意不继承任何平台类、不 import `android.app.appfunctions.*`。
 * 原因：那套 API 还在 alpha，删过组件。把它挡在业务侧，库本身就不会随平台抖动。
 * 平台怎么变，只改你自己 Service 里那一行调用。
 *
 * 典型用法：
 * ```kotlin
 * override fun onExecuteFunction(functionId: String, args: Bundle, ...): Bundle {
 *     val result = AfGuard.intercept(functionId, args) { call ->
 *         when (call.functionId) {
 *             "listNotes" -> repo.recent(call.intOr("limit", 20))
 *             "deleteNote" -> repo.delete(call.requireString("id"))
 *             else -> throw NoSuchFunctionException(call.functionId)
 *         }
 *     }
 *     return BundleCodec.toBundle(result)
 * }
 * ```
 */
object AfGuard {

    private val rateLimiter = RateLimiter { GuardConfig.clock() }

    private fun policyEngine() = PolicyEngine(GuardConfig.consentStore, GuardConfig.clock())

    /**
     * 执行一次受保护的调用。
     *
     * 顺序：白名单 → 策略 → 限流 → 参数校验 → 用户确认 → 执行 → 脱敏 → 审计。
     *
     * 注意顺序的意义：
     * - 策略在限流之前，保证 ALWAYS_DENY 的函数不会消耗配额；
     * - 限流在确认之前，避免"已经被限流的调用"还去骚扰用户点弹窗；
     * - 校验在确认之前，避免用户为一次注定失败的调用点头。
     */
    @JvmStatic
    fun intercept(
        functionId: String,
        args: Bundle,
        block: (GuardedCall) -> Any?
    ): GuardResult {
        val startedAt = GuardConfig.clock()
        val caller = resolveCaller(functionId)

        // 总开关关闭：完全透传，不审计（用于线上止血）
        if (!GuardConfig.enabled) {
            return try {
                GuardResult.Ok(block(GuardedCall(functionId, args, caller)))
            } catch (t: Throwable) {
                GuardResult.Err(GuardError.upstream(functionId, t))
            }
        }

        val meta = MetadataRegistry.of(functionId)
            ?: FunctionMeta(functionId, policy = GuardConfig.defaultPolicy)

        // 1. 调用方白名单
        if (!GuardConfig.callerAllowList.isAllowed(caller)) {
            return reject(
                functionId, caller, startedAt, DecisionKind.DENIED_CALLER,
                GuardError(
                    GuardErrorCode.NOT_AUTHORIZED, functionId,
                    "Caller ${caller.packageName} is not in the allow list"
                )
            )
        }

        // 2. 策略判定
        val decision = policyEngine().evaluate(functionId, meta.policy, meta.risk, caller)
        if (decision is Decision.Deny) {
            return reject(
                functionId, caller, startedAt, DecisionKind.DENIED_POLICY,
                GuardError(decision.code, functionId, "'$functionId' is not available to agents")
            )
        }

        // 3. 限流
        val spec = meta.rateSpec
        if (spec != null) {
            val wait = rateLimiter.tryAcquire(
                functionId,
                RateLimit(spec.calls, spec.perSeconds, spec.scope, spec.burst),
                caller.bucketKey
            )
            if (wait != null) {
                return reject(
                    functionId, caller, startedAt, DecisionKind.RATE_LIMITED,
                    GuardError.rateLimited(functionId, wait)
                )
            }
        }

        // 4. 参数校验
        ValidatorRegistry.validate(functionId, args)?.let { err ->
            return reject(
                functionId, caller, startedAt, DecisionKind.INVALID_ARGUMENT,
                err.copy(function = functionId)
            )
        }

        // 5. 用户确认
        if (decision is Decision.NeedsConfirmation) {
            val granted = requestConfirmation(functionId, meta, caller)
            if (!granted) {
                return reject(
                    functionId, caller, startedAt, DecisionKind.DENIED_USER,
                    GuardError.userDenied(functionId)
                )
            }
        }

        // 6. 执行
        val call = GuardedCall(functionId, args, caller)
        val raw = try {
            block(call)
        } catch (missing: MissingArgumentException) {
            return reject(
                functionId, caller, startedAt, DecisionKind.INVALID_ARGUMENT,
                GuardError.missing(functionId, missing.key)
            )
        } catch (t: Throwable) {
            audit(functionId, caller, startedAt, DecisionKind.UPSTREAM_ERROR, digest(args), 0, t.javaClass.simpleName)
            return GuardResult.Err(GuardError.upstream(functionId, t))
        }

        // 7. 脱敏
        var piiHits = 0
        val output = if (meta.redactOutput) {
            val (value, hits) = redactDeep(raw)
            piiHits = hits
            value
        } else {
            raw
        }

        // 8. 审计
        audit(functionId, caller, startedAt, DecisionKind.ALLOWED, digest(args), piiHits, null)

        return GuardResult.Ok(output, redacted = piiHits > 0)
    }

    // -----------------------------------------------------------------------

    private fun resolveCaller(functionId: String): CallerIdentity = try {
        GuardConfig.callerResolver.resolve(functionId)
    } catch (t: Throwable) {
        CallerIdentity(packageName = "unknown", uid = -1, isPrivileged = false)
    }

    private fun requestConfirmation(
        functionId: String,
        meta: FunctionMeta,
        caller: CallerIdentity
    ): Boolean {
        val handler = GuardConfig.confirmationHandler ?: return false
        val request = ConfirmRequest(
            appName = GuardConfig.appLabel(),
            callerPackage = caller.packageName,
            functionId = functionId,
            risk = meta.risk,
            sensitiveKinds = emptySet(),
            timeoutMillis = GuardConfig.confirmationTimeoutMillis
        )
        val granted = try {
            handler.confirm(request)
        } catch (t: Throwable) {
            false
        }
        if (granted && meta.policy == Policy.CONFIRM_ONCE && meta.risk != Risk.CRITICAL) {
            GuardConfig.consentStore.grant(
                functionId,
                caller.packageName,
                GuardConfig.clock() + GuardConfig.consentTtlMillis
            )
        }
        return granted
    }

    private fun reject(
        functionId: String,
        caller: CallerIdentity,
        startedAt: Long,
        kind: DecisionKind,
        error: GuardError
    ): GuardResult {
        audit(functionId, caller, startedAt, kind, null, 0, error.code.name)
        return GuardResult.Err(error)
    }

    private fun audit(
        functionId: String,
        caller: CallerIdentity,
        startedAt: Long,
        kind: DecisionKind,
        argsDigest: String?,
        pii: Int,
        errorCode: String?
    ) {
        AuditLog.emit(
            AuditRecord(
                timestampMillis = System.currentTimeMillis(),
                functionId = functionId,
                callerPackage = caller.packageName,
                callerUid = caller.uid,
                decision = kind,
                durationMillis = GuardConfig.clock() - startedAt,
                argsDigest = argsDigest,
                piiRedacted = pii,
                errorCode = errorCode,
                degraded = ValidatorRegistry.isDegraded()
            )
        )
    }

    /** 参数指纹：只保留结构，不保留原文。 */
    private fun digest(args: Bundle): String {
        val sb = StringBuilder()
        for (key in args.keySet().sorted()) {
            sb.append(key).append(':').append(args.get(key)?.let { it.javaClass.simpleName }).append(';')
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 保持结构地脱敏。只替换字符串值，不把 Bundle 拍平成一段文本。 */
    private fun redactDeep(value: Any?): Pair<Any?, Int> = when (value) {
        null -> null to 0
        is String -> {
            val o = GuardConfig.redactor.redactText(value)
            o.text to o.hits
        }
        is Bundle -> {
            val out = Bundle(value)
            var hits = 0
            for (key in value.keySet()) {
                if (value.get(key) is String) {
                    val o = GuardConfig.redactor.redactText(value.getString(key)!!)
                    hits += o.hits
                    out.putString(key, o.text)
                }
            }
            out to hits
        }
        is List<*> -> {
            var hits = 0
            val mapped = value.map { item ->
                val (v, h) = redactDeep(item)
                hits += h
                v
            }
            mapped to hits
        }
        else -> value to GuardConfig.redactor.redactText(value.toString()).hits
    }
}
