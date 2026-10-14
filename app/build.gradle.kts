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
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.11.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
}

kotlin { jvmToolchain(17) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.fragment.ktx)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(libs.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.preview) // @Preview annotations live in main sources
    implementation(libs.compose.icons)
    implementation(libs.material)
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
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test)
    androidTestImplementation(libs.room.testing)
}

// Framework regression tests need Android's SDK jar; keep downloads on Maven Central.
tasks.withType<Test>().configureEach {
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
