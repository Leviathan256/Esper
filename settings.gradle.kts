pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // Every plugin VERSION lives here, not in the root build file.
    //
    // Why: Gradle configures every project on every invocation, and a
    // `plugins { id(...) version "..." }` block in the root build file makes the
    // ROOT project resolve those plugin markers — including the Android one from
    // google() — even when you only asked for `:engine:test`. Declaring versions
    // here means the root project resolves no plugin artifacts at all, so pure-JVM
    // engine tasks run without the Android SDK or the Google Maven repo.
    //
    // The two Kotlin entries must stay in step with kotlinCompilerExtensionVersion
    // in app/build.gradle.kts: the Compose compiler hard-fails against a Kotlin
    // version it doesn't know.
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.25"
        id("org.jetbrains.kotlin.jvm") version "1.9.25"
        id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Esper"
include(":app")
include(":engine")
