import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

// Central 强制要求 javadoc artifact；这里给一份说明占位（见 docs/JAVADOC.md）。
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("docs/JAVADOC.md"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "guard-audit"
            artifact(javadocJar)
                pom {
                    name.set("AppFunction Guard :: audit")
                    description.set("Audit model and CSV/JSON export for AppFunction Guard — pure JVM, no Android dependency")
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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
