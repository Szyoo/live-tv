plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "xyz.szyyw.livetv"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.szyyw.livetv"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        val sourceUrl = (project.findProperty("defaultSourceUrl") as String?) ?: ""
        buildConfigField("String", "DEFAULT_SOURCE_URL", "\"" + sourceUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.appcompat)

    testImplementation(libs.junit)
}
