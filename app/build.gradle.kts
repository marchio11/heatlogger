plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Il numero di build arriva da GitHub Actions: ogni APK nuovo si installa sopra il precedente
val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "io.github.marchio11.heatlogger"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.marchio11.heatlogger"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Chiave di firma personale: sempre la stessa, così gli aggiornamenti si installano sopra
    // e Google riconosce l'app per l'accesso a Drive (impronta SHA-1 registrata nella Cloud Console).
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("keystore/heatlogger.jks")
            storePassword = "heatlogger-personal-key"
            keyAlias = "heatlogger"
            keyPassword = "heatlogger-personal-key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
