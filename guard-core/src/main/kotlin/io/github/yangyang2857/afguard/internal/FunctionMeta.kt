package io.github.yangyang2857.afguard.internal

import io.github.yangyang2857.afguard.GuardError
import io.github.yangyang2857.afguard.Policy
import io.github.yangyang2857.afguard.Risk
import io.github.yangyang2857.afguard.RateScope

/** 限流规格。用 data class 而非直接传注解实例，避免注解实例化在跨版本上的坑。 */
data class RateSpec(
    val calls: Int,
    val perSeconds: Int,
    val scope: RateScope,
    val burst: Int
)

/** 函数元数据，由 KSP 在编译期从 [io.github.yangyang2857.afguard.Guard] 等注解提取。 */
data class FunctionMeta(
    val functionId: String,
    val policy: Policy = Policy.CONFIRM_ONCE,
    val risk: Risk = Risk.MEDIUM,
    val redactOutput: Boolean = false,
    val idempotent: Boolean = false,
    val rateSpec: RateSpec? = null
)

/**
 * KSP 生成类需要实现的接口：一次反射拿到"校验 + 元数据"两件事。
 */
interface GeneratedGuardTable : FunctionValidator {
    override fun validate(functionId: String, args: android.os.Bundle): GuardError?
    fun metadataOf(functionId: String): FunctionMeta?
}

/**
 * 元数据注册表。与 ValidatorRegistry 平行，共用同一个生成类。
 */
object MetadataRegistry {

    @Volatile
    private var table: GeneratedGuardTable? = null

    @Volatile
    private var loaded = false

    fun of(functionId: String): FunctionMeta? {
        ensureLoaded()
        return table?.metadataOf(functionId)
    }

    /**
     * 显式指定元数据表，优先级高于反射加载。
     *
     * 两个用途：宿主要手工声明策略（不想走 KSP）时；以及单测里注入固定元数据。
     * 传 null 会把状态复位，下次调用重新走反射。
     */
    fun setOverride(generated: GeneratedGuardTable?) {
        synchronized(this) {
            table = generated
            loaded = generated != null
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            table = try {
                val clazz = Class.forName(ValidatorRegistry.GENERATED_CLASS)
                clazz.getField("INSTANCE").get(null) as? GeneratedGuardTable
            } catch (t: Throwable) {
                null
            }
            loaded = true
        }
    }
}
