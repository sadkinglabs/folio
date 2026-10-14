plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    api(libs.jsch)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    // Android provides org.json itself; bundling a copy would clash with the platform's.
    compileOnly(libs.json.test)
    testImplementation(libs.json.test)
    testImplementation(libs.coroutines)
    testImplementation(libs.junit)
}

tasks.test { outputs.upToDateWhen { System.getenv("FOLIO_SFTP_PORT") == null } }
