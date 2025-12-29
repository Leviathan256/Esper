plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.esper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.esper.app"
        minSdk = 26
        targetSdk = 35
        // Allow CI to override version metadata for Obtainium updates.
        val envVersionCode = System.getenv("ESPER_VERSION_CODE")?.toIntOrNull()
        val envVersionName = System.getenv("ESPER_VERSION_NAME")
        versionCode = envVersionCode ?: 1
        versionName = envVersionName ?: "0.1.0"
    }

    // Allow CI (and local dev) to inject a signing key via environment variables.
    // If no signing env is present, fall back to the default debug signing config
    // so an APK is still installable (though signatures may differ between machines).
    val signingStoreFile = System.getenv("ESPER_SIGNING_STORE_FILE")
    val signingStorePassword = System.getenv("ESPER_SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("ESPER_SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("ESPER_SIGNING_KEY_PASSWORD")

    if (
        !signingStoreFile.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()
    ) {
        signingConfigs {
            create("releaseFromEnv") {
                storeFile = file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Prefer env-provided signing (stable across CI runs), otherwise use debug signing.
            signingConfig = signingConfigs.findByName("releaseFromEnv") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Map view (OpenStreetMap). Requires INTERNET permission for live tiles.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}

