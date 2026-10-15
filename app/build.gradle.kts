import java.net.URI

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.sadking.folio"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.sadking.folio"
        minSdk = 36
        targetSdk = 36
        versionCode = 18
        versionName = "0.12.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The owner's phone is arm64; the CI emulator is x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs.getByName("debug") {
        val preservedKey = rootProject.file("development-signing/debug.keystore")
        if (preservedKey.exists()) storeFile = preservedKey
    }
    sourceSets.getByName("main").assets.srcDir("$rootDir/koreader")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // From minSdk 28 code and native libraries are stored uncompressed, which pushed the APK
        // past 130 MB. Compressed, it downloads and sideloads far faster.
        dex.useLegacyPackaging = true
        jniLibs.useLegacyPackaging = true
    }
}

kotlin { jvmToolchain(17) }

// Robolectric runs Android 16 (SDK 36) only on Java 21; the app still compiles for Java 17.
tasks.withType<Test>().configureEach {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    // Robolectric reaches into JDK internals that Java 21 closes by default.
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.fragment.ktx)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.navigator.tts)
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(libs.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.preview) // @Preview annotations live in main sources
    implementation(libs.compose.icons)
    implementation(libs.material)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.activity)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.vm.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines)
    implementation(libs.jsch)
    implementation(libs.mlkit.text)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json.test)
    // Renders Compose screens to PNG on the JVM: `./gradlew :app:testDebugUnitTest -Pscreenshots`.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test)
    testImplementation(libs.roborazzi.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test)
    androidTestImplementation(libs.room.testing)
}

// Framework regression tests need Android's SDK jar; keep downloads on Maven Central.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("screenshots")) {
        systemProperty("roborazzi.test.record", "true")
        // Images are the point of the run; never reuse a cached or up-to-date result.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    if (System.getenv("FOLIO_USE_ENV_PROXY") != null) {
        val proxy = URI(System.getenv("HTTPS_PROXY"))
        listOf("http", "https").forEach {
            systemProperty("$it.proxyHost", proxy.host)
            systemProperty("$it.proxyPort", proxy.port.toString())
        }
        systemProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
    }
}
