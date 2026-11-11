package io.github.yangyang2857.afguard.sample

import io.github.yangyang2857.afguard.Guard
import io.github.yangyang2857.afguard.OneOfVal
import io.github.yangyang2857.afguard.Pattern
import io.github.yangyang2857.afguard.Policy
import io.github.yangyang2857.afguard.RateLimit
import io.github.yangyang2857.afguard.Risk
import io.github.yangyang2857.afguard.TextLimit

/**
 * 四个函数，四种策略，覆盖一张 App 里最典型的四类能力：
 *
 * | 函数 | 策略 | 想说明的问题 |
 * |------|------|--------------|
 * | `listNotes` | ALLOW | 只读查询不该打断用户 |
 * | `deleteNote` | CONFIRM_ONCE | 写操作首次问一次，之后 7 天不再烦 |
 * | `exportNotes` | CONFIRM_EACH + 脱敏 | 批量外带数据，每次都得问 |
 * | `wipeAllNotes` | ALWAYS_DENY | 不可逆操作，对 Agent 永久关闭 |
 *
 * 注意：这里没有出现 `@AppFunction`。官方注解还在 alpha，示例刻意不依赖它，
 * 这样 guard-core 可以独立编译；真实接入时把这几个函数挂到你的
 * AppFunctionService 里即可（见 sample/README.md）。
 */
object NoteFunctions {

    /** 只读、低风险、直接放行，仅在审计里留痕。 */
    @Guard(policy = Policy.ALLOW, risk = Risk.LOW)
    fun listNotes(
        @io.github.yangyang2857.afguard.IntRange(min = 1, max = 50) limit: Int
    ): List<String> = NoteRepository.recent(limit).map { "${it.id}: ${it.title}" }

    /** 删除：首次确认，之后在同意有效期内免确认。同时限制频率，防止 Agent 批量删。 */
    @Guard(policy = Policy.CONFIRM_ONCE, risk = Risk.MEDIUM)
    @RateLimit(calls = 5, perSeconds = 60, burst = 2)
    fun deleteNote(
        @Pattern(regex = "^[A-Za-z0-9_-]{1,64}$") id: String
    ): String {
        val removed = NoteRepository.delete(id)
        return if (removed) "deleted $id" else "not found: $id"
    }

    /**
     * 导出：把手机号一起带出去，属于高风险外带。
     * 策略是每次确认，返回值过脱敏管线（`redactOutput = true`）。
     */
    @Guard(policy = Policy.CONFIRM_EACH, risk = Risk.HIGH, redactOutput = true)
    @RateLimit(calls = 2, perSeconds = 60, burst = 1)
    fun exportNotes(
        @OneOfVal(values = ["plain", "json"]) format: String
    ): String {
        val rows = NoteRepository.recent(50).joinToString("\n") {
            if (format == "json") {
                """{"id":"${it.id}","phone":"${it.phone}"}"""
            } else {
                "${it.id}\t${it.title}\t${it.phone}"
            }
        }
        return rows
    }

    /** 清空：不可逆，对 Agent 永久关闭。用户自己在 UI 里点了也要走 Guard，验证这一层真的挡得住。 */
    @Guard(policy = Policy.ALWAYS_DENY, risk = Risk.CRITICAL)
    fun wipeAllNotes(): String = "wiped ${NoteRepository.wipe()} notes"

    /** 演示参数校验：超长文本 + 空字符串，都会在进入业务代码之前被拦下。 */
    @Guard(policy = Policy.ALLOW, risk = Risk.LOW)
    fun searchNotes(
        @TextLimit(min = 1, max = 16) keyword: String
    ): List<String> = NoteRepository.recent(50)
        .filter { it.title.contains(keyword) || it.body.contains(keyword) }
        .map { it.id }
}
