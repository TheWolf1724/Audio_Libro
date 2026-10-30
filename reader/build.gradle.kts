plugins {
    alias(libs.plugins.lectorvoz.android.library)
    alias(libs.plugins.lectorvoz.android.compose)
}

android {
    namespace = "com.lectorvoz.reader"
}

dependencies {
    api(projects.core.model)
    // PdfBook aparece en la API publica (ReaderController.openPdf).
    api(projects.format.pdf)
    implementation(projects.format.epub)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
}
