# Changelog

以 [Semantic Versioning](https://semver.org/) 管理。

## [0.1.0-alpha] - 2026-11-12

首版。目标只有一个：**把拦截链跑通**，不追求功能齐全。

### Added
- `@Guard` 函数级授权注解（`ALWAYS_DENY` / `CONFIRM_EACH` / `CONFIRM_ONCE` / `ALLOW`）
- `@RateLimit` 令牌桶限流，按函数 × 调用方分桶，支持突发
- `@TextLimit` / `@IntRange` / `@Pattern` / `@OneOfVal` 参数约束注解
- `@Sensitive` 敏感参数标记与 `PiiKind` 枚举
- `AfGuard.intercept()` 拦截链：白名单 → 策略 → 限流 → 校验 → 确认 → 执行 → 脱敏 → 审计
- `GuardError` 结构化错误协议（`afg_error` JSON），每个错误码带 `retryable` 与 `hint`
- `Redactor` 内置 PII 检测（手机号 / 邮箱 / 身份证 / 银行卡 / 经纬度）
- `RingBufferAuditSink` 内存环形审计（默认保留 5000 条）
- `CallerAllowList` 调用方白名单（默认关闭，设置后生效）
- `BundleCodec` 结果编解码层
- `guard-ksp` 注解处理器：生成 `AfGuardGeneratedValidators`（校验 + 元数据一张表）

### Design notes
- 库**不 import 任何 `android.app.appfunctions.*`**。平台 API 还处在 alpha，把变动挡在库外。
- 审计日志**不记录参数原文**，只记录指纹（SHA-256 前 8 字节）。
- `CONFIRM_ONCE` 对 `Risk.CRITICAL` 不生效 —— 最危险的动作永远每次确认。

### Known limitations
- 未在真机跑通端到端验证（开发环境无 Android SDK），首个真机验证待做。
- 同进程 Executor 场景下无法区分调用方，退化为不区分。
- `guard-ui`（确认弹窗）与 `guard-cli`（审计导出）尚未开始。
- 未做 Maven Central 发布，暂用 JitPack。
