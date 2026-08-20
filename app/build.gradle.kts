plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Room writes a JSON description of the schema for every version here.
// Committing those files makes a schema change reviewable in a diff — and this
// project needs them for a second reason: the JSON carries the identityHash
// that the prebuilt database must be stamped with before Room will open it.
room {
    schemaDirectory("$projectDir/schemas")
}

// Release signing is driven entirely by environment variables so that no
// keystore path or password ever lives in the repository. On a normal dev
// machine these are unset, the signing config is never created, and only debug
// builds (auto-signed with the local debug key) are possible.
val releaseKeystore = System.getenv("KEYSTORE_PATH")?.let(::file)

// Version is supplied by the release workflow, derived from the git tag (M5).
// The defaults are only used for local and debug builds.
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1-dev"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.dnoel.markeralerts"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dnoel.markeralerts"

        // minSdk 26 rather than ChecklistV1's 24: NotificationChannel is API 26+
        // and this app is built around notifications. Starting at 26 removes a
        // legacy code path that would otherwise need writing and testing for
        // devices this app will never realistically run on.
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Null when the keystore env vars are absent, which leaves local
            // debug work unaffected instead of failing the whole build script.
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources and manifest to
            // stand up a real application context on the JVM. Set now so the
            // DAO tests in M2 do not need a build-file change to run.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.location)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
