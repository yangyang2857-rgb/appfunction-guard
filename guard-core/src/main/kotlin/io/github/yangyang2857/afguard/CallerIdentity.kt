package io.github.yangyang2857.afguard

/**
 * 一次调用的发起方身份。
 *
 * 平台在 alpha 期没有提供"这是哪个 Agent 调的"这种细粒度信息，
 * 因此 Guard 退化为基于 uid / 包名 / 签名指纹的推断，并允许宿主注入自定义实现。
 * 这不是缺陷，而是刻意为之：不同 OEM 的行为差异需要由宿主来兜底。
 */
data class CallerIdentity(
    val packageName: String,
    val uid: Int,
    /** 是否为系统特权 Executor（例如系统内置的 Agent 运行时）。 */
    val isPrivileged: Boolean = false,
    /** 签名摘要，可用于区分"同一包名但被重打包"的情况。 */
    val signatureHash: String? = null
) {
    /** 用于限流分桶与审计的稳定键。 */
    val bucketKey: String get() = "$packageName@$uid"
}

/** 调用方解析策略。 */
fun interface CallerResolver {
    fun resolve(functionId: String): CallerIdentity
}

/**
 * 允许调用的调用方名单。
 *
 * 默认策略是 [Mode.ALLOW_PRIVILEGED_ONLY]？不是。
 * 默认必须放行（否则现有 App 接入即坏），
 * 但一旦宿主设置了名单，就切换到白名单模式。
 */
class CallerAllowList(
    private val mode: Mode = Mode.DISABLED,
    private val packages: Set<String> = emptySet(),
    private val signatureHashes: Set<String> = emptySet()
) {
    enum class Mode {
        /** 不做调用方限制（默认）。仍然记录审计。 */
        DISABLED,

        /** 必须命中包名或签名白名单才放行。 */
        STRICT
    }

    fun isAllowed(caller: CallerIdentity): Boolean {
        return when (mode) {
            Mode.DISABLED -> true
            Mode.STRICT -> {
                if (caller.isPrivileged) return true
                caller.packageName in packages ||
                    (caller.signatureHash != null && caller.signatureHash in signatureHashes)
            }
        }
    }

    companion object {
        val DISABLED = CallerAllowList()
    }
}
