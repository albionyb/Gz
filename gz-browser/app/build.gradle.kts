plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gzbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gzbrowser"
        minSdk = 26
        targetSdk = 34
        // Each CI build gets a higher versionCode so Android treats it as an update
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = (System.currentTimeMillis() / 60000).toInt()
        versionName = "0.0." + run.toString().padStart(2, '0')
    }

    // Release signing key comes from environment variables (GitHub secrets in CI).
    // Nothing secret is stored in the repo.
    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (!ksFile.isNullOrEmpty()) create("release") {
            storeFile = file(ksFile)
            storeType = "pkcs12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Multi-profile API (separate cookies / logins per profile)
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")   // PathParser, used by AdaptiveIcon
}
