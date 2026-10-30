plugins {
    alias(libs.plugins.lectorvoz.android.library)
}

android {
    namespace = "com.lectorvoz.tts"

    testOptions {
        unitTests.all {
            // Prueba JVM opcional con la libreria nativa de sherpa-onnx 2.0.1 para Linux x86_64
            // (la descarga tools/descargar-dependencias.sh). Si no esta, la prueba se omite.
            val dirNativo = rootProject.layout.projectDirectory
                .dir("tools/cache/sherpa-onnx-v2.0.1-linux-x64-jni/lib").asFile
            it.systemProperty("lectorvoz.sherpa.libdir", dirNativo.absolutePath)
            it.systemProperty("java.library.path", dirNativo.absolutePath)
            it.systemProperty(
                "lectorvoz.voces.dir",
                layout.projectDirectory.dir("src/main/assets/voces").asFile.absolutePath,
            )
            // Muestra de audio de la cadena completa (opcional): -Plectorvoz.muestra=/ruta.wav
            providers.gradleProperty("lectorvoz.muestra").orNull?.let { ruta ->
                it.systemProperty("lectorvoz.muestra", ruta)
                it.outputs.upToDateWhen { false }
            }
        }
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.text)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.common)
    implementation(libs.androidx.media)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Voz neuronal: sherpa-onnx 2.0.1 (AAR oficial via JitPack, version exacta; sin GPL).
    implementation(libs.sherpa.onnx)
    // Descompresion .tar.bz2 de voces descargables (Apache-2.0).
    implementation(libs.commons.compress)
}
