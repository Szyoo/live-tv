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
        versionCode = 2
        versionName = "0.2.0"

        val sourceUrl = (project.findProperty("defaultSourceUrl") as String?) ?: ""
        buildConfigField("String", "DEFAULT_SOURCE_URL", "\"" + sourceUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 第三期换正式签名；目前临时用 debug 签名以便本地安装测试
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.recyclerview)

    testImplementation(libs.junit)
}
