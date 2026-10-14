plugins {
    alias(libs.plugins.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val ktfmt by configurations.creating

dependencies {
    add(ktfmt.name, "com.facebook:ktfmt:${libs.versions.ktfmt.get()}:with-dependencies")
}

fun kotlinFiles() =
    fileTree(rootDir) {
            include("**/*.kt", "**/*.kts")
            exclude("**/build/**", "**/.gradle/**")
        }
        .files
        .sortedBy { it.path }
        .map { it.absolutePath }

tasks.register<JavaExec>("formatKotlin") {
    classpath = ktfmt
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--kotlinlang-style")
    args(kotlinFiles())
}

tasks.register<JavaExec>("checkFormatting") {
    classpath = ktfmt
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--kotlinlang-style", "--dry-run", "--set-exit-if-changed")
    args(kotlinFiles())
}
