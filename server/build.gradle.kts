plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Esper backend (docs/SERVER.md): accounts, passkeys, characters, positions.
// Depends on the pure-JVM :engine so the server validates saves with the same
// SaveCodec the app writes, and later runs combat with the same CombatEngine.
// Not a dependency of :app, so the nightly APK build never compiles this.

// Same reasoning as engine/build.gradle.kts: source/target level, no toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application { mainClass.set("com.esper.server.MainKt") }

// Ship content/ beside bin/ and lib/; systemd WorkingDirectory makes "content" resolve.
distributions { main { contents { from("../content") { into("content") } } } }

// Ktor stays on 2.3.x: 3.x needs Kotlin 2, and the repo pins 1.9.25 for Compose.
val ktor = "2.3.13"

dependencies {
    implementation(project(":engine"))
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktor")
    implementation("io.ktor:ktor-server-forwarded-header-jvm:$ktor")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("com.yubico:webauthn-server-core:2.6.0")
    // Yubico asks for httpclient5 as the range [5.0.0,6), which resolves to whatever
    // alpha is newest; pin a release so builds are reproducible.
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.1!!")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("esper.contentDir", rootProject.layout.projectDirectory.dir("content").asFile.absolutePath)
    // ApiTest needs a real Postgres; it skips itself when this is unset (CI sets it).
    System.getenv("ESPER_TEST_DB_URL")?.let { environment("ESPER_TEST_DB_URL", it) }
}
