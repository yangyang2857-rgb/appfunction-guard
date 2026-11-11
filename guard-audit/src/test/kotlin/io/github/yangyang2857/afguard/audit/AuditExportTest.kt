package io.github.yangyang2857.afguard.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditExportTest {

    private val sample = listOf(
        AuditRecord(1_700_000_000_000, "listNotes", "com.example.agent", 10123,
            DecisionKind.ALLOWED, 3, "a1b2c3d4e5f60718", 0, null),
        AuditRecord(1_700_000_001_000, "wipeAllNotes", "com.example.agent", 10123,
            DecisionKind.DENIED_POLICY, 1, null, 0, "afg_policy_denied"),
        AuditRecord(1_700_000_002_000, "sendSms", "com.evil.agent", 10999,
            DecisionKind.DENIED_USER, 15_000, "deadbeefdeadbeef", 2, null, degraded = true)
    )

    @Test
    fun jsonLinesRoundTrip() {
        val parsed = AuditExport.parseJsonLines(AuditExport.toJsonLines(sample))
        assertEquals(sample, parsed)
    }

    @Test
    fun jsonLinesAreOneLinePerRecord() {
        val text = AuditExport.toJsonLines(sample)
        assertEquals(3, text.trim().split("\n").size)
    }

    @Test
    fun parseSkipsBlankAndCommentLines() {
        val text = "\n# 手工样例\n" + AuditExport.toJsonLine(sample[0]) + "\n\n"
        assertEquals(1, AuditExport.parseJsonLines(text).size)
    }

    @Test
    fun parseRejectsUnknownDecision() {
        val bad = """{"timestampMillis":1,"functionId":"f","callerPackage":"p","callerUid":1,"decision":"NOPE"}"""
        val err = runCatching { AuditExport.parseJsonLines(bad) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("unknown decision"))
    }

    @Test
    fun parseRejectsMissingField() {
        val bad = """{"timestampMillis":1,"functionId":"f","callerPackage":"p","decision":"ALLOWED"}"""
        val err = runCatching { AuditExport.parseJsonLines(bad) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("callerUid"))
    }

    @Test
    fun csvEscapesSeparatorsAndQuotes() {
        val tricky = sample[0].copy(functionId = "a,\"b\"c", errorCode = "x\ny")
        val lines = AuditExport.toCsv(listOf(tricky)).trim().split("\n")
        // 表头 + 虽然含换行但仍是一条记录（被引号包住，按行切会变成两段，这里只校验引号转义）
        assertTrue(lines[0].startsWith("timestampMillis,functionId"))
        val body = AuditExport.toCsv(listOf(tricky)).trim()
        assertTrue(body.contains("\"a,\"\"b\"\"c\""))
        assertTrue(body.contains("\"x\ny\""))
    }

    @Test
    fun summaryCountsDenialsAndRanking() {
        val s = AuditExport.summary(sample)
        assertEquals(3, s.total)
        assertEquals(2, s.denied)
        assertEquals(1, s.degraded)
        assertEquals(15_000L, s.slowestMillis)
        assertEquals(2, s.piiRedactedTotal)
        assertEquals("com.example.agent", s.byCaller.first().first)
        assertTrue(s.denialRate > 0.66 && s.denialRate < 0.67)
    }
}
