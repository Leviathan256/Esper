plugins {
    id("com.android.application") version "8.5.2" apply false
    // Must stay in step with kotlinCompilerExtensionVersion in app/build.gradle.kts:
    // the Compose compiler refuses to run against a version it doesn't know.
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}

