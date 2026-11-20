plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mandeep.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mandeep.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "5.3"
    }

    // Same key on every GitHub build, so new APKs install over the old one
    // (no more "App not installed").
    signingConfigs {
        create("jarvis") {
            storeFile = rootProject.file("jarvis.keystore")
            storePassword = "jarvis123"
            keyAlias = "jarvis"
            keyPassword = "jarvis123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("jarvis")
        }
        release {
            signingConfig = signingConfigs.getByName("jarvis")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No extra libraries: only the Android SDK + Kotlin, so the build stays simple.
dependencies {
}
