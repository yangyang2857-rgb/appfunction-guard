plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.yangyang2857.afguard.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.yangyang2857.afguard.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(project(":guard-core"))
    // 参考确认弹窗（含生命周期与超时处理），可直接用也可以照着写
    implementation(project(":guard-ui"))
    // 编译期生成校验器与元数据，运行时零反射解析
    ksp(project(":guard-ksp"))

    // 阶段 B：真正把函数暴露给系统 Agent。declare 用 KSP 生成 Service 与 schema。
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
}
