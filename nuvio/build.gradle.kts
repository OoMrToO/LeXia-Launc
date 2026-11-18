// NuvioTV's own code, copied unchanged under Nuvio's package and paths, plus the small "LeXia
// stand-in" files for the parts of Nuvio that LeXia does not have. Its namespace is Nuvio's, so the
// copied files find com.nuvio.tv.R and com.nuvio.tv.BuildConfig exactly as they do in Nuvio.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nuvio.tv"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // Nuvio scrapers use TMDB to resolve the selected title before searching providers.
        val tmdbApiKey = System.getenv("TMDB_API_KEY").orEmpty()
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        // NuvioTV's build settings its player reads (public build values; LeXia has no reports or Dolby Vision bridge).
        // IntroDB (introdb.app): free intro/recap/outro times by IMDb id; its API answers at api.introdb.app.
        buildConfigField("String", "INTRODB_API_URL", "\"https://api.introdb.app/\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"\"")
        buildConfigField("String", "SIMKL_APP_NAME", "\"nuvio\"")
        buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", "\"\"")
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", "false")
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", "false")
        buildConfigField("boolean", "IS_DEBUG_BUILD", "false")
        buildConfigField("boolean", "FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED", "false")
        buildConfigField("String", "APPLICATION_ID", "\"tv.lexia.launcher\"")
        buildConfigField("String", "VERSION_NAME", "\"1\"")
        buildConfigField("int", "VERSION_CODE", "1")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-datasource")
    exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-extractor")
}

dependencies {
    // Nuvio's own build of the JavaScript engine (the app module packs the same file).
    compileOnly(files("../app/libs/quickjs-kt-android-1.0.5-nuvio.aar"))
    // At NuvioTV's versions (its gradle/libs.versions.toml).
    api("androidx.datastore:datastore-preferences:1.1.1")
    api("com.squareup.moshi:moshi-kotlin:1.15.1")
    api("javax.inject:javax.inject:1")
    api("org.jsoup:jsoup:1.17.2")
    api("com.google.code.gson:gson:2.10.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.retrofit2:retrofit:2.9.0")
    api("com.squareup.retrofit2:converter-moshi:2.9.0")
    // Nuvio's screens are written with Compose for TV.
    api(platform("androidx.compose:compose-bom:2026.05.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.animation:animation")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.tv:tv-material:1.1.0-rc01")
    api("androidx.activity:activity-compose:1.11.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    api("io.coil-kt.coil3:coil-compose:3.3.0")
    api("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    api("dev.chrisbanes.haze:haze-android:1.7.2") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // Nuvio's own Media3 build (the app module packs it), for the Nuvio files that name its types.
    compileOnly(files(
        "../app/libs/lib-common-release.aar",
        "../app/libs/lib-datasource-release.aar",
        "../app/libs/lib-datasource-okhttp-release.aar",
        "../app/libs/lib-exoplayer-release.aar",
        "../app/libs/lib-exoplayer-hls-release.aar",
        "../app/libs/lib-extractor-release.aar",
        "../app/libs/lib-decoder-ffmpeg-release.aar"
    ))
    compileOnly("androidx.media3:media3-ui:1.8.0")
    compileOnly("androidx.media3:media3-decoder:1.8.0")
    compileOnly("androidx.media3:media3-database:1.8.0")
    compileOnly("androidx.media3:media3-exoplayer-dash:1.8.0")
    compileOnly("com.google.guava:guava:33.3.1-android")
    compileOnly("androidx.media3:media3-session:1.8.0")
    compileOnly("androidx.media3:media3-container:1.8.0")
    compileOnly("androidx.media3:media3-effect:1.8.0")
    compileOnly(files("../app/libs/nuvio-libass-release.aar", "../app/libs/nextlib-mediainfo-local.aar"))
    compileOnly("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

