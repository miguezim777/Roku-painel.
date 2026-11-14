import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

// MediaPipe hand landmark model (palm detector + 21-point landmark network, about 7.8 MB).
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

// Lighter hand model for the coliseum, which follows two hands at once: the same palm detector with
// the "lite" landmark network, which costs about half as much per hand. MediaPipe publishes the two
// networks separately; a model file is nothing more than a ZIP of the two, stored without
// compression, so it is put together here. Each network is checked against its published checksum.
val lightModelParts = listOf(
    Triple(
        "hand_detector.tflite",
        "https://storage.googleapis.com/mediapipe-assets/tasks/testdata/vision/palm_detection_full.tflite?generation=1782185042117966",
        "1b14e9422c6ad006cde6581a46c8b90dd573c07ab7f3934b5589e7cea3f89a54",
    ),
    Triple(
        "hand_landmarks_detector.tflite",
        "https://storage.googleapis.com/mediapipe-assets/tasks/testdata/vision/hand_landmark_lite.tflite?generation=1782184783517240",
        "048edd3645c9bf7397d19a9f6e3a42957d6e414c9bea6598030a2e9b624156e6",
    ),
)
val lightModelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker_lite.task").asFile
val lightModelParent = layout.buildDirectory.dir("hand-model-parts")
// Changing a checksum above makes the file be built again.
val lightModelStamp = layout.buildDirectory.file("hand-model-parts/built-from.txt").get().asFile
val lightModelKey = lightModelParts.joinToString(" ") { it.third }

val buildLightHandModel = tasks.register("buildLightHandModel") {
    outputs.file(lightModelFile)
    onlyIf { !lightModelFile.exists() || !lightModelStamp.exists() || lightModelStamp.readText() != lightModelKey }
    doLast {
        val folder = lightModelParent.get().asFile
        folder.mkdirs()
        lightModelFile.parentFile.mkdirs()
        try {
            val parts = lightModelParts.map { (name, url, checksum) ->
                val part = File(folder, name)
                if (!part.exists() || sha256(part) != checksum) {
                    // The address with the exact version first, then the plain one.
                    var error: Exception? = null
                    for (address in listOf(url, url.substringBefore('?'))) {
                        try {
                            URI(address).toURL().openStream().use { Files.copy(it, part.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                            if (sha256(part) == checksum) { error = null; break }
                            error = GradleException("$name: checksum ${sha256(part)}, expected $checksum")
                        } catch (e: Exception) { error = e }
                    }
                    error?.let { throw it }
                }
                name to part
            }
            val temporary = File(lightModelFile.absolutePath + ".tmp")
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                for ((name, part) in parts) {
                    val bytes = part.readBytes()
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                    entry.time = 315532800000L
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
            }
            Files.move(temporary.toPath(), lightModelFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            lightModelStamp.writeText(lightModelKey)
        } catch (e: Exception) {
            // The game still works without it: it then uses the full model, only slower.
            lightModelFile.delete(); lightModelStamp.delete()
            logger.warn("warning: hand_landmarker_lite.task was not built (${e.message ?: e.javaClass.simpleName}); the coliseum will use the full hand model")
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadHandModel, buildLightHandModel) }
