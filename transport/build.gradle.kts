plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    api(libs.jsch)
    testImplementation(libs.junit)
}

tasks.test { outputs.upToDateWhen { System.getenv("FOLIO_SFTP_PORT") == null } }
