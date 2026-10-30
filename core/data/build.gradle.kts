plugins {
    alias(libs.plugins.lectorvoz.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lectorvoz.core.data"
}

ksp {
    // Esquemas de Room versionados para migraciones.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.androidx.room.testing)
}
