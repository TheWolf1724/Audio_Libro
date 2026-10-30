import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.lectorvoz.android.application)
    alias(libs.plugins.lectorvoz.android.compose)
}

android {
    namespace = "com.lectorvoz.app"

    defaultConfig {
        applicationId = "com.lectorvoz.app"
        versionCode = 8
        versionName = "1.0.0"
    }

    // Firma de release local: la clave vive fuera del repositorio, en ~/.lectorvoz/
    // (keystore.properties + release.jks). Si no existe, el release queda sin firmar.
    val firmaLocal = File(System.getProperty("user.home"), ".lectorvoz/keystore.properties")
    if (firmaLocal.isFile) {
        val props = Properties().apply { firmaLocal.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sin firma de release todavia: no se crea keystore.
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // tts/INTEGRACION.md: los modelos se leen de assets con AssetManager, sin comprimir
    // (si no, se descomprimen en RAM).
    androidResources {
        noCompress += listOf("onnx", "bin")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // libsherpa-onnx-jni.so solo depende de libonnxruntime.so.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

// En release solo arm64-v8a (el movil real); x86_64 queda para depurar en debug.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/x86_64/**")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.text)
    implementation(projects.core.data)
    implementation(projects.format.epub)
    implementation(projects.format.pdf)
    implementation(projects.reader)
    implementation(projects.tts)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
