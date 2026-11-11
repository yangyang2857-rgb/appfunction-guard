package io.github.yangyang2857.afguard.internal

import io.github.yangyang2857.afguard.RateLimit
import io.github.yangyang2857.afguard.RateScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * 令牌桶。
 *
 * 为什么用令牌桶而不是固定窗口：
 * 固定窗口在窗口边界会放过双倍流量（59 秒打满 + 61 秒再打满），
 * 对"Agent 短时间疯狂调用"这个真实场景来说，令牌桶的平滑才是对的。
 */
internal class TokenBucket(
    private val capacity: Int,
    private val refillPerMilli: Double,
    private var lastRefill: Long
) {
    private var tokens: Double = capacity.toDouble()

    /**
     * @return null 表示放行；否则返回还需等待的毫秒数。
     */
    @Synchronized
    fun tryAcquire(now: Long): Long? {
        val elapsed = now - lastRefill
        if (elapsed > 0) {
            tokens = min(capacity.toDouble(), tokens + elapsed * refillPerMilli)
            lastRefill = now
        }
        if (tokens >= 1.0) {
            tokens -= 1.0
            return null
        }
        val waitMillis = (1.0 - tokens) / refillPerMilli
        return ceil(waitMillis).toLong().coerceAtLeast(1L)
    }
}

/**
 * 限流器：按函数 × 调用方维度维护令牌桶。
 *
 * 分桶键包含调用方，防止一个 Agent 打满配额导致另一个 Agent 饿死。
 */
internal class RateLimiter(
    private val clock: () -> Long
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    private val specs = ConcurrentHashMap<String, RateLimit>()

    /**
     * @param functionId 函数 ID
     * @param spec 限流声明
     * @param callerKey 调用方分桶键（PER_CALLER 时使用）
     * @return null 表示放行；否则返回建议的重试等待毫秒数。
     */
    fun tryAcquire(functionId: String, spec: RateLimit, callerKey: String): Long? {
        specs[functionId] = spec
        val key = when (spec.scope) {
            RateScope.PER_CALLER -> "$functionId|$callerKey"
            RateScope.GLOBAL -> functionId
        }
        val now = clock()
        val bucket = buckets.getOrPut(key) { newBucket(spec, now) }
        return bucket.tryAcquire(now)
    }

    private fun newBucket(spec: RateLimit, now: Long): TokenBucket {
        val perSeconds = spec.perSeconds.coerceAtLeast(1)
        val calls = spec.calls.coerceAtLeast(1)
        val capacity = maxOf(calls, spec.burst).coerceAtLeast(1)
        val refillPerMilli = calls.toDouble() / (perSeconds * 1000.0)
        return TokenBucket(capacity, refillPerMilli, now)
    }

    /** 用于测试与配置热更新。 */
    fun reset() {
        buckets.clear()
        specs.clear()
    }
}
