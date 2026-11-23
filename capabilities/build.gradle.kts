plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.hnet.camera.capabilities"
    compileSdk {
        // 37.2: Compose 1.13 (BOM 2026.11.01) needs at least 37.1; kept the same in every module
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
