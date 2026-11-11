package io.github.yangyang2857.afguard.audit

/**
 * 一次调用的决策结果。
 *
 * 注意区分 [DENIED_USER] 与 [DENIED_POLICY]：前者代表"人拦的"，后者代表"规则拦的"，
 * 两者在统计上意义完全不同 —— 前者说明 Agent 在骚扰用户，后者说明策略写对了。
 */
enum class DecisionKind {
    ALLOWED,
    DENIED_POLICY,
    DENIED_USER,
    DENIED_CALLER,
    RATE_LIMITED,
    INVALID_ARGUMENT,
    UPSTREAM_ERROR
}

/**
 * 审计记录。刻意不含参数原文、返回值原文与堆栈。
 *
 * 这个模块不依赖 Android，是为了让 guard-cli 能在没有设备、没有 SDK 的机器上把审计
 * 导出来 —— 审计数据最需要被分析的时候，往往已经离线了。
 */
data class AuditRecord(
    val timestampMillis: Long,
    val functionId: String,
    val callerPackage: String,
    val callerUid: Int,
    val decision: DecisionKind,
    val durationMillis: Long,
    val argsDigest: String?,
    val piiRedacted: Int,
    val errorCode: String?,
    val degraded: Boolean = false
)

/** 审计出口。 */
fun interface AuditSink {
    fun record(record: AuditRecord)
}

/** 默认实现：内存环形缓冲，保留最近 N 条。 */
class RingBufferAuditSink(private val capacity: Int = 5000) : AuditSink {
    private val records = ArrayDeque<AuditRecord>(capacity)

    override fun record(record: AuditRecord) {
        synchronized(records) {
            if (records.size >= capacity) records.removeFirst()
            records.addLast(record)
        }
    }

    /** 按时间倒序快照。 */
    fun snapshot(limit: Int = capacity): List<AuditRecord> = synchronized(records) {
        records.asReversed().take(limit)
    }

    /** 简单统计，供"昨晚 AI 调了你 47 次"这类展示用。 */
    fun summary(): Map<String, Int> = synchronized(records) {
        records.groupingBy { it.functionId }.eachCount()
    }

    fun denialCount(): Int = synchronized(records) {
        records.count { it.decision != DecisionKind.ALLOWED }
    }

    fun clear() = synchronized(records) { records.clear() }
}

/** 广播到多个出口（例如内存 + 文件）。 */
class CompositeAuditSink(private val sinks: List<AuditSink>) : AuditSink {
    override fun record(record: AuditRecord) {
        sinks.forEach { runCatching { it.record(record) } }
    }
}

/** 空实现，用于彻底关闭审计。 */
object NoopAuditSink : AuditSink {
    override fun record(record: AuditRecord) = Unit
}
