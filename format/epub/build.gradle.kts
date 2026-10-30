plugins {
    alias(libs.plugins.lectorvoz.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.core)
}
