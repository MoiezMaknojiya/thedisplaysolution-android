import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key's place and passwords, from keystore.properties at the project's root (git-ignored; copy
// keystore.properties.example). Without it a release build is left unsigned, and Android Studio's
// Build > Generate Signed App Bundle or APK signs it instead. See RELEASING.md.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.thedisplaysolution.player"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.thedisplaysolution.player"
        minSdk = 23
        // 36, not 37, on purpose. Holding Back for five seconds opens the player's menu, and that needs Back as a
        // key event, which Android 16 and later keep only for apps that opt out of predictive back
        // (android:enableOnBackInvokedCallback="false" in the manifest): documented for 36. Android 17's changes for
        // apps targeting 37 also tighten how an activity may be started, which is what the watchdog does. Moving to
        // 37 means trying both again on an Android 17 device first.
        targetSdk = 36
        // The first release given to customers, signed with the release key (owner, 2026-10-20: "versioncode ko 1 kardo
        // aur version ko 1.0 kar do phir mein sub customer ko dena shuru karta hu"). The 1.0 to 1.2 before it were tried
        // on the owner's Fire Stick alone, to prove the in-app updater, and the 1.x builds before those were debug builds.
        // Every update raises versionCode by one; Developer > TV App, the Amazon Appstore and Google Play read both.
        versionCode = 1
        versionName = "1.0"
    }

    // Two builds of the one app (RELEASING.md): "site" for the website's Developer > TV App and the Amazon Appstore,
    // which updates itself from the website, and "play" for Google Play, which has no updater of its own (Play lets no app
    // update itself) and is for televisions alone. Both are com.thedisplaysolution.player, with the same version.
    flavorDimensions += "store"
    productFlavors {
        create("site") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystoreProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
