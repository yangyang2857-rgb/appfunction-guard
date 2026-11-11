// 根构建脚本：只声明插件版本，不应用
//
// 版本说明：
// - AGP / Kotlin / KSP 的版本号请在 Android Studio 里用 "AGP Upgrade Assistant" 校准一次，
//   或直接改 gradle/libs.versions.toml。这组数字是 2026-11 的合理值，但你的 IDE 可能已有更新版本。
// - compileSdk 必须是 36（Android 16），AppFunctions 是 Android 16+ 的平台能力。
//
// 发布：四个可发布模块（guard-audit / guard-core / guard-ksp / guard-ui）统一用
// group:io.github.yangyang2857，版本从这里下发给所有子项目，避免各写各的。
// 发布流程见 docs/PUBLISHING.md。

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "io.github.yangyang2857"
    version = "0.1.0-alpha3"
}

// 一次装到本地仓库，用于验证 POM / sources / javadoc 三件套是否齐全（不需要任何密钥）。
tasks.register("publishAllToLocal") {
    group = "publishing"
    description = "把全部可发布模块装进 ~/.m2，供本地联调与 CI 校验"
    dependsOn(
        ":guard-audit:publishToMavenLocal",
        ":guard-core:publishToMavenLocal",
        ":guard-ksp:publishToMavenLocal",
        ":guard-ui:publishToMavenLocal"
    )
}
