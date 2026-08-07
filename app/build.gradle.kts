import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Personal defaults live in gitignored personal.defaults.properties (see .example).
// Without that file, debug/release ship empty defaults like a clean install.
val personalDefaults = Properties().apply {
    val f = rootProject.file("personal.defaults.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun personal(key: String, fallback: String = ""): String =
    personalDefaults.getProperty(key, fallback).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.vizvag.shieldvideo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vizvag.shieldvideo"
        minSdk = 28
        targetSdk = 34
        versionCode = 225
        versionName = "2.5.9"

        // Personalized defaults from personal.defaults.properties when present.
        // The "clean" build type blanks every one so a distributable APK ships
        // with no API keys, IPs, accounts, or subscriptions baked in.
        buildConfigField("boolean", "CLEAN_BUILD", "false")
        buildConfigField("String", "DEFAULT_TRAKT_CLIENT_ID", "\"${personal("DEFAULT_TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "DEFAULT_TMDB_API_KEY", "\"${personal("DEFAULT_TMDB_API_KEY")}\"")
        buildConfigField("String", "DEFAULT_TMDB_READ_TOKEN", "\"${personal("DEFAULT_TMDB_READ_TOKEN")}\"")
        buildConfigField("String", "DEFAULT_NAS_HOST", "\"${personal("DEFAULT_NAS_HOST")}\"")
        buildConfigField("String", "DEFAULT_NAS_USER", "\"${personal("DEFAULT_NAS_USER")}\"")
        buildConfigField("String", "DEFAULT_HA_WEBHOOK", "\"${personal("DEFAULT_HA_WEBHOOK")}\"")
        buildConfigField("String", "DEFAULT_HUE_BRIDGE_IP", "\"${personal("DEFAULT_HUE_BRIDGE_IP")}\"")
        buildConfigField("String", "DEFAULT_TRAKT_USERNAME", "\"${personal("DEFAULT_TRAKT_USERNAME")}\"")
        buildConfigField("String", "DEFAULT_BACKGROUND_FOLDER", "\"${personal("DEFAULT_BACKGROUND_FOLDER")}\"")
        buildConfigField("String", "DEFAULT_IPTV_M3U", "\"${personal("DEFAULT_IPTV_M3U")}\"")
        buildConfigField("String", "DEFAULT_IPTV_EPG", "\"${personal("DEFAULT_IPTV_EPG")}\"")
        buildConfigField("String", "DEFAULT_IPTV_EPG_AI_KEY", "\"${personal("DEFAULT_IPTV_EPG_AI_KEY")}\"")
        buildConfigField("String", "DEFAULT_MUSIC_PATH", "\"${personal("DEFAULT_MUSIC_PATH", "/music")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // Distributable build: no personalization baked in. A new user must
        // enter their own NAS, accounts, API keys, and IPTV playlist in
        // Settings. Debug-signed so it installs the same way as `debug`.
        create("clean") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "CLEAN_BUILD", "true")
            buildConfigField("String", "DEFAULT_TRAKT_CLIENT_ID", "\"\"")
            buildConfigField("String", "DEFAULT_TMDB_API_KEY", "\"\"")
            buildConfigField("String", "DEFAULT_TMDB_READ_TOKEN", "\"\"")
            buildConfigField("String", "DEFAULT_NAS_HOST", "\"\"")
            buildConfigField("String", "DEFAULT_NAS_USER", "\"\"")
            buildConfigField("String", "DEFAULT_HA_WEBHOOK", "\"\"")
            buildConfigField("String", "DEFAULT_HUE_BRIDGE_IP", "\"\"")
            buildConfigField("String", "DEFAULT_TRAKT_USERNAME", "\"\"")
            buildConfigField("String", "DEFAULT_BACKGROUND_FOLDER", "\"\"")
            buildConfigField("String", "DEFAULT_IPTV_M3U", "\"\"")
            buildConfigField("String", "DEFAULT_IPTV_EPG", "\"\"")
            buildConfigField("String", "DEFAULT_IPTV_EPG_AI_KEY", "\"\"")
            buildConfigField("String", "DEFAULT_MUSIC_PATH", "\"\"")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-android:1.7.36")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.jthink:jaudiotagger:3.0.1")

    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    // Software AC-3 / E-AC-3 / DTS when the device has no hardware decoder (Chromecast, etc.).
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
