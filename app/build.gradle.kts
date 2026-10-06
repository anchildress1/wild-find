plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.anchildress1.wildfind"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.anchildress1.wildfind"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Debug and release coexist on the single test phone, so a release install never wipes the model.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
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

// Without these files the APK installs fine and fails on the first camera frame, so no APK may skip them.
// Compiling alone doesn't need them, which keeps CodeQL's compile-only build working.
val checkBundledAssets = tasks.register("checkBundledAssets") {
    val dir = layout.projectDirectory.dir("generated/assets")
    val required = listOf(
        "flora_student_fp32.onnx",
        "plant_gate.onnx",
        "plant_gate.json",
        "species_table.npy",
        "species_labels.json",
    )
    doLast {
        val missing = required.filterNot { dir.file(it).asFile.isFile }
        if (missing.isNotEmpty()) throw GradleException("missing bundled assets $missing; run make assets")
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
    implementation(libs.onnxruntime.android)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
