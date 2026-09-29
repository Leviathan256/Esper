// Deliberately empty: the root project applies no plugins.
//
// All plugin versions are declared in settings.gradle.kts's
// `pluginManagement { plugins { … } }` block. Keep it that way. A
// `plugins { id("com.android.application") version "…" apply false }` block here
// makes the ROOT project resolve the Android plugin marker from google() on every
// invocation, including `gradle :engine:test`, which breaks engine-only builds in
// any environment without access to Google's Maven repo.
//
// Still load-bearing wherever the Kotlin version is written down: it must stay in
// step with kotlinCompilerExtensionVersion in app/build.gradle.kts, because the
// Compose compiler refuses to run against a Kotlin version it doesn't know.
