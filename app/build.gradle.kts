import java.io.FileInputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.transcribe"
    compileSdk = 35
    ndkVersion = "28.0.12433566"

    defaultConfig {
        applicationId = "dev.transcribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.1.33"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("STORE_PASS") ?: "password"
                keyAlias = System.getenv("KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("KEY_PASS") ?: "password"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Source sets — the Rust-built .so files land in jniLibs via cargo-ndk
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
        }
    }

    // Play Asset Delivery
    assetPacks += listOf(":model_assets")
}

val isBundle = gradle.startParameter.taskNames.any {
    it.contains("bundle", ignoreCase = true)
}
if (!isBundle) {
    android.sourceSets.getByName("main") {
        assets.srcDirs(
            "src/main/assets",
            rootProject.file("model_assets/src/main/assets")
        )
    }
}

// ---------------------------------------------------------------------------
// Repositories for sherpa-onnx AAR (JitPack)
// ---------------------------------------------------------------------------

repositories {
    maven { url = uri("https://jitpack.io") }
    google()
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Sherpa-ONNX Android AAR — offline TTS (Thorsten-Medium Piper VITS)
    implementation("com.github.k2-fsa:sherpa-onnx:1.10.30")
}

// Dedicated configuration for ORT native libs
val ortNative: Configuration by configurations.creating
dependencies {
    ortNative("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

// ---------------------------------------------------------------------------
// Task to extract ORT headers & native libs for Rust compilation
// ---------------------------------------------------------------------------

val extractOrt by tasks.registering(Copy::class) {
    description = "Extract ONNX Runtime AAR for Rust build"
    group = "build"

    from(ortNative.elements.map { fileCollection ->
        fileCollection.map { zipTree(it) }
    })
    into(layout.buildDirectory.dir("ort-extracted"))
}

// ---------------------------------------------------------------------------
// Rust / cargo-ndk build task
// ---------------------------------------------------------------------------

val cargoNdkBuild by tasks.registering(Exec::class) {
    description = "Build Rust native code via cargo-ndk"
    group = "build"

    dependsOn(extractOrt)

    workingDir = rootProject.projectDir

    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: android.ndkDirectory.absolutePath

    environment("ANDROID_NDK_HOME", ndkDir)

    val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
    val currentPath = System.getenv("PATH") ?: ""
    environment("PATH", if (currentPath.contains(cargoBin)) currentPath else "$cargoBin:$currentPath")

    val extractDir = layout.buildDirectory.dir("ort-extracted").get().asFile
    environment("ORT_LIB_LOCATION", File(extractDir, "jni/arm64-v8a").absolutePath)
    environment("ORT_INCLUDE_DIR", File(extractDir, "headers").absolutePath)

    val jniLibsDir = project.file("src/main/jniLibs")

    val cargoExe = "$cargoBin/cargo"
    commandLine(
        cargoExe, "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )

    doLast {
        val ndkPath = environment["ANDROID_NDK_HOME"] as String
        val prebuiltHost = when {
            System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val libcpp = file("$ndkPath/toolchains/llvm/prebuilt/$prebuiltHost/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        if (libcpp.exists()) {
            val destDir = File(jniLibsDir, "arm64-v8a")
            destDir.mkdirs()
            libcpp.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
            println("Copied libc++_shared.so from NDK")
        } else {
            throw GradleException("libc++_shared.so not found in NDK at: ${libcpp.absolutePath}")
        }
    }

    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

// ---------------------------------------------------------------------------
// Model asset download task
// ---------------------------------------------------------------------------

data class ModelFile(val name: String, val sha256: String)

val appAssetFiles = listOf(
    ModelFile("config.json", ""),
    ModelFile("vocab.txt", ""),
)

val modelPackFiles = listOf(
    ModelFile("encoder-model.int8.onnx",
        "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"),
    ModelFile("decoder_joint-model.int8.onnx",
        "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"),
    ModelFile("nemo128.onnx",
        "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"),
)

val huggingFaceRepo = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"

fun downloadToDir(assetsDir: File, files: List<ModelFile>) {
    assetsDir.mkdirs()
    files.forEach { model ->
        val destFile = File(assetsDir, model.name)
        if (destFile.exists() && model.sha256.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(destFile).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash == model.sha256) {
                println("  \u2713 ${model.name} already downloaded and verified")
                return@forEach
            } else {
                println("  \u2717 ${model.name} checksum mismatch, re-downloading...")
                destFile.delete()
            }
        }

        if (!destFile.exists()) {
            println("  \u2193 Downloading ${model.name}...")
            val downloadUrl = "$huggingFaceRepo/${model.name}?download=true"
            val proc = ProcessBuilder("curl", "-L", "-f", "-o", destFile.absolutePath, downloadUrl)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Failed to download ${model.name} (curl exit code $exitCode)")
            }

            if (model.sha256.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(destFile).use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        digest.update(buf, 0, read)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != model.sha256) {
                    throw GradleException(
                        "Checksum verification failed for ${model.name}:\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                    )
                }
                println("  \u2713 ${model.name} verified")
            }
        }
    }
}

val downloadModels by tasks.registering {
    description = "Download HuggingFace Parakeet model assets"
    group = "build"

    val appAssetsDir = project.file("src/main/assets/parakeet-tdt-0.6b-v3-int8")
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8")

    outputs.dir(appAssetsDir)
    outputs.dir(packAssetsDir)

    doLast {
        downloadToDir(appAssetsDir, appAssetFiles)
        downloadToDir(packAssetsDir, modelPackFiles)
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModels)
}
