# sample — 可直接运行的演示 App

这个模块存在的唯一目的：让人在 30 秒内看到拦截链真的在拦东西，而不是读 README 想象。

它**不依赖** `android.app.appfunctions.*`，所以能和 `guard-core` 一起在任何 Android 36 模拟器上编译运行。

## 跑起来

```bash
gradle :sample:installDebug   # 仓库未带 wrapper，需本机 Gradle 9.3.1；或先执行 gradle wrapper 生成
```

打开 **AFG Sample**，界面上每个按钮都等价于一次 Agent 发起的调用 —— 走的是 `AfGuard.intercept()`，不是直接调业务函数。

| 按钮 | 预期现象 |
|------|----------|
| `listNotes · ALLOW` | 直接返回，不弹窗，审计里多一条 `ALLOWED` |
| `searchNotes("") · 校验` | 进入业务代码**之前**返回 `INVALID_ARGUMENT`，`field = keyword`，`retryable = true` |
| `deleteNote · 待确认` | 弹窗，显示调用方、风险等级；允许后 7 天内同类调用不再弹 |
| `exportNotes · 脱敏` | 返回内容里的手机号变成 `138****8000`，日志标 `[返回值已脱敏]` |
| `wipeAllNotes · 永久拒绝` | 连弹窗都没有，直接 `FUNCTION_BLOCKED`，`retryable = false` |
| `连发 9 次 deleteNote` | 前几次通过，之后 `RATE_LIMITED` 并给出 `retryAfterMs` |
| `查看最近 20 条审计` | 每条调用一行，只有参数指纹，没有参数值 |

## 四个函数对应四类能力

```kotlin
@Guard(policy = Policy.ALLOW, risk = Risk.LOW)
fun listNotes(@IntRange(min = 1, max = 50) limit: Int): List<String>

@Guard(policy = Policy.CONFIRM_ONCE, risk = Risk.MEDIUM)
@RateLimit(calls = 5, perSeconds = 60, burst = 2)
fun deleteNote(@Pattern(regex = "^[A-Za-z0-9_-]{1,64}$") id: String): String

@Guard(policy = Policy.CONFIRM_EACH, risk = Risk.HIGH, redactOutput = true)
@RateLimit(calls = 2, perSeconds = 60, burst = 1)
fun exportNotes(@OneOfVal(values = ["plain", "json"]) format: String): String

@Guard(policy = Policy.ALWAYS_DENY, risk = Risk.CRITICAL)
fun wipeAllNotes(): String
```

注意业务函数体里没有任何一行安全检查。安全策略在注解上，执行在调用边界上，两者都不侵入业务逻辑。

## 真实设备上怎么接 AppFunctions

示例刻意不带 `@AppFunction`，因为那套 API 还在 alpha、会导致 `guard-core` 无法独立编译。真实项目里，把你自己的 `AppFunctionService` 包一层即可 —— 只改这一处：

```kotlin
class NoteAppFunctionService : AppFunctionService() {

    override fun onExecuteFunction(
        functionId: String,
        args: Bundle,
        // 平台参数随 alpha 版本变动，以官方文档为准
        cancellationSignal: CancellationSignal
    ): Bundle {
        val result = AfGuard.intercept(functionId, args) { call ->
            when (call.functionId) {
                "listNotes"   -> NoteFunctions.listNotes(call.intOr("limit", 20))
                "searchNotes" -> NoteFunctions.searchNotes(call.requireString("keyword"))
                "deleteNote"  -> NoteFunctions.deleteNote(call.requireString("id"))
                "exportNotes" -> NoteFunctions.exportNotes(call.requireString("format"))
                "wipeAllNotes"-> NoteFunctions.wipeAllNotes()
                else -> throw NoSuchElementException(call.functionId)
            }
        }
        return BundleCodec.toBundle(result)
    }
}
```

`BundleCodec.toBundle()` 会把 `GuardResult.Err` 序列化成 `{"afg_error": {...}}`，Agent 侧拿到的是一段能读懂结构的错误，而不是一个异常字符串。

## 两个容易接错的点

1. **不要在 UI 线程同步等弹窗。** `ConfirmationHandler.confirm()` 是阻塞调用，而 `AlertDialog` 必须在主线程创建。sample 不再自带实现，直接用 `guard-ui` 的 `io.github.yangyang2857.afguard.ui.DialogConfirmationHandler`：调用方在后台线程，`confirm()` 里用 `CountDownLatch` + `runOnUiThread` 把两边界接起来；在 `Activity.onCreate` 里 `attach(this)`、`onDestroy` 里 `detach(this)` 跟随宿主生命周期，超时会 dismiss 残留弹窗并返回拒绝，在主线程调用 `confirm()` 直接判否而不是死锁。自己实现时这四条缺一不可。
2. **`GuardConfig.init()` 之后再改 `auditSink`，要补一次 `GuardConfig.apply()`。** 审计出口是在 `init` 时挂上的，换出口不重新挂就会静默丢记录。

## 已知限制

- `functionId` 取的是函数**简单名**。同一个 App 里两个类各有 `deleteNote` 会互相覆盖，务必保证全局唯一。
- 未开启 `minifyEnabled` 混淆时正常；如果开 R8，记得保留 `io.github.yangyang2857.afguard.generated.**`，反射加载依赖类名。
