// Build raiz: solo declara versiones de plugins (se aplican en cada modulo
// a traves de los convention plugins de build-logic).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Fija KGP 2.4.21 (AGP 9 trae Kotlin integrado con una version minima menor).
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
