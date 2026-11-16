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

dependencies {
    // Native Android TV playback foundation. Keep all Media3 artifacts on one version.
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3Version")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Sandboxed Nuvio JS scraper runtime.
    implementation("io.github.dokar3:quickjs-kt-android:1.0.5")
    implementation("org.jsoup:jsoup:1.17.2")
    // Nuvio Beta scrapers commonly rely on the CryptoJS global.
    implementation("org.webjars.npm:crypto-js:4.2.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
