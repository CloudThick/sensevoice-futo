import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sherpaVersion = "1.13.5"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar")

// The official sherpa-onnx Android AAR is a GitHub release asset.
// Android Studio/Gradle will fetch it once before the first build.
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
        versionCode = 1
        versionName = "0.1-prototype"
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
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
