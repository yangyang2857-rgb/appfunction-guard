plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.yangyang2857.afguard.cli.MainKt")
    applicationName = "guard-cli"
}

dependencies {
    implementation(project(":guard-audit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
