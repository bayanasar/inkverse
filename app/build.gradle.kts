plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bayanasar.inkverse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bayanasar.inkverse"
        // takeScreenshotOfWindow() is API 34; the whole approach depends on it.
        minSdk = 34
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.8"
    }

    buildTypes {
        release {
            // Kotlin's stdlib is most of the APK without this; R8 takes it from ~2MB
            // back to the size the app actually is.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Unsigned by default; CI signs with the repo's release key when present.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

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
