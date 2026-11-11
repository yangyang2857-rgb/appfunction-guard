# 真机验收清单

CI 能证明的是"能构建、单测通过、CLI 能跑"，**证明不了**拦截链在真实 `AppFunctionService`
上按预期工作 —— 因为 GitHub Actions 上没有 Android 设备，也装不了 Agent 宿主。

这份清单就是 CI 的盲区。整套做下来约 30 分钟。

## 需要什么

| 项 | 要求 | 说明 |
|---|---|---|
| 设备 | Android 16（API 36），真机优先 | 模拟器可以跑通前 6 项，但 AppFunctions 的调用方鉴权在模拟器上可能与真机不同 |
| 构建 | JDK 17 + Android SDK 36 | `gradle :sample:installDebug` |
| 调用方 | 一个会调你 AppFunction 的 Agent / 宿主 | 没有的话用 `adb shell cmd app_function execute ...`（见下） |
| 日志 | `adb logcat -s AFG:* AFG/Confirm:*` | 库的 tag 统一 `AFG/` 前缀 |

先装再跑：

```bash
gradle :sample:assembleDebug
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
adb shell cmd app_function list            # 确认 sample 的函数已被平台收录
```

如果 `list` 里看不到函数，问题在清单/服务声明，不在库 —— 先照 sample/README.md 把
`AppFunctionService` 注册好，再回来做这份清单。

## 逐项检查

| # | 操作 | 期望 | 失败说明什么 |
|---|---|---|---|
| 1 | 调 `listNotes`（ALLOW 策略） | 直接返回数据，不弹窗；logcat 有一行 `ALLOWED` | 策略映射或 functionId 匹配错了 |
| 2 | 调 `searchNotes` 传 `keyword` 超长 | 返回 `INVALID_ARGUMENT`，`field=keyword`，业务代码**没有**执行 | KSP 校验器没生效或参数名对不上 |
| 3 | 调 `deleteNote` | 弹窗显示调用方包名 + 风险等级；点"允许"后执行 | 确认 UI 接线问题 |
| 4 | 立刻再调一次 `deleteNote` | 不再弹窗（CONFIRM_ONCE 记 7 天） | 授权缓存失效 |
| 5 | 调 `exportNotes` | 手机号在返回里是 `138****8000`，logcat 里也看不到原文 | 脱敏没覆盖到该字段 |
| 6 | 调 `wipeAllNotes` | 不弹窗，直接 `FUNCTION_BLOCKED`，`retryable=false` | 阻断策略没生效 |
| 7 | 连调 9 次 `deleteNote` | 前 5 次正常，第 6 次起 `RATE_LIMITED` 且带 `retryAfterMs` | 限流窗口/计数错 |
| 8 | 弹窗放着不管，等超时 | 超时后**返回拒绝**；弹窗自动消失；点"允许"不会追溯改变结果 | 超时保护或残留弹窗处理有问题 |
| 9 | 把 App 切到后台再触发 | 无界面时**拒绝**，不静默放行 | Activity 生命周期处理有问题 |
| 10 | 打开 sample 首页的审计列表 | 每次调用一行，只有参数**形状/指纹**，没有原文 | 审计出口没接上（`GuardConfig.apply()` 漏了？） |
| 11 | 杀进程后重新打开 | 审计记录按设计丢失（RingBuffer 是内存实现） | 属于预期，若要持久化换 `AuditSink` 实现 |

## 已知需要在真机上确认的行为

- **第 2 项的参数名**：KSP 用函数 `simpleName` 作为 functionId，重载/同名函数会冲突（README 已列为已知限制）。真机上如果两个类都有 `deleteNote`，先确认命中哪一个。
- **第 3 项的风险文案**：`Risk` 枚举的展示文案是 sample 自己拼的，不是库的契约。
- **第 8 项的时序**：`confirmationTimeoutMillis` 默认 20s，sample 里设的同值；若你调到小于 1s，`DialogConfirmationHandler.MIN_TIMEOUT_MS` 会兜到 1s。

## 实测记录（发布前填）

| 日期 | 设备 / 系统 | 结论 | 备注 |
|---|---|---|---|
| — | — | 未测 | `0.1.0-alpha3` 尚未在真机上跑过 |

每一项通过就把这一行改成 `✅`，失败就写清失败项与 logcat 片段，然后开 issue。
