package io.github.yangyang2857.afguard.internal

import io.github.yangyang2857.afguard.CallerIdentity
import io.github.yangyang2857.afguard.GuardErrorCode
import io.github.yangyang2857.afguard.Policy
import io.github.yangyang2857.afguard.Risk

/** 策略引擎的判定结果。 */
internal sealed class Decision {
    data object Allow : Decision()
    data object NeedsConfirmation : Decision()
    data class Deny(val code: GuardErrorCode) : Decision()
}

/**
 * 授权状态存储。
 *
 * 默认实现是内存态（进程重启即失效），因为"重启后重新问一次用户"是更安全的方向。
 * 宿主可以注入基于 DataStore / Room 的实现来做持久化。
 */
interface ConsentStore {
    fun hasConsent(functionId: String, callerPackage: String, nowMillis: Long): Boolean
    fun grant(functionId: String, callerPackage: String, expiresAtMillis: Long)
    fun revoke(functionId: String, callerPackage: String)
    fun clear()
}

/** 默认实现：进程内有效。 */
class InMemoryConsentStore : ConsentStore {
    private val grants = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(functionId: String, callerPackage: String) = "$functionId|$callerPackage"

    override fun hasConsent(functionId: String, callerPackage: String, nowMillis: Long): Boolean {
        val expiresAt = grants[key(functionId, callerPackage)] ?: return false
        if (expiresAt <= nowMillis) {
            grants.remove(key(functionId, callerPackage))
            return false
        }
        return true
    }

    override fun grant(functionId: String, callerPackage: String, expiresAtMillis: Long) {
        grants[key(functionId, callerPackage)] = expiresAtMillis
    }

    override fun revoke(functionId: String, callerPackage: String) {
        grants.remove(key(functionId, callerPackage))
    }

    override fun clear() = grants.clear()
}

/**
 * 策略引擎：把 [Policy] + 调用方 + 已授予的同意状态，收敛成一个 [Decision]。
 *
 * 这里是"per-function 授权"的落地点，也就是平台目前缺失的那一环。
 */
internal class PolicyEngine(
    private val consentStore: ConsentStore,
    private val clock: () -> Long
) {
    fun evaluate(
        functionId: String,
        policy: Policy,
        risk: Risk,
        caller: CallerIdentity
    ): Decision = when (policy) {
        Policy.ALWAYS_DENY -> Decision.Deny(GuardErrorCode.FUNCTION_BLOCKED)

        Policy.ALLOW -> Decision.Allow

        Policy.CONFIRM_EACH -> Decision.NeedsConfirmation

        Policy.CONFIRM_ONCE -> {
            // CRITICAL 风险永远不记"长期同意"，哪怕策略写着 CONFIRM_ONCE。
            // 这是刻意的不对称：越危险的动作，越不允许被一次点击永久放行。
            if (risk == Risk.CRITICAL) {
                Decision.NeedsConfirmation
            } else if (consentStore.hasConsent(functionId, caller.packageName, clock())) {
                Decision.Allow
            } else {
                Decision.NeedsConfirmation
            }
        }
    }
}
