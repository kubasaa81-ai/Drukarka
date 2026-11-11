plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numer = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.kubasaa.drukarka"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.kubasaa.drukarka"
        minSdk = 26
        targetSdk = 34
        versionCode = numer
        versionName = "1.$numer"
    }

    // stały klucz - każda nowa wersja instaluje się na starą (bez odinstalowania)
    signingConfigs {
        create("wlasny") {
            storeFile = file("klucz.jks")
            storePassword = "drukarka"
            keyAlias = "drukarka"
            keyPassword = "drukarka"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("wlasny")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wlasny")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
