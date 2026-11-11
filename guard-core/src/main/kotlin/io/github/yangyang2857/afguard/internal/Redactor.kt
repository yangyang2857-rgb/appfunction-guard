package io.github.yangyang2857.afguard.internal

import android.os.Bundle
import io.github.yangyang2857.afguard.PiiKind

/**
 * PII 检测与脱敏。
 *
 * 对应论文风险一（隐私泄露）：Agent 拿到含手机号、邮箱、身份证的返回值后，
 * 通常会原样带进模型上下文。在返回值离开 App 之前把它替换掉，是最省事的一道闸。
 *
 * 设计红线：审计日志永不记录原文，只记录命中次数。
 * 否则这个库自己就成了二次泄露源。
 */
class Redactor(
    private val extraRules: List<Rule> = emptyList()
) {
    data class Rule(val kind: PiiKind, val regex: Regex)

    /** 单次脱敏结果。 */
    data class Outcome(val text: String, val hits: Int, val kinds: Set<PiiKind>)

    fun redactText(input: String): Outcome {
        var text = input
        var hits = 0
        val kinds = mutableSetOf<PiiKind>()

        for (rule in DEFAULT_RULES + extraRules) {
            val matches = rule.regex.findAll(text).toList()
            if (matches.isEmpty()) continue
            hits += matches.size
            kinds += rule.kind
            text = rule.regex.replace(text) { "[REDACTED:${rule.kind.name}]" }
        }
        return Outcome(text, hits, kinds)
    }

    /** 递归处理常见容器类型，其余原样返回。 */
    fun redactValue(value: Any?): Outcome = when (value) {
        null -> Outcome("", 0, emptySet())
        is String -> redactText(value)
        is Bundle -> {
            val out = StringBuilder()
            var hits = 0
            val kinds = mutableSetOf<PiiKind>()
            for (key in value.keySet()) {
                val raw = value.get(key)
                if (raw is String) {
                    val r = redactText(raw)
                    hits += r.hits
                    kinds += r.kinds
                    out.append(key).append('=').append(r.text).append(';')
                }
            }
            Outcome(out.toString(), hits, kinds)
        }
        is List<*> -> {
            var hits = 0
            val kinds = mutableSetOf<PiiKind>()
            val sb = StringBuilder()
            for (item in value) {
                val r = redactValue(item)
                hits += r.hits
                kinds += r.kinds
                sb.append(r.text).append('\n')
            }
            Outcome(sb.toString(), hits, kinds)
        }
        else -> {
            val r = redactText(value.toString())
            r
        }
    }

    companion object {
        /**
         * 内置规则偏保守：宁可漏一点，也不要误伤正常文本。
         * 这里全部要求"前后不紧邻数字"，避免把订单号里的片段误判成手机号。
         */
        val DEFAULT_RULES: List<Rule> = listOf(
            Rule(PiiKind.PHONE, Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")),
            Rule(PiiKind.EMAIL, Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),
            Rule(PiiKind.IDENTITY, Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")),
            Rule(PiiKind.FINANCIAL, Regex("""(?<!\d)\d{16,19}(?!\d)""")),
            Rule(PiiKind.LOCATION, Regex("""(?<!\d)\d{1,3}\.\d{4,},\s*\d{1,3}\.\d{4,}(?!\d)"""))
        )

        fun default(): Redactor = Redactor()
    }
}
