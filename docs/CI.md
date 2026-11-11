# 启用 CI

`.github/workflows/build.yml` **不存在是有意为之**，不是漏了。

## 为什么

GitHub 对 `.github/workflows/*` 的写入有额外校验：token 只有 `repo` scope 时，通过 REST API 提交该路径会返回 **404**（不是 403，极具误导性），必须额外持有 `workflow` scope。

当前这个仓库是用一把 classic token 建的，没有 `workflow` scope，所以 workflow 文件暂存在 [`ci/build.yml`](../ci/build.yml)，等有权限的人贴一次即可。

## 启用（二选一）

### 方式 A：网页粘贴，约 30 秒

1. 打开 https://github.com/yangyang2857-rgb/appfunction-guard
2. **Add file → Create new file**
3. 文件名填 `.github/workflows/build.yml`
4. 把 [`ci/build.yml`](../ci/build.yml) 的全部内容粘进去
5. **Commit changes**，选 main 分支

提交完成即自动触发第一次构建。

### 方式 B：本地推送

```bash
git clone https://github.com/yangyang2857-rgb/appfunction-guard.git
cd appfunction-guard
mkdir -p .github/workflows
cp ci/build.yml .github/workflows/build.yml
git add .github/workflows/build.yml
git commit -m "ci: enable build workflow"
git push
```

如果 `git push` 报 `refusing to allow a personal access token to create or update workflow`，说明本地凭据也没 `workflow` scope，改用方式 A，或重新签发一把勾选了 `workflow` 的 token。

## 这个配置做什么

| 步骤 | 目的 |
|------|------|
| JDK 17 + Gradle 8.13 | AGP 8.13 要求 Gradle ≥ 8.13 |
| `android-actions/setup-android` | ubuntu-latest 不带 Android SDK，缺这一步 `assembleDebug` 必然失败 |
| `sdkmanager --licenses` | 首次拉 platform 36 / build-tools 需接受许可 |
| `gradle assembleDebug` | 编译 guard-core、guard-ksp、sample 三个模块 |
| `gradle testDebugUnitTest` | 当前无单测，`NO-SOURCE` 也算通过；留位置给后续用例 |
| `upload-artifact` | 把 sample 的 APK 传上来，可以直接装到手机上验 |

## 已知会挂的点

按可能性排序，第一条就是本次没有验证的部分：

1. **KSP 生成的类名与 `ValidatorRegistry.GENERATED_CLASS` 是否一致** —— 两边都写死为 `io.github.yangyang2857.afguard.generated.AfGuardGeneratedValidators`，若拼写或包名有偏差，编译能过但运行时校验器静默降级（审计里 `degraded = true`）。
2. **`sample` 的 `android-application` 插件版本** —— 已在 `gradle/libs.versions.toml` 注册为 AGP 8.13.0，与其他模块一致。
3. **`kotlinOptions` 在 Kotlin 2.2 下已废弃** —— 会有 warning，不影响构建；后续可换 `compilerOptions`。

第一次跑挂了不要紧，把日志贴到 issue 里，这正是路线图第 1 周要做的事：**先在真实环境跑一遍，再写踩坑**。
