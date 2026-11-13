plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.lexia.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.lexia.launcher"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "4").toInt()
        versionName = "0.4"
        buildConfigField("String", "BUILD_NO", "\"${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}\"")
        // The address of the files site (film list, updates); the build workflow looks it up.
        buildConfigField("String", "SITE", "\"${System.getenv("LEXIA_SITE") ?: "https://lexia.invalid"}\"")
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
            versionCode = 1
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

    // The app has no translations; lint's release check would only slow the build.
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
    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
