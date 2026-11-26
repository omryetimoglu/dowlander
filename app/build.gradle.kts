import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val appConfig = Properties().apply {
    rootProject.file("config/app.properties").inputStream().use { load(it) }
}
fun option(key: String) = providers.gradleProperty(key).orElse(providers.environmentVariable(key))
    .getOrElse(appConfig.getProperty(key, "")).trim()
fun releaseOption(key: String) = providers.gradleProperty(key).orElse(providers.environmentVariable(key)).orNull
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val xClient = option("EVASI_X_CLIENT_ID")
val xRedirect = option("EVASI_X_REDIRECT_URI")
val callback = URI(xRedirect.ifBlank { "com.evasi.downloader.unconfigured://oauth/disabled" })
require((callback.scheme == "https" || callback.scheme.orEmpty().matches(Regex("[a-z][a-z0-9.-]*[.][a-z0-9.-]+"))) &&
    callback.host != null && callback.rawPath.orEmpty().startsWith("/") && callback.userInfo == null &&
    callback.query == null && callback.fragment == null && callback.port == -1) { "Invalid EVASI_X_REDIRECT_URI" }

android {
    namespace = "com.evasi.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.evasi.downloader"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0"
        buildConfigField("String", "X_CLIENT_ID", quoted(xClient))
        buildConfigField("String", "X_REDIRECT_URI", quoted(xRedirect))
        resValue("string", "support_email", option("EVASI_SUPPORT_EMAIL"))
        manifestPlaceholders["oauthScheme"] = callback.scheme
        manifestPlaceholders["oauthHost"] = callback.host
        manifestPlaceholders["oauthPath"] = callback.path
        manifestPlaceholders["oauthEnabled"] = (xClient.isNotBlank() && xRedirect.isNotBlank()).toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val signingFile = releaseOption("EVASI_RELEASE_KEYSTORE")
    if (!signingFile.isNullOrBlank()) {
        val signing = signingConfigs.create("distribution") {
            storeFile = rootProject.file(signingFile)
            storePassword = requireNotNull(releaseOption("EVASI_RELEASE_STORE_PASSWORD")) { "Missing EVASI_RELEASE_STORE_PASSWORD" }
            keyAlias = requireNotNull(releaseOption("EVASI_RELEASE_KEY_ALIAS")) { "Missing EVASI_RELEASE_KEY_ALIAS" }
            keyPassword = requireNotNull(releaseOption("EVASI_RELEASE_KEY_PASSWORD")) { "Missing EVASI_RELEASE_KEY_PASSWORD" }
        }
        buildTypes.getByName("release").signingConfig = signing
    }

    // The downloadable GitHub APK updates the original standalone test installation.
    if (providers.gradleProperty("githubApk").isPresent) {
        buildTypes.getByName("debug").applicationIdSuffix = ".xtest"
    }

    // AGP 8.7 exposes APK filenames through the public ApkVariantOutput API.
    applicationVariants.all {
        if (buildType.name == "debug") {
            outputs.withType<com.android.build.gradle.api.ApkVariantOutput>().all {
                outputFileName = "eVasiApp.apk"
            }
        }
    }

    // Python, QuickJS and FFmpeg must be executable files in nativeLibraryDir.
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/lib*.zip.so"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // The in-app language picker must work offline even when the system uses another language.
    bundle.language.enableSplit = false

    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    val publicPosts = providers.environmentVariable("EVASI_PUBLIC_POSTS").orElse("")
    inputs.property("evasiPublicPosts", publicPosts)
    environment("EVASI_PUBLIC_POSTS", publicPosts.get())
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    val youtubeDlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubeDlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubeDlAndroid")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
