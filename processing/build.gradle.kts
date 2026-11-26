plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.hnet.camera.processing"
    compileSdk {
        // 37.2: kept the same in every module
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
    testImplementation(libs.junit)
}
