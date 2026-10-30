package com.lectorvoz.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Configuracion comun de modulos Android (app y librerias).
 * AGP 9 trae Kotlin integrado: no se aplica `org.jetbrains.kotlin.android`.
 */
internal fun Project.configureKotlinAndroid(extension: CommonExtension) {
    extension.apply {
        // AGP 9: la plataforma instalada es "android-37.0" (API 37, menor 0).
        compileSdk {
            version = release(libs.version("compileSdk").toInt()) {
                minorApiLevel = 0
            }
        }
        defaultConfig.apply {
            minSdk = libs.version("minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            // Solo ABIs utiles: movil real (arm64) y depuracion en x86_64.
            ndk.abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions.apply {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
            unitTests.all {
                // Robolectric con SDK 36+ en JDK 21 toca internos de FileDescriptor (SharedSecrets).
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                it.maxHeapSize = "1g"
            }
        }
    }
    configureKotlinCompile()

    dependencies {
        add("testImplementation", libs.lib("junit"))
        add("testImplementation", libs.lib("truth"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("androidx-test-core"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
    }
}

/** jvmTarget 17 para todo el codigo Kotlin, compilando con el JDK local (21). */
internal fun Project.configureKotlinCompile() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.version("jvmTarget")))
        }
    }
}
