package io.github.yangyang2857.afguard

/**
 * 函数级授权策略。
 *
 * 注意：这里的 "确认" 指的是用户可见的确认动作。
 * Guard 自身不弹 UI —— 它只负责判定"需要确认"，确认界面的实现由宿主 App 通过
 * [GuardConfig.confirmationHandler] 提供（可以接系统弹窗、通知、Activity，随便）。
 * 这样库不绑定任何 UI 框架，也不强加体验。
 */
enum class Policy {
    /** 永不向 Agent 开放。适合改密码、退出登录这类不该被自动化的能力。 */
    ALWAYS_DENY,

    /** 每次调用都必须经过用户确认。适合删除、转账、发送消息。 */
    CONFIRM_EACH,

    /** 首次确认后，在 [GuardConfig.consentTtlMillis] 内免确认。默认策略。 */
    CONFIRM_ONCE,

    /** 直接放行，只做审计。适合只读查询。 */
    ALLOW
}

/** 风险等级。只影响呈现强度与默认策略，不改变拦截逻辑。 */
enum class Risk { LOW, MEDIUM, HIGH, CRITICAL }

/** 限流计数维度。 */
enum class RateScope {
    /** 每个调用方各算各的。默认，也是更安全的选择。 */
    PER_CALLER,

    /** 全设备共用一份配额。用于保护昂贵的系统资源。 */
    GLOBAL
}

/**
 * 函数级授权声明。
 *
 * 不标注 [Guard] 的函数会走 [GuardConfig.defaultPolicy]（默认 CONFIRM_ONCE），
 * 也就是"默认保守"而不是"默认放行"。这是本库与"什么都不做"的核心区别。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Guard(
    val policy: Policy = Policy.CONFIRM_ONCE,
    val risk: Risk = Risk.MEDIUM,
    /** 返回值是否过脱敏管线。含用户隐私的返回必须置 true。 */
    val redactOutput: Boolean = false,
    /** 同参重复调用是否直接返回上次结果（需要宿主提供缓存实现）。 */
    val idempotent: Boolean = false
)

/**
 * 限流声明。令牌桶实现，支持突发。
 *
 * 例：`@RateLimit(calls = 5, perSeconds = 60, burst = 2)`
 * → 平均每分钟 5 次，允许瞬时 2 次突发。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RateLimit(
    val calls: Int = 10,
    val perSeconds: Int = 60,
    val scope: RateScope = RateScope.PER_CALLER,
    val burst: Int = 3
)

// ---------------------------------------------------------------------------
// 参数校验注解
//
// 这些注解由 guard-ksp 在编译期读取，生成对应的 FunctionValidator。
// 运行时零反射、零注解解析开销。
// ---------------------------------------------------------------------------

/** 字符串长度限制。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class TextLimit(val max: Int = 256, val min: Int = 0)

/** 整数范围限制。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class IntRange(val min: Long, val max: Long)

/** 正则匹配。用于约束 ID 形态，挡住 `../` 之类的路径穿越尝试。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class Pattern(val regex: String)

/** 枚举值白名单。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class OneOfVal(val values: Array<String>)

/**
 * 标记敏感参数。用于两件事：
 * 1. 审计日志中对参数值遮罩；
 * 2. 确认 UI 中高亮提示"这次调用会碰到什么数据"。
 *
 * 注意：它不会阻止参数被使用，只是标记。真正的阻断靠 [Policy]。
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class Sensitive(val kind: PiiKind = PiiKind.GENERIC)

/** 隐私数据类型。 */
enum class PiiKind { GENERIC, PHONE, EMAIL, LOCATION, CONTACT, FINANCIAL, IDENTITY }
