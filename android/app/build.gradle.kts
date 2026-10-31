import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// One version for every platform: the desktop app's package.json when building inside the
// full project, otherwise fdVersion from gradle.properties (the published Android source).
val pkgFile = rootDir.resolve("../package.json")
val appVersion = if (pkgFile.exists()) (JsonSlurper().parse(pkgFile) as Map<*, *>)["version"] as String
else providers.gradleProperty("fdVersion").get()
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

// Release signing key, kept outside the repository (see android/README.md).
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val envKey = System.getenv("FD_KEYSTORE")

android {
    namespace = "com.fastdownloader.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fastdownloader.app"
        minSdk = 26 // Android 8.0
        targetSdk = 37 // Android 17
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        // -PfdVersionOverride=1.0.0 makes the app call itself older (same versionCode, so it installs
        // over the real one and keeps its data), to try the update check.
        versionName = providers.gradleProperty("fdVersionOverride").orNull ?: appVersion
    }

    signingConfigs {
        create("release") {
            val store = envKey ?: keyProps.getProperty("storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("FD_KEYSTORE_PASSWORD") ?: keyProps.getProperty("storePassword")
                keyAlias = System.getenv("FD_KEY_ALIAS") ?: keyProps.getProperty("keyAlias")
                keyPassword = System.getenv("FD_KEY_PASSWORD") ?: keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not minified: yt-dlp, FFmpeg and libtorrent are reached through JNI and reflection.
            isMinifyEnabled = false
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    // One APK per processor type (smaller downloads) plus one that runs everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        // yt-dlp's Python and FFmpeg are run from the extracted native-library folder.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The ad and tracker list is shared with the browser extension (a copy sits in rules/ for the published source).
    val sharedRules = rootDir.resolve("../extension/rules")
    sourceSets["main"].assets.srcDir(if (sharedRules.exists()) sharedRules else rootDir.resolve("rules"))
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.webkit:webkit:1.18.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Torrents
    val lt = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$lt")

    // Video sites (yt-dlp with Python, FFmpeg and QuickJS for Android)
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    testImplementation("junit:junit:4.13.2")
}
