import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.github.yangyang2857.afguard.ui"
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
                artifactId = "guard-ui"
                artifact(javadocJar)
                pom {
                    name.set("AppFunction Guard :: ui")
                    description.set("Reference confirmation dialog for AppFunction Guard: lifecycle aware, timeout safe, no deadlock on main thread")
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
    api(project(":guard-core"))
}
