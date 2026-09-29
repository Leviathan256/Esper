plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Pure-JVM game engine: every rule, roll, stat formula and grid computation lives
// here, with zero Android imports, so it is unit-testable without an SDK or an
// emulator. `:app` renders what this module decides.

// Deliberately NOT `kotlin { jvmToolchain(17) }`: a toolchain spec makes Gradle
// look for an exact JDK 17 and fall back to a foojay download, which is blocked in
// this project's development environment (the local JDK is 21). Setting the
// source/target level instead compiles 17-compatible bytecode on whatever JDK is
// present, matching CI's JDK 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `api`, not `implementation`: @Serializable types in this module's public API
    // (JobDefinition, CharacterState, …) need kotlinx.serialization on the
    // consumer's compile classpath too.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

tasks.test {
    useJUnitPlatform()
    // Engine tests validate the SHIPPED content files straight off disk, so
    // malformed game data fails the PR check rather than a player's phone. The
    // app reads the same directory from its assets.
    systemProperty(
        "esper.contentDir",
        rootProject.layout.projectDirectory.dir("content").asFile.absolutePath,
    )
}
