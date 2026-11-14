import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.minipots.ar"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.minipots.ar"; minSdk = 24; targetSdk = 35; versionCode = 2; versionName = "2.0"
        // Phones only: leaving out the x86 copies of the MediaPipe library keeps the APK small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // The model is read straight from the APK instead of being unpacked into memory first.
    androidResources { noCompress += "task" }
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:1.2.2")
    testImplementation("junit:junit:4.13.2")
}

// MediaPipe hand landmark model (palm detector + 21-point landmark network, about 7.5 MB).
// It is downloaded once at build time, checked against the published checksum, and kept out of Git.
val handModelUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val handModelSha256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
val handModelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadHandModel = tasks.register("downloadHandModel") {
    outputs.file(handModelFile)
    onlyIf { !handModelFile.exists() || sha256(handModelFile) != handModelSha256 }
    doLast {
        handModelFile.parentFile.mkdirs()
        val temporary = File(handModelFile.absolutePath + ".tmp")
        try {
            URI(handModelUrl).toURL().openStream().use { Files.copy(it, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            val actual = sha256(temporary)
            if (actual != handModelSha256) throw GradleException("hand_landmarker.task: checksum $actual, expected $handModelSha256")
            Files.move(temporary.toPath(), handModelFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadHandModel) }
