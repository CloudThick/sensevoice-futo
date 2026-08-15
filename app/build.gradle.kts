import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sherpaVersion = "1.13.5"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar")
val llamaAar = layout.projectDirectory.file("libs/llama-android.aar")

// The official sherpa-onnx Android AAR is a GitHub release asset.
tasks.register("downloadSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        val out = sherpaAar.asFile
        if (!out.exists()) {
            out.parentFile.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
            println("Downloading $url")
            URL(url).openStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadSherpaAar")
}

android {
    namespace = "com.example.sensevoicefuto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sensevoicefuto"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-local-refiner"

        // This personal build targets the same arm64 phone used for the SenseVoice demo.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files(sherpaAar))
    implementation(files(llamaAar))

    // llama.android's public AAR references these AndroidX libraries.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
