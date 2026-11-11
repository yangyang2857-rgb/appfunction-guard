package io.github.yangyang2857.afguard.internal

import android.os.Bundle
import io.github.yangyang2857.afguard.GuardError

/**
 * 参数校验器。由 guard-ksp 在编译期生成实现。
 *
 * 契约：返回 null 表示通过；返回 [GuardError] 表示拦截。
 */
fun interface FunctionValidator {
    fun validate(functionId: String, args: Bundle): GuardError?
}

/**
 * 校验器注册表。
 *
 * 加载顺序：
 * 1. 如果宿主通过 [setOverride] 显式注入，用注入的；
 * 2. 否则尝试反射加载 KSP 生成的 `AfGuardGeneratedValidators`；
 * 3. 都拿不到则降级为 [PassThrough]（只做策略与限流，不做参数校验），
 *    并在审计里记一条 degraded 事件。
 *
 * 反射只发生一次，懒加载后缓存实例，不在每次调用路径上。
 */
object ValidatorRegistry {

    const val GENERATED_CLASS = "io.github.yangyang2857.afguard.generated.AfGuardGeneratedValidators"

    @Volatile
    private var override: FunctionValidator? = null

    @Volatile
    private var resolved: FunctionValidator? = null

    @Volatile
    private var degraded: Boolean = false

    /** 宿主可显式指定，优先级最高。 */
    fun setOverride(validator: FunctionValidator?) {
        override = validator
        resolved = null
        degraded = false
    }

    fun isDegraded(): Boolean {
        ensureResolved()
        return degraded
    }

    fun validate(functionId: String, args: Bundle): GuardError? {
        ensureResolved()
        return (resolved ?: return null).validate(functionId, args)
    }

    private fun ensureResolved() {
        if (resolved != null || degraded) return
        synchronized(this) {
            if (resolved != null || degraded) return
            override?.let {
                resolved = it
                return
            }
            resolved = loadGenerated()
            if (resolved == null) degraded = true
        }
    }

    private fun loadGenerated(): FunctionValidator? = try {
        val clazz = Class.forName(GENERATED_CLASS)
        val instance = clazz.getField("INSTANCE").get(null)
        instance as? FunctionValidator
    } catch (t: Throwable) {
        // 没接 KSP 是合法用法，静默降级
        null
    }
}
