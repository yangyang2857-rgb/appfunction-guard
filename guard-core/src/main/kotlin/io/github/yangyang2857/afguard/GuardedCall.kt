package io.github.yangyang2857.afguard

import android.os.Bundle

/**
 * 传给业务代码的调用上下文。
 *
 * 与直接拿 Bundle 的区别：
 * 1. 必填参数缺失时直接抛 [MissingArgumentException]，由 Guard 转成结构化错误；
 * 2. 提供已解析好的调用方身份，业务侧可据此做更细的决策；
 * 3. 禁止业务代码直接读原始 Bundle —— 因为它已经被校验器检查过一轮了。
 */
class GuardedCall internal constructor(
    val functionId: String,
    private val bundle: Bundle,
    val caller: CallerIdentity
) {
    fun requireString(key: String): String =
        bundle.getString(key) ?: throw MissingArgumentException(key)

    fun stringOrNull(key: String): String? = bundle.getString(key)

    fun requireInt(key: String): Int =
        if (bundle.containsKey(key)) bundle.getInt(key) else throw MissingArgumentException(key)

    fun intOr(key: String, fallback: Int): Int =
        if (bundle.containsKey(key)) bundle.getInt(key) else fallback

    fun requireLong(key: String): Long =
        if (bundle.containsKey(key)) bundle.getLong(key) else throw MissingArgumentException(key)

    fun requireBoolean(key: String): Boolean =
        if (bundle.containsKey(key)) bundle.getBoolean(key) else throw MissingArgumentException(key)

    fun stringList(key: String): List<String> =
        bundle.getStringArrayList(key)?.toList() ?: emptyList()

    /** 是否存在某参数。 */
    fun has(key: String): Boolean = bundle.containsKey(key)
}

/** 由 [GuardedCall] 抛出，Guard 捕获后转为 MISSING_ARGUMENT。 */
class MissingArgumentException(val key: String) : IllegalArgumentException("Missing required argument: $key")
