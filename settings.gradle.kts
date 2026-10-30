pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // :tts - sherpa-onnx no esta en Maven Central; JitPack publica el AAR oficial
        // (mismo SHA-256 que el de GitHub Releases). Limitado a ese grupo.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "LectorVoz"

include(":app")
include(":core:model")
include(":core:text")
include(":core:data")
include(":format:epub")
include(":format:pdf")
include(":reader")
include(":tts")
