plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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
}

dependencies {
    // 审计模型是纯 JVM 的，用 api 暴露出去：sample / guard-ui 都要能拿到 AuditRecord
    api(project(":guard-audit"))
    implementation(libs.androidx.core)
    compileOnly(libs.androidx.annotation)

    // 编译期生成参数校验器，运行时不依赖 KSP
    ksp(project(":guard-ksp"))
}
