plugins {
    id("com.android.application")
}

android {
    namespace = "cool.rin.deepseekremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "cool.rin.deepseekremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    kotlin {
        jvmToolchain(21)
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "OldTargetApi",
        )
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
