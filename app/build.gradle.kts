plugins {
    alias(libs.plugins.android.application)        // compiles the Kotlin too (AGP 9)
}

// The release key, where it's configured: Gradle properties (~/.gradle/gradle.properties on a computer that has the
// key) or environment variables (the release workflow) of these names; TV_AGENT_PLAYER_* first, else tv-agent's own
// TV_AGENT_* (one project key for all its apps). See RELEASING.md. Never in this repository.
fun releaseKey(name: String): String? =
    listOf("TV_AGENT_PLAYER_$name", "TV_AGENT_$name").firstNotNullOfOrNull { n ->
        (findProperty(n) as String?) ?: System.getenv(n)
    }

android {
    namespace = "com.boujuan.tvagent.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.boujuan.tvagent.player"
        // The living-room TCL runs Android 14; the player is built for it and nothing older.
        minSdk = 34
        targetSdk = 37
        // Released as v<versionName> tags (see RELEASING.md); bump versionCode with every release.
        versionCode = 2
        versionName = "0.1.1"
        // The TV's userspace is 32-bit only. The APK has no native code today; this keeps it that way if a
        // library ever brings some.
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        if (releaseKey("KEYSTORE") != null) {
            create("release") {
                storeFile = file(releaseKey("KEYSTORE")!!)
                storePassword = releaseKey("KEYSTORE_PASSWORD")
                keyAlias = releaseKey("KEY_ALIAS")
                // keytool's PKCS12 keystores have one password for the store and the key.
                keyPassword = releaseKey("KEY_PASSWORD") ?: releaseKey("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release key if configured, else the debug key (CI on branches, a computer without the key): that APK
            // installs, but can't update an install signed with the release key, nor the other way round.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/kotlin/**", "DebugProbesKt.bin")
    }
    lint {
        // A TV-only app on Android 14, sideloaded and opened only by tv-agent: Play's target-level, launcher and
        // ChromeOS rules don't apply.
        disable += listOf("ExpiredTargetSdkVersion", "MissingTvBanner", "MissingLeanbackLauncher", "ChromeOsAbiSupport")
        // The player is built on Media3's @UnstableApi parts (renderer, track selector, load control, preload): that
        // is why Media3 is pinned (gradle/libs.versions.toml), so the opt-in is for the whole app.
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)              // SubtitleView and AspectRatioFrameLayout only; R8 drops the rest
    implementation(libs.profileinstaller)       // the baseline profile, for installs that don't compile it themselves

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
