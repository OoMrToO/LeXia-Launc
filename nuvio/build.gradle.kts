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
    // LeXia's player library version (the app module's), for the Nuvio files that name its types.
    compileOnly("androidx.media3:media3-common:1.11.1")
    compileOnly("androidx.media3:media3-exoplayer:1.11.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

