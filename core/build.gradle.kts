plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
    // Reads the KOReader session cases shared with the plugin's Lua check (test only).
    testImplementation(libs.json.test)
}
