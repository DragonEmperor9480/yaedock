plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release version lives in yae_version.json at the repository root.
val yaeVersion: String = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
    .find(rootProject.file("../yae_version.json").readText())
    ?.groupValues?.get(1)
    ?: error("yae_version.json has no \"version\"")

// 1.2.3 becomes 10203, so every release has a larger versionCode than the last.
val yaeVersionCode: Int = yaeVersion.substringBefore('-').split('.')
    .map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// Release signing comes from the environment (set by the release workflow).
val releaseKeystore: String? = System.getenv("YAEDOCK_KEYSTORE")

android {
    namespace = "com.dragonemperor9480.yaedock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dragonemperor9480.yaedock"
        minSdk = 26
        targetSdk = 36
        versionCode = yaeVersionCode
        versionName = yaeVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("YAEDOCK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YAEDOCK_KEY_ALIAS")
                keyPassword = System.getenv("YAEDOCK_KEY_PASSWORD")
            }
        }
    }
    // Release builds make one APK per ABI, so a phone only downloads its own
    // native libraries (mostly ML Kit's barcode scanner). Debug stays one APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidsvg)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
