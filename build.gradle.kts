import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension

// 根构建脚本：只声明插件版本，不应用
//
// 版本说明：
// - AGP / Kotlin / KSP 的版本号请在 Android Studio 里用 "AGP Upgrade Assistant" 校准一次，
//   或直接改 gradle/libs.versions.toml。这组数字是 2026-11 的合理值，但你的 IDE 可能已有更新版本。
// - compileSdk 是 37：androidx.appfunctions 1.0.0-rc01 起要求 compileSdk 37 + AGP 9.1+，
//   而 AGP 9 又要求 Gradle 9.1+，三者必须一起动。AppFunctions 本身仍然是 Android 16+ 的平台能力。
//
// 发布：四个可发布模块（guard-audit / guard-core / guard-ksp / guard-ui）统一用
// group:io.github.yangyang2857-rgb，版本从这里下发给所有子项目，避免各写各的。
// 发布流程见 docs/PUBLISHING.md。

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "io.github.yangyang2857-rgb"
    version = "0.1.0-alpha3"
}

// ---------------------------------------------------------------------------
// Maven Central（Central Portal 上传包）
//
// 目标：不碰 Sonatype 的 Nexus staging、不装 GPG 图形工具，只产出一个可以直接
// 拖进 https://central.sonatype.com/publishing 的 zip。
//
// 有签名密钥时（SIGNING_KEY / -PsigningKey）会把 .asc 一起生成，bundle 即为可上架状态；
// 没有密钥时仍然可以打包，但只够用来检查目录结构（CI 会用一次性密钥走完整流程）。
//
// 目录结构遵循 Portal 要求：
//   io/github/yangyang2857-rgb/<artifactId>/<version>/<artifactId>-<version>[.jar|.aar|.pom|-sources.jar|-javadoc.jar][.asc]
// ---------------------------------------------------------------------------

val centralRepoDir = rootProject.layout.buildDirectory.dir("central-repo")
val signingKeyValue = (findProperty("signingKey") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("SIGNING_KEY")?.takeIf { it.isNotBlank() }
val signingPasswordValue = (findProperty("signingPassword") as String?)
    ?: System.getenv("SIGNING_PASSWORD")

subprojects {
    val sub: Project = this

    sub.plugins.withId("maven-publish") {
        apply(plugin = "signing")

        sub.extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "centralStaging"
                    url = uri(centralRepoDir.get().asFile)
                }
            }
        }

        // 没有密钥时必须显式关掉，否则任何被挂进任务图的 sign 任务都会报
        // "Could not read PGP secret key"。
        sub.extensions.configure<SigningExtension> {
            isRequired = signingKeyValue != null
        }

        if (signingKeyValue != null) {
            // guard-core / guard-ui 的 publication 是在 afterEvaluate 里建的，这里必须等
            // 全部项目脚本求值完再签名，否则会签到一个空集合上。
            // 另外这里不能用隐式的 extensions：projectsEvaluated 的接收者是 Gradle（它本身
            // 也是 ExtensionAware），会解析到 Gradle 实例上，那里的扩展只有 ExtraProperties。
            sub.gradle.projectsEvaluated {
                sub.extensions.configure<SigningExtension> {
                    useInMemoryPgpKeys(signingKeyValue, signingPasswordValue)
                    // 这个时刻所有 publication 都已创建，逐个签即可（sign 只接受 vararg）。
                    sub.extensions.getByType(PublishingExtension::class.java)
                        .publications.forEach { sign(it) }
                }
            }
        }
    }
}

val cleanCentralRepo = tasks.register<Delete>("cleanCentralRepo") {
    group = "publishing"
    description = "清空 bundle 暂存目录"
    delete(centralRepoDir)
}

// 各模块的 publication 名不统一（guard-audit / guard-ksp 叫 maven，guard-core / guard-ui 叫 release），
// 所以按任务名匹配，避免硬编码写错。
val centralPublishTasks = provider {
    subprojects.flatMap { p ->
        p.tasks.matching { it.name.startsWith("publish") && it.name.endsWith("ToCentralStagingRepository") }
    }
}

subprojects {
    tasks.matching { it.name.startsWith("publish") && it.name.contains("CentralStaging") }
        .configureEach { mustRunAfter(cleanCentralRepo) }
}

// 把暂存目录打成 Portal 能直接吃的 zip；maven-metadata.xml 不是制品，排除掉。
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "生成 Maven Central Portal 上传包 build/distributions/central-bundle.zip"
    dependsOn(cleanCentralRepo)
    dependsOn(centralPublishTasks)
    from(centralRepoDir)
    exclude("**/maven-metadata.xml", "**/maven-metadata.xml.*")
    archiveFileName.set("central-bundle.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))
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
