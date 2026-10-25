plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.opensource.routesim.probe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.opensource.routesim.probe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
