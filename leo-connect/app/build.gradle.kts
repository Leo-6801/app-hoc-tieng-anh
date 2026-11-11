plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Số bản build: lấy từ GitHub Actions để mỗi APK mới có versionCode lớn hơn (cài đè được).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.leo.connect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leo.connect"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Dùng chung một khóa ký cho mọi bản build để có thể cài đè bản mới lên bản cũ.
    // Chỉ dùng cá nhân, cài trực tiếp (không đưa lên Google Play).
    signingConfigs {
        create("shared") {
            storeFile = file("leo-connect.keystore")
            storePassword = "leoconnect"
            keyAlias = "leoconnect"
            keyPassword = "leoconnect"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
