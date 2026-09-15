plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.roundsalmon4.phonetv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.roundsalmon4.phonetv"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("fromKeystore") {
            val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: error("RELEASE_KEYSTORE_PASSWORD not set")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: error("RELEASE_KEY_ALIAS not set")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: error("RELEASE_KEY_PASSWORD not set")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("fromKeystore")
            }
            checkReleaseBuilds = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.java.websocket)
}
