plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.veeshays.pulse"
    compileSdk = 34

    defaultConfig {
        // Application ID + display label are centralized in ui/Brand.kt (BRAND.*);
        // this block is the single Gradle-side mirror for the future rebrand pass.
        applicationId = "com.veeshays.pulse"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging { resources { excludes += setOf("/META-INF/**") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.4.1"
    val lifecycle = "2.8.6"
    val datastore = "1.1.1"
    val composeBom = "2024.09.00"

    // Playback core (background audio/video + session + notification). Non-negotiable.
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3") // online enrichment only

    // UI (One UI-friendly Material3, edge-to-edge).
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // media3-ui-compose intentionally absent: first published at 1.6.x which
    // requires compileSdk 35; the video stage hosts PlayerView via AndroidView
    // until the SDK-35 bump (polish pass). See Screens.kt.
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Lifecycle-aware controller observation (no polling, no leaks).
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycle")

    // Persistence: DataStore JSON mirrors PC localStorage caps (no SQLite until 20k+ need).
    implementation("androidx.datastore:datastore-preferences:$datastore")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Artwork only (thumbnails + covers, memory/disk cached, no crossfade bloat).
    implementation("io.coil-kt.coil3:coil-compose:3.0.0")
    implementation("io.coil-kt.coil3:coil-video:3.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
