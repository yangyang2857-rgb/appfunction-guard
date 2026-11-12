import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "io.github.yangyang2857.afguard"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 拦截链的单测跑在 JVM 上（Robolectric 提供真实 Bundle / SystemClock），
    // 目的是把「装到手机上一项项点按钮」变成可回归的断言。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // 只发布 release AAR，并附带 sources，便于使用方在 IDE 里读到 KDoc
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("docs/JAVADOC.md"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "guard-core"
                artifact(javadocJar)
                pom {
                    name.set("AppFunction Guard :: core")
                    description.set("Intercept, gate and audit AppFunctions calls on Android 16 (Android API 36)")
                    url.set("https://github.com/yangyang2857-rgb/appfunction-guard")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("yangyang2857-rgb")
                            name.set("Wang Sunyang")
                            url.set("https://github.com/yangyang2857-rgb")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/yangyang2857-rgb/appfunction-guard.git")
                        developerConnection.set("scm:git:ssh://github.com/yangyang2857-rgb/appfunction-guard.git")
                        url.set("https://github.com/yangyang2857-rgb/appfunction-guard")
                    }
                }
            }
        }
    }
}

dependencies {
    // 审计模型是纯 JVM 的，用 api 暴露出去：sample / guard-ui 都要能拿到 AuditRecord
    api(project(":guard-audit"))
    implementation(libs.androidx.core)
    compileOnly(libs.androidx.annotation)

    // 编译期生成参数校验器，运行时不依赖 KSP
    ksp(project(":guard-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
