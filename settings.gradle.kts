pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Central rate-limits busy build machines (HTTP
        // 429),
        // which Gradle reports as a missing library.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // sherpa-onnx (on-device voice) is published only on JitPack. Limited to that group so no
        // other dependency can resolve from it.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

rootProject.name = "Folio"

include(":core", ":transport", ":app")
