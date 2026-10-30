package com.lectorvoz.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Modulo Kotlin/JVM puro (sin Android): se prueba en JVM sin dispositivo. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            configureKotlinCompile()
            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("truth"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            }
        }
    }
}
