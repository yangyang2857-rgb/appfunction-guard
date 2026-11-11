# AppFunction Guard

**Android 16 让 AI Agent 直接调用你 App 里的函数。Guard 是那套机制忘了给你的锁、门铃和访客登记簿。**

[English](README.md) · [错误码协议](docs/ERROR_CODES.md) · [变更日志](CHANGELOG.md)

---

## 问题

Android 16 引入了 **AppFunctions**：Agent 可以绕过你的 UI，直接调用 App 内部函数。平台给了三样东西——

- 注册函数的方式
- 把函数描述给模型的方式
- 执行函数的方式

平台**没给**的：

| 缺失 | 后果 |
|---|---|
| 函数级授权 | 能调 `getNote` 的 Agent，也能调 `deleteAllNotes` |
| 参数校验 | 模型编出 `limit: -1`，你的代码照着用 |
| 限流 | 一个重试循环一分钟打你数据库四千次 |
| 审计 | 用户问"昨晚助手对我数据做了什么"，你答不上来 |
| 同意交互 | 用户完全不知道有 Agent 正在翻他的相册 |
| 结构化错误 | Agent 拿到一个 `IllegalArgumentException`，然后永远重试 |

这些不是假想。已有的 AppFunctions 安全研究列出了三类实打实的风险：**隐私泄露**（工具返回值）、**隔离突破**（函数暴露过宽）、**LLM 完整性攻击**（恶意工具描述）。三类都不是"缺 API"，是"缺策略"。

Guard 就是那层策略。

---

## 它做什么

平台与业务代码之间的唯一拦截点：

```
agent
  │
  ▼
AppFunctionService.onExecuteFunction()
  │
  ├─ 1. 调用方白名单     谁在调？
  ├─ 2. 策略判定         这个函数让不让调？
  ├─ 3. 令牌桶限流       按函数 × 调用方分桶
  ├─ 4. 参数校验         编译期由 KSP 生成
  ├─ 5. 用户确认         只在策略要求时出现
  ├─ 6. 执行业务         你的代码，原样不动
  ├─ 7. 脱敏             在返回值流向模型之前
  └─ 8. 审计             每次调用一条，永不记录参数原文
```

全部在业务代码之前执行。不是装饰器，不会漏加。

---

## 快速接入

```kotlin
// 1. Application.onCreate
GuardConfig.init(this)
GuardConfig.confirmationHandler = MyConfirmDialog()   // 宿主自己实现，几行

// 2. 你的 AppFunctionService
override fun onExecuteFunction(functionId: String, args: Bundle): Bundle =
    BundleCodec.toBundle(
        Guard.intercept(functionId, args) { call ->
            when (call.functionId) {
                "listNotes"  -> repo.recent(call.intOr("limit", 20))
                "deleteNote" -> repo.delete(call.requireString("id"))
                else         -> null
            }
        }
    )

// 3. 在函数上声明策略
@Guard(policy = Policy.CONFIRM_EACH, risk = Risk.HIGH)
@RateLimit(calls = 5, perSeconds = 60)
@AppFunction
fun deleteNote(@TextLimit(max = 64) id: String): Boolean = repo.delete(id)
```

注解在编译期由 KSP 读取，热路径上没有反射。

---

## 错误协议

即使你最终不用这个库，这部分也值得抄走。

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

`retryable` 是最关键的字段。模型的默认行为是"失败就再试一次"，`retryable: false` 是唯一能可靠掐断这个循环的信号。

| code | retryable | 含义 |
|---|---|---|
| `INVALID_ARGUMENT` | 是 | 按 `expected` 修正参数后重试 |
| `MISSING_ARGUMENT` | 是 | 补上 `field` |
| `RATE_LIMITED` | 是 | 等 `retryAfterMs` |
| `USER_DENIED` | **否** | 人拒绝了。停。 |
| `NOT_AUTHORIZED` | **否** | 调用方不被允许 |
| `FUNCTION_BLOCKED` | **否** | 永久关闭 |
| `UPSTREAM_ERROR` | **否** | 你的代码坏了，别再打 |

完整说明：[docs/ERROR_CODES.md](docs/ERROR_CODES.md)

---

## 注解一览

| 注解 | 作用位置 | 用途 |
|---|---|---|
| `@Guard(policy, risk, redactOutput, idempotent)` | 函数 | 授权策略 |
| `@RateLimit(calls, perSeconds, scope, burst)` | 函数 | 令牌桶限流 |
| `@TextLimit(min, max)` | String 参数 | 长度边界 |
| `@IntRange(min, max)` | Int/Long 参数 | 数值边界 |
| `@Pattern(regex)` | String 参数 | 格式校验 |
| `@OneOfVal(values)` | String 参数 | 白名单取值 |
| `@Sensitive(kind)` | 参数 | 标记 PII，供确认弹窗展示 |

四种策略：

- `ALWAYS_DENY` — Agent 永远碰不到这个函数
- `CONFIRM_EACH` — 每次都要用户点头
- `CONFIRM_ONCE` — 同意后记住 `consentTtlMillis`（默认 7 天）；**对 `Risk.CRITICAL` 不生效**
- `ALLOW` — 放行，但仍然限流、仍然审计

---

## 几个刻意的设计取舍

**库不 import 任何 `android.app.appfunctions.*`。**

那套 API 还在 alpha，alpha08 到 alpha10 之间确实删过组件。如果 Guard 依赖那些类型，平台一动库就废。所以 Guard 只操作 `String functionId` + `Bundle args` —— 这两个东西跨版本都在。平台怎么变，只改你自己 Service 里那一行。

**审计永不记录参数值。**

只记参数**结构**的 SHA-256 指纹，和脱敏命中数。一个安全库如果把用户手机号写进日志，它自己就是新漏洞。

**`CONFIRM_ONCE` 不对 `CRITICAL` 生效。**

便利性不该有能力把破坏性操作变成永久的。

**配置缺失时失败关闭。**

函数需要确认但没装 `ConfirmationHandler` → 拒绝。不是放行，不是忽略，是拒绝。

**降级要留痕。**

KSP 生成的表缺失时（构建配置错了），校验被跳过，但每条审计记录标 `degraded = true`。静默失败比崩溃更糟。

---

## 环境要求

- `compileSdk 36`（AGP 8.13+）
- `minSdk 26` —— 库本身只碰 `Bundle` + `Binder`，低版本也能跑；**AppFunctions 本身要求设备是 API 36**
- Kotlin 2.x
- KSP

```kotlin
dependencies {
    implementation("com.github.yangyang2857-rgb.appfunction-guard:guard-core:0.1.0-alpha")
    ksp("com.github.yangyang2857-rgb.appfunction-guard:guard-ksp:0.1.0-alpha")
}
```

> JitPack 坐标正在配置。在此之前用 `includeBuild` 引本仓库。

---

## 当前状态

**alpha，不可用于生产。**

诚实清单：

- [x] 核心拦截链、策略引擎、限流器、脱敏器、审计、错误协议
- [x] KSP 处理器（生成校验器 + 元数据表）
- [ ] **真机端到端验证** —— 开发环境没有 Android SDK，还没对着真实 `AppFunctionService` 跑通过
- [ ] `guard-ui` 确认弹窗
- [ ] `guard-cli` 审计导出
- [ ] Maven Central 发布

早期阶段，最有价值的是对**注解设计**的反馈，不是 bug 报告。

---

## 同类项目核查

截至 2026 年 11 月，没有找到给 AppFunctions 做函数级授权 / 校验 / 限流 / 审计的现成库。GitHub 上 `appfunctions` 大约 72 个仓库，基本是个位数 star 的样例；唯一沾边的是 `anyfn`（UI 自动化），解决的是另一个问题——它驱动界面，不守卫 API 面。官方样例几百 star，无策略层。

如果你知道有先行者，开 issue 告诉我，我挂链接。这比假装第一更有价值。

---

## 路线

| 周 | 目标 |
|---|---|
| W1 | 真机跑通端到端，写清楚到底哪里炸了 |
| W2 | `guard-ui` 确认面板 + 审计查看器 |
| W3 | `guard-cli` 导出、策略 lint（`afg check`） |
| W4 | Maven Central，v0.1.0 |

---

## 许可

MIT，见 [LICENSE](LICENSE)。

---

*本项目与 Google 无关。AppFunctions 是 Android 平台 API，这是一个独立社区库。*
