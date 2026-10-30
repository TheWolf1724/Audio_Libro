plugins {
    alias(libs.plugins.lectorvoz.android.library)
}

android {
    namespace = "com.lectorvoz.format.pdf"
}

dependencies {
    api(projects.core.model)
    implementation(libs.pdfium)
    implementation(libs.kotlinx.coroutines.android)
}
