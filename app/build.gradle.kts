plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imaxcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imaxcam"
        // Camera2 10-bit dynamic range profiles (HDR10+/HDR10/HLG10) require API 33.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // No androidx or Material Components: at minSdk 33 the framework already provides
    // everything the UI needs, and keeping the graph empty keeps the APK small and the
    // view-inflation path free of interceptors.
    testImplementation(libs.junit)
}
