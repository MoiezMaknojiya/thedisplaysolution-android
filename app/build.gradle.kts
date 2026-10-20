plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        // The first release, signed with the release key (owner, 2026-10-10: "isko v1 se hi start karo"): the 1.x
        // builds before it were debug builds. Every update raises versionCode by one; Developer > TV App reads both.
        // 1.1 (2026-10-20): the first update through the in-app updater, to try it on a real TV.
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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