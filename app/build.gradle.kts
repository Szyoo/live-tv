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
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 2
        versionName = (project.findProperty("versionName") as String?) ?: "0.2.0"

        val updateUrl = (project.findProperty("updateUrl") as String?)
            ?: "https://github.com/Szyoo/live-tv/releases/latest/download/update.json"
        buildConfigField("String", "UPDATE_URL", "\"" + updateUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // 签名：三个环境变量齐全才用正式签名，否则退回 debug 签名（仅本地测试）
    val ksFile = System.getenv("LIVE_KEYSTORE_FILE")
    val ksPass = System.getenv("LIVE_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("LIVE_KEY_ALIAS")
    val haveReleaseKey = !ksFile.isNullOrBlank() && !ksPass.isNullOrBlank() && !ksAlias.isNullOrBlank()
    if (!haveReleaseKey && project.findProperty("requireReleaseKey") == "true") {
        val missing = listOf("LIVE_KEYSTORE_FILE" to ksFile, "LIVE_KEYSTORE_PASSWORD" to ksPass, "LIVE_KEY_ALIAS" to ksAlias)
            .filter { it.second.isNullOrBlank() }.joinToString { it.first }
        throw GradleException("-PrequireReleaseKey=true 但签名环境变量缺失: $missing。拒绝退回 debug 签名。")
    }
    if (haveReleaseKey) {
        signingConfigs.create("releaseKey") {
            storeFile = file(ksFile!!)
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksPass
        }
    } else {
        logger.warn("********** WARNING: LIVE_KEYSTORE_FILE/LIVE_KEYSTORE_PASSWORD/LIVE_KEY_ALIAS 未设置，release 使用 DEBUG 签名（不可发布） **********")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (haveReleaseKey) "releaseKey" else "debug")
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
    testImplementation("org.json:json:20240303")
}
