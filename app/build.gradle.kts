import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// =====================================================
// Security: Load API Keys from local.properties
// =====================================================
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
    println("✅ Loaded API keys from local.properties")
} else {
    println("⚠️ local.properties not found. Using default/empty API keys.")
    println("   Create local.properties from local.properties.example")
}

fun getLocalProperty(key: String, default: String = ""): String {
    return localProperties.getProperty(key, default)
}

fun String.asBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.example.voicebrainlive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.voicebrainlive"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"naylinhtike1356-cyber/VoiceBrainLive-\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val spotifyClientId = getLocalProperty("SPOTIFY_CLIENT_ID", "06fe7531fe074c4ca42ff3b5321ff986")
        val spotifyRedirectUri = "voicebrainlive://callback"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", spotifyClientId.asBuildConfigString())
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", spotifyRedirectUri.asBuildConfigString())
    }

    val releaseSigningFile = rootProject.file(".release-signing/signing.properties")
    if (releaseSigningFile.exists()) {
        val releaseSigning = Properties().apply { releaseSigningFile.inputStream().use { load(it) } }
        signingConfigs.create("distribution") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        create("deviceAcceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".m16fixture"
            versionNameSuffix = "-M1.6-FIXTURE"
            matchingFallbacks += listOf("debug")
            // Isolated acceptance fixture: never embed developer/service credentials.
            listOf("GEMINI_API_KEY", "YOUTUBE_API_KEY", "OPENAI_API_KEY", "SERPER_API_KEY", "SPOTIFY_CLIENT_ID")
                .forEach { buildConfigField("String", it, "\"\"") }
        }
        debug {
            // Debug builds may use local developer keys from local.properties.
            buildConfigField("String", "GEMINI_API_KEY", getLocalProperty("GEMINI_API_KEY", "").asBuildConfigString())
            buildConfigField("String", "YOUTUBE_API_KEY", getLocalProperty("YOUTUBE_API_KEY", "").asBuildConfigString())
            buildConfigField("String", "OPENAI_API_KEY", getLocalProperty("OPENAI_API_KEY", "").asBuildConfigString())
            buildConfigField("String", "SERPER_API_KEY", getLocalProperty("SERPER_API_KEY", "").asBuildConfigString())
        }
        release {
            if (releaseSigningFile.exists()) signingConfig = signingConfigs.getByName("distribution")
            // Do not embed private API keys in release APKs. Users add them in Settings.
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "YOUTUBE_API_KEY", "\"\"")
            buildConfigField("String", "OPENAI_API_KEY", "\"\"")
            buildConfigField("String", "SERPER_API_KEY", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    if (providers.gradleProperty("m16").orNull == "true") {
        testBuildType = "deviceAcceptance"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    // Spotify App Remote (local AAR from official GitHub releases)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Phase 4: Room for Vector Store (embedding vectors)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    
    // Phase 3: CameraX for Vision
    val camerax_version = "1.3.4"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-extensions:${camerax_version}")
}
