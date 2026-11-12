# Changelog

以 [Semantic Versioning](https://semver.org/) 管理。

## [Unreleased]

### Added
- `sample` 阶段 B 平台接线：`BaseNoteAppFunctionService` 用 `@AppFunctionServiceEntryPoint` 标注抽象类，KSP 生成 `NoteAppFunctionService` 与 `res/xml/note_app_function_service.xml`；`listNotes` / `deleteNote` / `exportNotes` / `wipeAllNotes` 四个函数真正暴露给系统 Agent，manifest 补齐 `BIND_APP_FUNCTION_SERVICE` 权限、`AppFunctionService` action 与 schema property
- 每个 AppFunction 都是 `suspend` + `Dispatchers.IO`，并统一经 `AfGuard.intercept` 拦截；被拒绝时返回 `AFGUARD_BLOCKED` + 结构化错误，而不是抛不透明异常

### Changed
- 构建体系升到 **AGP 9.1.1 / Gradle 9.3.1 / compileSdk 37**。AGP 9 是主版本升级（内置 Kotlin、新 DSL、Gradle 9），这次先用 `android.builtInKotlin=false` + `android.newDsl=false` 保住现有 `kotlin-android` 写法，让改动面停在"版本 + 平台"，构建写法迁移单独做一次
- 补删 `sample` 里 alpha3 声称删过、实际仍在的 `DialogConfirmationHandler` 旧副本（无人引用，缺主线程防死锁与超时收窗），`sample/README.md` 里"`CountDownLatch` + `runOnUiThread` 桥接"的描述改为指向 `guard-ui` 的实现，并写清 attach/detach、超时收窗、主线程判否四条约束
- CI 改用 Gradle 9.3.1，Android SDK Platform 装 37（保留 36 供旧设备镜像）

### Why
`androidx.appfunctions` 1.0.0-rc01 要求 compileSdk 37 + AGP 9.1+，不升就接不上阶段 B 的真机验证；而 AGP 9.1.1 又要求 Gradle ≥ 9.3.1。

## [0.1.0-alpha3] - 2026-11-12

把"只有核心库"补成"能发、能用、能验收"。CI 仍然只证明构建与单测，真机那一格依旧空着。

### Added
- `guard-audit`：审计模型（`AuditRecord` / `DecisionKind` / `AuditSink` / `RingBufferAuditSink`）与 CSV、JSONL 双向转换、summary 统计，不依赖 Android
- `guard-ui`：`DialogConfirmationHandler` 参考实现，支持 `attach`/`detach` 跟随 Activity 生命周期；主线程调用直接判否，超时后返回拒绝并 dismiss 残留弹窗
- `guard-cli`：`summary` / `export --format csv|jsonl`，支持文件或 stdin，解析失败的行计入报告而非静默跳过
- 四个可发布模块的 Maven 发布配置：POM 元数据齐全、release AAR + sources + javadoc artifact、`publishAllToLocal` 一键装本地
- `docs/DEVICE_TEST.md` 真机验收清单（11 项）、`docs/PUBLISHING.md` 发布流程、`docs/JAVADOC.md` javadoc 占位说明

### Changed
- `guard-core` 的审计类型改为 `typealias` 指向 `guard-audit`，避免同一模型两处定义
- `sample` 改用 `guard-ui` 的确认弹窗，删掉自带的重复实现
- CI 扩到四模块装配 + 三模块单测 + CLI 冒烟 + 发布元数据校验
- 发布坐标 group 定为 `io.github.yangyang2857-rgb`（Central 只授权与 GitHub 账号名一致的 `io.github.*` 命名空间）

### Known limitations
- 仍未在真机跑端到端验证，验收清单待填
- `-javadoc.jar` 是说明占位，0.2.0 接 Dokka
- Maven Central 上传未执行（缺 Sonatype 凭据与 GPG 密钥）

## [0.1.0-alpha2] - 2026-11-12

第一次让 CI 真正跑通。alpha 版本是"能编译过"，这一版是"能构建、能出 APK、能测"。

### Added
- `.github/workflows/build.yml` 正式启用：装配 `guard-core` / `guard-ksp` / `sample`，跑单元测试，产出 `sample-debug-apk`
- CI 失败时自动 dump KSP 生成源码，省掉靠猜生成代码的来回

### Fixed
- `annotation class Guard` 与 `object Guard` 在同一包重名 → 入口对象改名 `AfGuard`，注解保持更短的 `@Guard`；README / 中文文档 / sample / W1 文章同步
- `CallerAllowList.isAllowed()` 在表达式函数体里用 `return`，改为块体
- `PolicyEngine` 构造收到的是 `Long`（`GuardConfig.clock()` 的返回值）而不是 `() -> Long`，改为传函数引用 `GuardConfig.clock`
- KSP `@OneOfVal` 生成的错误消息里直接拼了带引号的列表，产出 `"one of "plain", "json""`，字符串被腰斩；消息改用裸值拼接
- KSP `@Pattern` 生成代码对 `$` 转义了两层，`"^[A-Za-z0-9_-]{1,64}$"` 的结尾锚点变成字面 `$`，正则在真机上会放行非法 id

### Known limitations
- 仍未在真机跑端到端验证，CI 只覆盖构建与单元测试。

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
