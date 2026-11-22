plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.mamamiefoodtruck.ecran"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.mamamiefoodtruck.ecran"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("ecran") {
            storeFile = file("ecran.jks")
            storePassword = "ecran1234"
            keyAlias = "ecran"
            keyPassword = "ecran1234"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ecran")
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
