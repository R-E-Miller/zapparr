plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "tv.zapparr"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.zapparr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // A public development key shared by every build, local or CI, so a newer APK always
        // installs over an older one. A launcher can't be uninstalled safely while it is Home.
        // Not for releases you hand to other people: see keystore/README.md.
        getByName("debug") {
            storeFile = rootProject.file("keystore/zapparr-dev.p12")
            storePassword = "zapparr"
            keyAlias = "zapparr-dev"
            keyPassword = "zapparr"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback.grid)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
