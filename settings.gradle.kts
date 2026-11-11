import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.maven.apache.org/maven2")
        maven("https://plugins.gradle.org/m2")
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://repo.maven.apache.org/maven2")
    }
}

rootProject.name = "appfunction-guard"

include(":guard-core")
include(":guard-ksp")
include(":sample")
