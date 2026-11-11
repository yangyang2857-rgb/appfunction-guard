# 错误码协议

这是本库最应该被别的项目抄走的一部分。

## 为什么要定这个

Agent 拿到一句 `IllegalArgumentException` 时是完全瞎的。它不知道是参数错了、被限流了、还是用户拒绝了，于是只能盲目重试——这就是真实世界里的 Agent 死循环。

Guard 让每次失败都带上下文：

```json
{
  "afg_error": {
    "code": "RATE_LIMITED",
    "function": "deleteNote",
    "message": "Rate limit exceeded for 'deleteNote'",
    "retryAfterMs": 12000,
    "retryable": true,
    "hint": "Too many calls. Wait for retryAfterMs, then retry."
  }
}
```

## 字段

| 字段 | 类型 | 必现 | 说明 |
|---|---|---|---|
| `code` | string | 是 | 错误码，见下表 |
| `function` | string | 是 | 出错的函数 ID |
| `message` | string | 是 | 人类可读的一句话，不含参数原文 |
| `retryable` | bool | 是 | **最关键的一个字段**。false 就意味着"别试了" |
| `hint` | string | 是 | 给模型看的下一步建议 |
| `field` | string | 否 | 出问题的参数名 |
| `expected` | string | 否 | 期望的格式/范围，用于自我纠正 |
| `retryAfterMs` | number | 否 | 建议等待毫秒数 |

## 错误码表

| code | retryable | 触发条件 | 给 Agent 的建议 |
|---|---|---|---|
| `INVALID_ARGUMENT` | true | 参数违反 `@TextLimit` / `@IntRange` / `@Pattern` / `@OneOfVal` | 按 `expected` 修正参数后重试 |
| `MISSING_ARGUMENT` | true | 必填参数缺失 | 补上 `field` 后重试 |
| `RATE_LIMITED` | true | 令牌桶耗尽 | 等 `retryAfterMs` |
| `CONFIRMATION_TIMEOUT` | true | 用户未在超时内响应 | 稍后再试一次 |
| `USER_DENIED` | **false** | 用户明确拒绝 | **不要重试**，去问用户 |
| `NOT_AUTHORIZED` | **false** | 调用方不在白名单 | 不要重试 |
| `FUNCTION_BLOCKED` | **false** | 策略为 `ALWAYS_DENY` | 不要重试，该能力永久关闭 |
| `FUNCTION_NOT_FOUND` | **false** | 函数 ID 不存在 | 检查可用函数列表 |
| `UPSTREAM_ERROR` | **false** | 业务代码自身异常 | 不要盲目重试，报告故障 |

## 两条设计原则

**1. 宁可多给一个 false，也不要让 Agent 猜。**

模型的安全默认行为是"失败了就再试一次"。`retryable: false` 是唯一能可靠掐断这个循环的信号。

**2. 永不回传参数原文。**

错误信息里出现用户手机号、邮箱，等于把隐私又漏了一遍。只回 `field` 名和 `expected` 规则，不回值。
