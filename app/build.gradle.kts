plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        isCoreLibraryDesugaringEnabled = true   // the parsers library targets newer Java APIs
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/README.md", "META-INF/NOTICE.md", "META-INF/NOTICE*", "META-INF/LICENSE*",
                "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Futon's manga / comics sources, at the same commit Futon pins (AppFuton/Futon gradle/libs.versions.toml)
    implementation("com.github.clquwu:kotatsu-parsers-redo:dc223c3d80") {
        exclude(group = "org.json", module = "json")   // Android has its own org.json
    }
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
