plugins {
    id("com.android.application")
}

android {
    namespace = "app.book.shelf"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.book.shelf"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.4." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // A fixed prototype key (committed on purpose) so each new build installs over the last one.
    // Not for store releases.
    signingConfigs {
        create("proto") {
            storeFile = file("proto.keystore")
            storePassword = "bookproto"
            keyAlias = "proto"
            keyPassword = "bookproto"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("proto")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
