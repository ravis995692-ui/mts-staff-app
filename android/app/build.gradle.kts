import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing: read from android/keystore.properties (local builds in Android Studio)
// or from environment variables (GitHub Actions). If neither is present the release APK is built unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "MTS_KEYSTORE_FILE")

android {
    namespace = "com.meridiantheschool.mtsstaff"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meridiantheschool.mtsstaff"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The web app the native shell opens. Change this one line to point the app at another address.
        buildConfigField("String", "APP_URL", "\"https://ravis995692-ui.github.io/mts-staff-app/beta/\"")
        // Pages under this address open inside the app; every other link opens in the matching app (WhatsApp, phone, browser).
        buildConfigField("String", "APP_SCOPE", "\"https://ravis995692-ui.github.io/mts-staff-app/\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "MTS_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MTS_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MTS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)
}
