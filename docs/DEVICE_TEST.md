# 真机验收清单

CI 能证明的是"能构建、单测通过、CLI 能跑"，**证明不了**拦截链在真实设备上按预期工作。

这份清单分两段，原因是这个项目目前的真实状态：

| 阶段 | 验的是什么 | 前置条件 | 耗时 |
|---|---|---|---|
| **阶段 A** | guard 拦截链本身（策略、校验、确认、脱敏、限流、审计） | 一台 Android 8.0+ 手机，装 sample APK | 20 分钟 |
| **阶段 B** | 与平台 AppFunctions 的接线（函数被系统收录、`adb` 直接调用、Agent 真实发起） | Android 16+ 设备（构建侧前置已满足：compileSdk 37 / AGP 9.1.0，sample 已接线） | 1～2 小时 |

阶段 A 不需要任何平台 API：sample 首页每个按钮都等价于一次 Agent 调用，走的是同一条
`AfGuard.intercept(...)` 路径，只是调用方从"系统 Agent"换成了"你自己"。**策略、校验、
确认、限流、脱敏、审计这六件事，在阶段 A 就能全部验完。**

阶段 B 才是 `docs/` 里原本设想的那套端到端验收。构建侧前置已经补齐，`sample` 也接好了
`@AppFunctionServiceEntryPoint`，剩下的只有"插上设备跑一遍"（见文末"阶段 B：已接线，待真机"）。

---

## 阶段 A：不需要平台 API（先做这个）

### 准备

```bash
# 1. 拿 APK：CI 的 sample-debug-apk 产物，或自己构建
gradle :sample:assembleDebug
adb install -r sample/build/outputs/apk/debug/sample-debug.apk

# 2. 开一个 logcat 窗口，只留库的日志
adb logcat -c && adb logcat -s AFG:V AFG/Confirm:V AndroidRuntime:E
```

装好后打开 `AFG Sample`，从上往下点按钮，逐项对照。

### 逐项检查（10 项，全部可在此阶段完成）

> **先看这里**：下表里带 `[CI]` 的 9 项，判定逻辑已经由
> `guard-core/src/test/kotlin/.../AfGuardInterceptTest.kt` 在 CI 上跑（Robolectric，
> 假时钟 + 假元数据表），改代码即回归，不再需要每次手动点一遍。
> 手工只剩 3 项：A3 的弹窗外观与按钮点击、A11、A12 —— 它们的共同点是
> "依赖 Android 真实的窗口/进程/权限行为"，JVM 上测不出来。
>
> 映射关系（测试名见 `AfGuardInterceptTest`）：
> A1→`ALLOW 直接执行并留下 ALLOWED 审计`，A2→`校验失败时业务不执行`，
> A3→`CONFIRM_EACH 同意后执行`（仅链路，不含外观与点击），A4→`CONFIRM_ONCE 在有效期内不重复弹窗`
> + `到期后重新弹窗`，A5→`返回值里的手机号在离开前被替换` + `审计只记参数指纹 不记原文`，
> A6→`ALWAYS_DENY 不执行业务且不可重试`，A7→`令牌桶用满后返回 RATE_LIMITED` + `补充令牌后放行`
> + `PER_CALLER 限流互不牵连`，A10→同 A5 的审计两条。
>
> A8 / A9 由 `guard-ui/src/test/kotlin/.../DialogConfirmationHandlerTest.kt` 覆盖：
> A8→`超时返回 false 并收掉残留弹窗`，A9→`没有 Activity 时拒绝` + `detach 之后拒绝且不再弹窗`，
> 另加一条 `主线程调用直接拒绝且不弹窗`（Agent 线程与主线程撞车时不能互相等死）。
>
> 这组测试跑在 Robolectric 上，有一处必须说清：**AlertDialog 在 JVM 里没有真实窗口，
> 按钮点击回调不可靠**，所以"点允许后真的删掉""点拒绝后真的没删"这两件事，
> 连同"超时后弹窗是否从屏幕上消失"，仍然只能真机验（见 A3、A8 的手工备注）。
> 已自动化的部分只是**决策与生命周期**：超时判否、无界面判否、detach 后判否、主线程不自等。

**当前状态（2026-11-12）**：CI 单测 34 条全绿（`:guard-core:testDebugUnitTest` +
`:guard-ui:testDebugUnitTest`），阶段 A 剩下 3 项手工 + 阶段 B 全部，仍需一台真机。

| # | 操作 | 期望 | 失败指向 |
|---|---|---|---|
| A1 `[CI]` | 点 `listNotes · ALLOW` | 立刻出结果，无弹窗，日志里一行 `ALLOWED` | 策略映射错 |
| A2 `[CI]` | 点 `searchNotes("") · 校验` | 日志 `INVALID_ARGUMENT field=keyword`；**列表没变**（业务没执行） | KSP 校验器未生效 / 参数名不匹配 |
| A3 | 点 `deleteNote · 待确认` | 弹窗显示调用方 + 风险等级；点"允许"后删除成功 | 确认 UI 未接线（`confirmationHandler.attach` 漏了） |
| A4 `[CI]` | 紧接着再点一次 `deleteNote` | **不再弹窗**，直接执行 | 授权缓存（CONFIRM_ONCE，7 天）失效 |
| A5 `[CI]` | 点 `exportNotes · 脱敏` | 返回值里手机号被替换成 `[REDACTED:PHONE]`；logcat 里搜不到完整号码 | `redactOutput` 未生效 |
| A6 `[CI]` | 点 `wipeAllNotes · 永久拒绝` | 不弹窗，日志 `DENIED_POLICY` / `FUNCTION_BLOCKED`，`retryable=false` | 阻断策略未命中 |
| A7 `[CI]` | 点 `连发 9 次 deleteNote` | 前 5 次正常，第 6 次起 `RATE_LIMITED` 且带 `retryAfterMs` | 限流窗口/令牌桶参数 |
| A8 `[CI]` | 触发 `deleteNote` 后**放着弹窗不动**，等 20 秒 | 超时后返回拒绝；真机上还要确认弹窗自己消失 | 超时保护（弹窗残留需真机确认） |
| A9 `[CI]` | 按 Home 键回桌面，再触发确认类函数 | 拒绝，不静默放行 | Activity 生命周期（`detach` 后应为无界面即拒） |
| A10 `[CI]` | 点 `查看最近 20 条审计` | 每次调用一行，只有参数**指纹/形状**，无原文 | `AuditSink` 未接（`GuardConfig.apply()` 漏了） |

补充两条只能靠"杀进程"来验的：

| # | 操作 | 期望 |
|---|---|---|
| A11 | 点几次函数后 `adb shell am force-stop <包名>`，再打开看审计 | 记录清空 —— **这是设计**（RingBuffer 是内存实现），要持久化换 `AuditSink` |
| A12 | 系统设置里把"显示弹窗"权限关掉，再触发确认类函数 | 拒绝而不是卡死（这验证的是 `DialogConfirmationHandler` 的降级路径） |

### 常见失败与原因

- **A3 点了没反应**：确认 `SampleApp` 里 `GuardConfig.confirmationHandler` 装的是
  `DialogConfirmationHandler`，并且 `MainActivity.onCreate` 里调过 `attach(this)`。
- **A2 不报错而是照样执行**：说明校验器没生成。`gradle :sample:kspDebugKotlin --info | grep afg`
  看 KSP 有没有处理到该类。
- **A7 计数不对**：限流状态挂在 `AfGuard` 的进程级单例上，热重载/多进程会重置。

---

## 阶段 B：已接线，待真机

### 状态

接线已落地（PR 见仓库 `build/phase-b-wiring`）：`compileSdk 37`、`AGP 9.1.0`、
`androidx.appfunctions 1.0.0-rc01` + KSP 编译器，`sample` 新增带 `@AppFunctionServiceEntryPoint`
的 `BaseNoteAppFunctionService`，四个函数统一走 `AfGuard.intercept(...)`。CI 产物里能验到三件事：
`NoteAppFunctionService` 已生成、`assets/note_app_function_service.xml` 收录了四个函数、
manifest 带 `BIND_APP_FUNCTION_SERVICE` 权限与 `android.app.appfunctions.AppFunctionService` action。

接线完成之前的状态长这样（留档）：`sample` 里既没有 `@AppFunction` 也没有
`AppFunctionService`，把 APK 装到 Android 16 设备上跑：

```bash
adb shell cmd app_function list-app-functions
```

**结果里不会有这个 App 的任何函数。** 这不是 bug，是它还没接平台。

要让函数出现在系统索引里，必须引 `androidx.appfunctions`，而这个库最新的几版（alpha10
至 1.0.0-rc01）在 AAR 元数据里都硬性要求：

```properties
minCompileSdk=37
minAndroidGradlePluginVersion=9.1.0
```

也就是说要动三处：`compileSdk 36 → 37`、`AGP 8.13.0 → 9.x`、新增 `appfunctions` 与
`appfunctions-compiler`(KSP)。顺带 KSP 版本也要跟着 Kotlin/AGP 走。这是一次构建体系升级，
不适合和"验证"混在一起做，建议单开一次提交。

### 接好之后的命令（写法与现在文档里的旧版不同，注意）

```bash
# 列函数（旧文档里的 `cmd app_function list` 不存在）
adb shell cmd app_function list-app-functions

# 按包名过滤，看自己这一坨
adb shell cmd app_function list-app-functions | grep -A 10 io.github.yangyang2857.afguard.sample

# 直接执行一个函数
adb shell "cmd app_function execute-app-function \
  --package io.github.yangyang2857.afguard.sample \
  --function 'io.github.yangyang2857.afguard.sample.NoteAppFunctionService#listNotes' \
  --parameters '{\"limit\": 5}'"
```

> 这两个 `cmd app_function` 子命令是 **Android 17（API 37）** 才进平台的。Android 16 的设备
> 上只有框架本身，没有 adb 入口 —— 那种设备上只能用官方 **AppFunctions Testing Agent**
> （一个聊天界面的测试宿主，装上去说人话，它会真的检索并调用你暴露的函数）。

### 接好之后的验收项

| # | 操作 | 期望 |
|---|---|---|
| B1 | `list-app-functions` | 四个函数都在，参数/返回值有描述 |
| B2 | `execute-app-function` 调 `listNotes` | 返回数据；审计里 `callerPackage` 是调用方而不是自己 |
| B3 | 调 `deleteNote` | 弹窗里显示的是**真实调用方包名**；拒绝后函数返回 `ERROR_DENIED` |
| B4 | 后台（无 Activity）时被调用 | 拒绝而非静默放行 |
| B5 | 系统设置里关闭该 App 的 AppFunction | 调用方收到 `ERROR_DISABLED`，不进入确认流程 |

---

## 已知需要留意的地方

- **functionId 的构造**：库内部按函数 `simpleName` 匹配（`deleteNote`），而平台生成的常量
  是编译期拼出来的（形态类似 `NoteAppFunctionService#deleteNote`）。阶段 B 接线时必须确认
  两者对得上，否则策略会全部落到默认分支 —— 这是接平台时最容易踩的一个坑。
- **超时默认 20s**：`DialogConfirmationHandler.MIN_TIMEOUT_MS = 1s`，传更小的值会被兜到 1s。
- **风险文案**：`Risk` 枚举的中文展示是 sample 自己拼的，不属于库的契约，改文案不影响行为。
- **caller 切线程后会退化**：`DefaultCallerResolver` 依赖 `Binder.getCallingUid()`，而
  `NoteAppFunctionService` 的函数体跑在 `Dispatchers.IO` 上；Binder 身份是线程局部的，切过去
  之后大概率读回自己的 uid。表现是 B2 里 `callerPackage` 等于本 App 而不是调用方，策略落到
  "不区分调用方"分支（限流、审计仍生效）。需要精确 caller 时由宿主注入自定义
  `CallerResolver`，库不擅自改 API。
- **schema 不是资源**：KSP 把函数清单写进 `assets/`，所以 manifest 里**不要**写
  `android:resource="@xml/..."`，AAPT 会直接报 `resource not found`。

## 实测记录

| 日期 | 设备 / 系统 | 阶段 | 结论 | 备注 |
|---|---|---|---|---|
| — | — | A | 未测 | `0.1.0-alpha3` 尚未在真机上跑过 |
| 2026-11-12 | CI 构建产物（非真机） | B | 代码侧完成 | compileSdk 37 / AGP 9.1.0 已升级，Service 与 assets schema 已随 APK 打包，manifest 注册就位；B1～B5 待真机执行 |

每验完一项就把对应行改成 `✅`，失败写清项号 + logcat 片段，然后开 issue。
