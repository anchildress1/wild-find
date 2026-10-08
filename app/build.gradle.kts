import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.anchildress1.wildfind"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.anchildress1.wildfind"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives only on the developer's machine; without keystore.properties the release APK builds unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.isFile) {
        val keys = Properties().apply { keystoreFile.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Debug and release coexist on the single test phone, so a release install never wipes gate-harness runs not yet pulled.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Stored, not deflated, so BundledAssets can memory-map the models instead of inflating them onto the heap.
        noCompress += "onnx"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

androidComponents {
    onVariants { variant ->
        // Bundled models and tables from make assets; gitignored, never committed.
        variant.sources.assets?.addStaticSourceDirectory("generated/assets")
    }
}

// Without these files the APK installs fine and fails on the first camera frame, so no APK may skip them,
// and stale ones from before a pin or pipeline change would package silently, so their input stamp must match.
// Compiling alone doesn't need them, which keeps CodeQL's compile-only build working.
val checkBundledAssets = tasks.register("checkBundledAssets") {
    val dir = layout.projectDirectory.dir("generated/assets")
    val stamp = layout.projectDirectory.file("generated/inputs.json")
    val repoRoot = rootProject.layout.projectDirectory
    val required = listOf(
        "flora_student_fp32.onnx",
        "plant_gate.onnx",
        "plant_gate.json",
        "species_table.npy",
        "species_labels.json",
        "map.bin",
    )
    doLast {
        val missing = required.filterNot { dir.file(it).asFile.isFile }
        if (missing.isNotEmpty()) throw GradleException("missing bundled assets $missing; run make assets")
        if (!stamp.asFile.isFile) throw GradleException("bundled assets have no input stamp; run make assets")
        @Suppress("UNCHECKED_CAST")
        val inputs = JsonSlurper().parse(stamp.asFile) as Map<String, String>
        val stale = inputs.filter { (path, sha) ->
            val file = repoRoot.file(path).asFile
            !file.isFile ||
                MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) } != sha
        }.keys
        if (stale.isNotEmpty()) throw GradleException("bundled assets are stale, $stale changed; run make assets")
    }
}

tasks.matching { it.name.matches(Regex("merge\\w*Assets")) }.configureEach { dependsOn(checkBundledAssets) }

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.onnxruntime.android)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.compose)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
