plugins {
    id("com.android.application")
}

val releaseStoreFile = providers.environmentVariable("DEEPSEEK_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("DEEPSEEK_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("DEEPSEEK_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("DEEPSEEK_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "cool.rin.deepseekremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "cool.rin.deepseekremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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
