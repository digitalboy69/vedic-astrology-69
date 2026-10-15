plugins {
    id("com.android.application")
}

android {
    namespace = "com.builder.vedicastrology69"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.builder.vedicastrology69"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("ci") {
            storeFile = rootProject.file("my-upload-key.jks")
            storePassword = System.getenv("STORE_PASSWORD") ?: ""
            keyAlias = "upload"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("my-upload-key.jks").exists()) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}
