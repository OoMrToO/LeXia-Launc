plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.lexia.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.lexia.launcher"
        minSdk = 24
        targetSdk = 36
        // TVs run ARM; the x86 copies of the native parts (FFmpeg, QuickJS) only made the APK larger.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // 2000 above the build number: installs over the LeXia + Nuvio trial (code 1000+) of 16 November.
        versionCode = 2000 + (System.getenv("GITHUB_RUN_NUMBER") ?: "4").toInt()
        versionName = "0.4"
        buildConfigField("String", "BUILD_NO", "\"${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}\"")
        // The address of the files site (film list, updates); the build workflow looks it up.
        buildConfigField("String", "SITE", "\"${System.getenv("LEXIA_SITE") ?: "https://lexia.invalid"}\"")
        // Nuvio scrapers use TMDB to resolve the selected title before searching providers.
        val tmdbApiKey = System.getenv("TMDB_API_KEY").orEmpty()
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    // "full" is the normal app. "setup" leaves out the Home button service, which Play Protect
    // blocks on a first install; it reports build 0 so it updates itself to "full" right away.
    flavorDimensions += "kind"
    productFlavors {
        create("full") {
            dimension = "kind"
            buildConfigField("boolean", "SETUP", "false")
        }
        create("setup") {
            dimension = "kind"
            // One below the full build of the same run, but above the LeXia + Nuvio trial (1000+):
            // kur.apk installs over that trial too, then updates itself to the full build.
            versionCode = 1999 + (System.getenv("GITHUB_RUN_NUMBER") ?: "4").toInt()
            buildConfigField("boolean", "SETUP", "true")
            buildConfigField("String", "BUILD_NO", "\"0\"")
        }
    }

    // Signing key is encrypted; its password lives only in the KEYSTORE_PASSWORD secret.
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val buildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    if (System.getenv("GITHUB_ACTIONS") == "true" && buildingRelease && keystorePassword.isNullOrEmpty()) {
        throw GradleException("KEYSTORE_PASSWORD secret is missing; refusing to build an APK with a throwaway key.")
    }
    signingConfigs {
        create("own") {
            storeFile = file("signing.p12")
            storeType = "pkcs12"
            storePassword = keystorePassword
            keyAlias = "lexia"
            keyPassword = keystorePassword
        }
    }
    buildTypes {
        getByName("debug") {
            if (!keystorePassword.isNullOrEmpty()) signingConfig = signingConfigs.getByName("own")
        }
        // What TVs get: not debuggable, same key, so it updates the earlier debug builds in place.
        // Shrunk and with scrambled names, so the code is hard to read if someone unpacks the APK.
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystorePassword.isNullOrEmpty()) signingConfig = signingConfigs.getByName("own")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        buildConfig = true
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

// As NuvioTV does: the stock modules that Nuvio's own Media3 build replaces never come in.
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-datasource")
    exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-extractor")
}

dependencies {
    // NuvioTV's player library: Nuvio's own Media3 1.8.0 build (app/libs, from NuvioTV app/libs)
    // in place of the stock common, datasource, datasource-okhttp, exoplayer, exoplayer-hls and
    // extractor modules (excluded below, as NuvioTV does), the other modules at 1.8.0 from Maven,
    // and Nuvio's FFmpeg audio decoder (AC3, E-AC3, DTS, TrueHD... that many TVs cannot decode).
    implementation(files(
        "libs/lib-common-release.aar",
        "libs/lib-datasource-release.aar",
        "libs/lib-datasource-okhttp-release.aar",
        "libs/lib-exoplayer-release.aar",
        "libs/lib-exoplayer-hls-release.aar",
        "libs/lib-extractor-release.aar",
        "libs/lib-decoder-ffmpeg-release.aar"
    ))
    val media3Version = "1.8.0"
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3Version")
    implementation("androidx.media3:media3-decoder:$media3Version")
    implementation("androidx.media3:media3-container:$media3Version")
    implementation("androidx.media3:media3-database:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    // NuvioTV's player screen: libass subtitles, media info probe, MPV (as in NuvioTV's app module).
    implementation(files("libs/nuvio-libass-release.aar", "libs/nextlib-mediainfo-local.aar"))
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    // What the forked AARs need but do not carry (as in NuvioTV's build).
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.annotation:annotation-experimental:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Sandboxed Nuvio JS scraper runtime.
    // Nuvio's own build of the JavaScript engine its scrapers are written against (from NuvioTV's app/libs).
    implementation(files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
    // NuvioTV's copied code and screens, with the libraries they are built on.
    implementation(project(":nuvio"))
    // Nuvio Beta scrapers commonly rely on the CryptoJS global.
    implementation("org.webjars.npm:crypto-js:4.2.0")
}

// Off-device drawing of LeXia's media pages (Robolectric + Roborazzi): build/shots/*.png.
android.testOptions.unitTests.isIncludeAndroidResources = true
android.testOptions.unitTests.all {
    it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    it.systemProperty("roborazzi.test.record", "true")
    System.getProperty("lexia.repo")?.let { repo -> it.systemProperty("lexia.repo", repo) }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("androidx.test:core:1.6.1")
}
