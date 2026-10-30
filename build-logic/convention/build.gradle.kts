import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.lectorvoz.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: las versiones reales las aporta el bloque plugins del build raiz.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "lectorvoz.android.application"
            implementationClass = "com.lectorvoz.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "lectorvoz.android.library"
            implementationClass = "com.lectorvoz.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "lectorvoz.android.compose"
            implementationClass = "com.lectorvoz.buildlogic.AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "lectorvoz.jvm.library"
            implementationClass = "com.lectorvoz.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
