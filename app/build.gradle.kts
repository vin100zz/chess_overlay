import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stockfish : binaire officiel pour Android (ARM 64 bits), téléchargé à la compilation et
// embarqué dans l'APK sous le nom libstockfish.so (seul moyen de pouvoir l'exécuter).
val stockfishVersion = "sf_17.1"
val stockfishTar = layout.buildDirectory.file("stockfish/$stockfishVersion.tar").get().asFile
val stockfishJniDir = layout.buildDirectory.dir("stockfish/jniLibs").get().asFile

android {
    namespace = "fr.babouch.echecs"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.babouch.echecs"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Clé de debug fixe : permet d'installer une nouvelle version par-dessus l'ancienne.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(stockfishJniDir)
        }
    }

    packaging {
        // Les .so doivent être extraits sur le téléphone pour que Stockfish puisse être lancé.
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

val downloadStockfish by tasks.registering {
    outputs.file(stockfishTar)
    doLast {
        if (!stockfishTar.exists() || stockfishTar.length() < 1_000_000) {
            stockfishTar.parentFile.mkdirs()
            val url = URI(
                "https://github.com/official-stockfish/Stockfish/releases/download/" +
                    "$stockfishVersion/stockfish-android-armv8.tar",
            ).toURL()
            url.openStream().use { input -> stockfishTar.outputStream().use { input.copyTo(it) } }
        }
    }
}

val extractStockfish by tasks.registering(Copy::class) {
    dependsOn(downloadStockfish)
    from(tarTree(stockfishTar)) {
        include("stockfish/stockfish-android-armv8")
        eachFile { path = "arm64-v8a/libstockfish.so" }
        includeEmptyDirs = false
    }
    into(stockfishJniDir)
}

tasks.named("preBuild") { dependsOn(extractStockfish) }
