plugins {
    alias(libs.plugins.lectorvoz.android.library)
}

android {
    namespace = "com.lectorvoz.format.pdf"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.text)
    implementation(libs.pdfium)
    implementation(libs.tesseract4android)
    implementation(libs.kotlinx.coroutines.android)
}
