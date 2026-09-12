plugins {
    id("com.android.application")
}

android {
    namespace = "com.bayanasar.inkverse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bayanasar.inkverse"
        // takeScreenshotOfWindow() is API 34; the whole approach depends on it.
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned by default; CI signs with the repo's release key when present.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        val storePath = System.getenv("KEYSTORE_FILE")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
}
