plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.geelyqdlink.lab"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.geelyqdlink.lab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.5-lab"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(project(":qdlink-core"))
}

