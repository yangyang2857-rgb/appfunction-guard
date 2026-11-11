package io.github.yangyang2857.afguard.cli

import io.github.yangyang2857.afguard.audit.AuditExport
import io.github.yangyang2857.afguard.audit.AuditRecord
import java.io.File
import kotlin.system.exitProcess

/**
 * 审计导出命令行。
 *
 * 存在理由：审计数据最有用的时刻，通常是你已经拿不到那台设备的时候。ring buffer 一重启
 * 就没了，所以必须能把 JSONL 拖出来，在笔记本上用 jq / Excel 看。
 *
 * 用法：
 *   guard-cli summary --in audit.jsonl
 *   guard-cli export --in audit.jsonl --out audit.csv --format csv
 *   guard-cli export --in audit.jsonl --out audit.jsonl --format json --limit 200
 * 输入用 `-` 表示 stdin，输出用 `-` 表示 stdout。
 */
fun main(args: Array<String>) {
    val code = run(args)
    if (code != 0) exitProcess(code)
}

private fun run(args: Array<String>): Int {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        println(usage())
        return if (args.isEmpty()) 2 else 0
    }

    val command = args[0]
    val opts = parseOptions(args.drop(1))
    if (opts == null) {
        System.err.println("参数格式错误。\n")
        System.err.println(usage())
        return 2
    }

    val input = opts["in"] ?: "-"
    return try {
        val records = readRecords(input)
        when (command) {
            "summary" -> { printSummary(input, records); 0 }
            "export" -> {
                val format = (opts["format"] ?: "csv").lowercase()
                if (format != "csv" && format != "json") {
                    System.err.println("不支持的格式: $format（可选 csv / json）")
                    return 2
                }
                val limit = opts["limit"]?.toIntOrNull()
                if (opts.containsKey("limit") && limit == null) {
                    System.err.println("--limit 需要是整数")
                    return 2
                }
                val out = opts["out"] ?: "-"
                val data = if (format == "csv") AuditExport.toCsv(records)
                else AuditExport.toJsonLines(records)
                writeText(out, if (limit != null) limitCsv(data, limit, format) else data)
                if (out != "-") {
                    System.err.println("已导出 ${records.size} 条记录 -> $out（$format）")
                }
                0
            }
            else -> {
                System.err.println("未知命令: $command\n")
                System.err.println(usage())
                2
            }
        }
    } catch (t: Throwable) {
        System.err.println("失败: ${t.message}")
        1
    }
}

private fun usage(): String = """
    用法: guard-cli <command> [options]

    命令:
      summary    统计审计文件（拒绝率、调用排行、降级次数）
      export     导出为 CSV 或 JSON Lines

    选项:
      --in <path>      输入文件，`-` 表示 stdin（默认 -）
      --out <path>     输出文件，`-` 表示 stdout（默认 -，仅 export）
      --format <fmt>   csv | json（默认 csv，仅 export）
      --limit <n>      只导出前 n 条（按文件顺序，仅 export）

    示例:
      guard-cli summary --in audit.jsonl
      guard-cli export --in audit.jsonl --out audit.csv --format csv
      cat audit.jsonl | guard-cli summary
""".trimIndent()

private fun parseOptions(args: List<String>): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--")) return null
        val value = args.getOrNull(i + 1) ?: return null
        out[key.removePrefix("--")] = value
        i += 2
    }
    return out
}

private fun readRecords(input: String): List<AuditRecord> {
    val text = if (input == "-") System.`in`.bufferedReader().readText() else File(input).readText()
    if (text.isBlank()) return emptyList()
    return AuditExport.parseJsonLines(text)
}

private fun writeText(out: String, data: String) {
    if (out == "-") {
        print(data)
    } else {
        val file = File(out)
        file.parentFile?.mkdirs()
        file.writeText(data)
    }
}

/** --limit 对 CSV 要保留表头。 */
private fun limitCsv(data: String, limit: Int, format: String): String {
    if (limit <= 0) return data
    val lines = data.split("\n").filter { it.isNotEmpty() }
    return if (format == "csv") {
        (listOfNotNull(lines.firstOrNull()) + lines.drop(1).take(limit)).joinToString("\n", postfix = "\n")
    } else {
        data.split("\n").filter { it.isNotEmpty() }.take(limit).joinToString("\n", postfix = "\n")
    }
}

private fun printSummary(input: String, records: List<AuditRecord>) {
    val s = AuditExport.summary(records)
    val source = if (input == "-") "stdin" else input
    println("审计来源: $source")
    if (s.total == 0) {
        println("没有记录。")
        return
    }
    println("记录条数: ${s.total}    被拦截: ${s.denied} (${"%.1f".format(s.denialRate * 100)}%)")
    println("降级次数: ${s.degraded}    脱敏命中: ${s.piiRedactedTotal}    最慢调用: ${s.slowestMillis} ms")
    println()
    println("按决策:")
    s.byDecision.entries.sortedByDescending { it.value }
        .forEach { println("  %-16s %d".format(it.key, it.value)) }
    println()
    println("调用最多的函数:")
    s.byFunction.take(5).forEach { println("  %-24s %d".format(it.first, it.second)) }
    println()
    println("调用方:")
    s.byCaller.take(5).forEach { println("  %-32s %d".format(it.first, it.second)) }
}
