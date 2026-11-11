# CI

**状态：已启用。** 工作流位于 `.github/workflows/build.yml`，每次 push 到 `main` 自动运行。

## 做什么

| 步骤 | 说明 |
|------|------|
| Assemble | 装配 `guard-core`（AAR）、`guard-ksp`（JAR）、`sample`（APK） |
| Unit tests | 跑 `guard-core` 与 `guard-ksp` 的单元测试 |
| Dump generated KSP sources | 仅失败时执行，把生成的校验器源码打印到日志 |
| Upload sample APK | 上传 `sample-debug-apk` 产物 |

## 看结果

- Actions 页面：https://github.com/yangyang2857-rgb/appfunction-guard/actions/workflows/build.yml
- README 顶部的 `build` 徽章就是这条工作流的实时状态。

## 为什么曾经刻意没有这个文件

GitHub 对 `.github/workflows/*` 有额外校验：token 只有 `repo` scope 时，通过 REST API 提交该路径会返回 **404**（不是 403），看起来像仓库不存在，实际是权限不足。当时只能把工作流放在 `ci/build.yml` 暂存，等拿到带 `workflow` scope 的 token 再推。

现在已用带 `workflow` scope 的 token 直推成功，`ci/build.yml` 保留为同一份内容的镜像，方便本地比对；两者的差异应当始终为空，改动请同时更新。

## 本地复现

```bash
./gradlew :guard-core:assembleDebug :guard-ksp:test :sample:assembleDebug
```

需要 JDK 17 与 Android SDK Platform 36。
