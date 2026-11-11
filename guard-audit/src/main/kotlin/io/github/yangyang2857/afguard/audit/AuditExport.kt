package io.github.yangyang2857.afguard.audit

/**
 * 审计数据导出 / 导入。
 *
 * 格式只有两种，都是"能被 Excel、jq、pandas 直接吃掉"的形态：
 * - CSV：给人和表格软件看，一行一次调用；
 * - JSON Lines：给脚本和管道看，一行一个对象，天然流式，不需要一次读进内存。
 *
 * 没有任何第三方依赖。手写 JSON 解析是为了让 guard-cli 保持零依赖 —— 审计导出工具
 * 自己去引入一套序列化框架，是最容易被供应链问题毁掉的环节。
 */
object AuditExport {

    val CSV_HEADER = listOf(
        "timestampMillis", "functionId", "callerPackage", "callerUid",
        "decision", "durationMillis", "argsDigest", "piiRedacted", "errorCode", "degraded"
    )

    // ---------------------------------------------------------------- CSV

    /** 导出 CSV（RFC 4180：含逗号/引号/换行时加引号并把 " 翻倍）。 */
    fun toCsv(records: List<AuditRecord>): String = buildString {
        append(CSV_HEADER.joinToString(","))
        append("\n")
        records.forEach { r ->
            append(
                listOf(
                    r.timestampMillis.toString(),
                    r.functionId,
                    r.callerPackage,
                    r.callerUid.toString(),
                    r.decision.name,
                    r.durationMillis.toString(),
                    r.argsDigest ?: "",
                    r.piiRedacted.toString(),
                    r.errorCode ?: "",
                    r.degraded.toString()
                ).joinToString(",") { csvCell(it) }
            )
            append("\n")
        }
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    // ------------------------------------------------------------ JSONL

    fun toJsonLine(r: AuditRecord): String = buildString {
        append('{')
        append("\"timestampMillis\":").append(r.timestampMillis)
        append(",\"functionId\":").append(jsonString(r.functionId))
        append(",\"callerPackage\":").append(jsonString(r.callerPackage))
        append(",\"callerUid\":").append(r.callerUid)
        append(",\"decision\":").append(jsonString(r.decision.name))
        append(",\"durationMillis\":").append(r.durationMillis)
        append(",\"argsDigest\":").append(r.argsDigest?.let(::jsonString) ?: "null")
        append(",\"piiRedacted\":").append(r.piiRedacted)
        append(",\"errorCode\":").append(r.errorCode?.let(::jsonString) ?: "null")
        append(",\"degraded\":").append(r.degraded)
        append('}')
    }

    fun toJsonLines(records: List<AuditRecord>): String =
        if (records.isEmpty()) "" else records.joinToString("\n", postfix = "\n") { toJsonLine(it) }

    private fun jsonString(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * 解析 JSON Lines。空行与以 # 开头的注释行跳过（便于手写样例）。
     * 无法解析的行抛出 [IllegalArgumentException]，由调用方决定是跳过还是中断 ——
     * 静默丢数据比报错更危险。
     */
    fun parseJsonLines(text: String): List<AuditRecord> {
        val out = ArrayList<AuditRecord>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val obj = runCatching { JsonReader(line).readObject() }
                .getOrElse { throw IllegalArgumentException("line ${index + 1}: ${it.message}") }
            out += toRecord(obj, index + 1)
        }
        return out
    }

    private fun toRecord(m: Map<String, Any?>, line: Int): AuditRecord {
        fun fail(name: String): Nothing =
            throw IllegalArgumentException("line $line: missing or invalid field '$name'")

        val ts = m.long("timestampMillis") ?: fail("timestampMillis")
        val fn = m["functionId"] as? String ?: fail("functionId")
        val pkg = m["callerPackage"] as? String ?: fail("callerPackage")
        val uid = m.int("callerUid") ?: fail("callerUid")
        val decisionName = m["decision"] as? String ?: fail("decision")
        val decision = runCatching { DecisionKind.valueOf(decisionName) }
            .getOrElse { throw IllegalArgumentException("line $line: unknown decision '$decisionName'") }
        val duration = m.long("durationMillis") ?: 0L
        val digest = (m["argsDigest"] as? String)?.takeIf { it.isNotEmpty() }
        val pii = m.int("piiRedacted") ?: 0
        val error = (m["errorCode"] as? String)?.takeIf { it.isNotEmpty() }
        val degraded = m["degraded"] as? Boolean ?: false
        return AuditRecord(ts, fn, pkg, uid, decision, duration, digest, pii, error, degraded)
    }

    private fun Map<String, Any?>.long(k: String): Long? = when (val v = this[k]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun Map<String, Any?>.int(k: String): Int? = long(k)?.toInt()

    // ---------------------------------------------------------- summary

    /** 导出前的体检：谁在疯狂调用、拦了多少、有没有降级。 */
    data class Summary(
        val total: Int,
        val denied: Int,
        val degraded: Int,
        val byDecision: Map<String, Int>,
        val byFunction: List<Pair<String, Int>>,
        val byCaller: List<Pair<String, Int>>,
        val piiRedactedTotal: Int,
        val slowestMillis: Long
    ) {
        val denialRate: Double get() = if (total == 0) 0.0 else denied.toDouble() / total
    }

    fun summary(records: List<AuditRecord>): Summary {
        val byDecision = records.groupingBy { it.decision.name }.eachCount()
        val byFunction = records.groupingBy { it.functionId }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        val byCaller = records.groupingBy { it.callerPackage }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        return Summary(
            total = records.size,
            denied = records.count { it.decision != DecisionKind.ALLOWED },
            degraded = records.count { it.degraded },
            byDecision = byDecision,
            byFunction = byFunction,
            byCaller = byCaller,
            piiRedactedTotal = records.sumOf { it.piiRedacted },
            slowestMillis = records.maxOfOrNull { it.durationMillis } ?: 0L
        )
    }
}

/** 极简 JSON 读取器：只支持对象/字符串/数字/布尔/null，够用且无依赖。 */
private class JsonReader(private val src: String) {
    private var i = 0

    fun readObject(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        expect('{')
        if (peek() == '}') { i++; return out }
        while (true) {
            val key = readString()
            expect(':')
            out[key] = readValue()
            when (val c = nextSignificant()) {
                ',' -> continue
                '}' -> return out
                else -> error("expected ',' or '}' but found '$c' at $i")
            }
        }
    }

    private fun readValue(): Any? = when (peek()) {
        '"' -> readString()
        '{' -> readObject()
        't' -> { expectWord("true"); true }
        'f' -> { expectWord("false"); false }
        'n' -> { expectWord("null"); null }
        else -> readNumber()
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) error("unterminated string")
            when (val c = src[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    when (val e = src[i++]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { sb.append(src.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Number {
        val start = i
        if (peek() == '-') i++
        while (i < src.length && (src[i].isDigit() || src[i] in ".eE+-")) i++
        val text = src.substring(start, i)
        require(text.isNotEmpty()) { "expected number at $start" }
        return if (text.any { it == '.' || it == 'e' || it == 'E' }) text.toDouble()
        else text.toLongOrNull() ?: text.toDouble()
    }

    private fun expectWord(word: String) {
        require(src.startsWith(word, i)) { "expected '$word' at $i" }
        i += word.length
    }

    private fun peek(): Char {
        skipWs()
        require(i < src.length) { "unexpected end of input" }
        return src[i]
    }

    private fun nextSignificant(): Char {
        if (i < src.length && src[i].isWhitespace()) i++
        require(i < src.length) { "unexpected end of input" }
        return src[i++]
    }

    private fun skipWs() { while (i < src.length && src[i].isWhitespace()) i++ }

    private fun expect(c: Char) {
        require(peek() == c) { "expected '$c' at $i" }
        i++
    }
}
