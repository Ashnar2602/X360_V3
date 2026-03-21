import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.tasks.Delete

val fexCommitId = "49a37c7d6fec7d94923507e5ce10d55c2920e380"
val fexPatchSetName = "android-baseline-v1"
val fexNdkVersion = "27.2.12479018"
val fexCmakeVersion = "3.22.1"
val fexAndroidApi = 33
val guestRuntimeLockManifestPath = "fixtures/guest-runtime/ubuntu-24.04-amd64-lvp.lock.json"
val mesaRuntimeLockManifestPath = "fixtures/mesa-runtime/mesa-turnip-source-lock.json"
val xeniaSourceLockManifestPath = "fixtures/xenia-runtime/xenia-source-lock.json"
val androidSdkDirValue = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orNull
    ?: ""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "emu.x360.mobile.dev"
    compileSdk = 35
    ndkVersion = fexNdkVersion

    defaultConfig {
        applicationId = "emu.x360.mobile.dev"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":runtime-core"))
    implementation(project(":native-bridge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.google.material)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}

val androidComponents = extensions.getByType(AndroidComponentsExtension::class.java)
androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
    val prepareFexSourceTask = tasks.register<PrepareFexSourceTask>("prepare${variantName}FexSource") {
        sourceDir.set(rootProject.layout.projectDirectory.dir("third_party/FEX"))
        patchesDir.set(rootProject.layout.projectDirectory.dir("third_party/fex-patches/android"))
        outputDir.set(layout.buildDirectory.dir("intermediates/fexSource/${variant.name}"))
    }

    val buildFexArtifactsTask = tasks.register<BuildFexArtifactsTask>("build${variantName}FexArtifacts") {
        dependsOn(prepareFexSourceTask)
        preparedSourceDir.set(prepareFexSourceTask.flatMap { it.outputDir })
        androidSdkDir.set(androidSdkDirValue)
        androidApiLevel.set(fexAndroidApi)
        ndkVersion.set(fexNdkVersion)
        cmakeVersion.set(fexCmakeVersion)
        pythonExecutable.set("python")
        fexCommit.set(fexCommitId)
        patchSetId.set(fexPatchSetName)
        outputDir.set(layout.buildDirectory.dir("generated/fexJniLibs/${variant.name}"))
        metadataFile.set(layout.buildDirectory.file("generated/fexMetadata/${variant.name}/fex-build-metadata.json"))
    }

    val generateFexGuestAssetsTask = tasks.register<GenerateFexGuestAssetsTask>("generate${variantName}FexGuestAssets") {
        dependsOn(buildFexArtifactsTask)
        helloFixtureBinary.set(rootProject.layout.projectDirectory.file("fixtures/guest-tests/hello_x86_64"))
        metadataFile.set(buildFexArtifactsTask.flatMap { it.metadataFile })
        outputDir.set(layout.buildDirectory.dir("generated/fexGuestAssets/${variant.name}"))
    }

    val generateVulkanGuestAssetsTask = tasks.register<GenerateVulkanGuestAssetsTask>("generate${variantName}VulkanGuestAssets") {
        guestRuntimeLockManifest.set(rootProject.layout.projectDirectory.file(guestRuntimeLockManifestPath))
        dynamicHelloFixtureBinary.set(rootProject.layout.projectDirectory.file("fixtures/guest-tests/dyn_hello_x86_64"))
        vulkanProbeFixtureBinary.set(rootProject.layout.projectDirectory.file("fixtures/guest-tests/vulkan_probe_x86_64"))
        outputDir.set(layout.buildDirectory.dir("generated/vulkanGuestAssets/${variant.name}"))
    }

    val generateTurnipMesaAssetsTask = tasks.register<GenerateTurnipMesaAssetsTask>("generate${variantName}TurnipMesaAssets") {
        mesaRuntimeLockManifest.set(rootProject.layout.projectDirectory.file(mesaRuntimeLockManifestPath))
        patchesDir.set(rootProject.layout.projectDirectory.dir("third_party/mesa-patches"))
        outputDir.set(layout.buildDirectory.dir("generated/turnipMesaAssets/${variant.name}"))
    }

    val generateXeniaBringupAssetsTask = tasks.register<GenerateXeniaBringupAssetsTask>("generate${variantName}XeniaBringupAssets") {
        xeniaSourceLockManifest.set(rootProject.layout.projectDirectory.file(xeniaSourceLockManifestPath))
        patchesDir.set(rootProject.layout.projectDirectory.dir("third_party/xenia-patches"))
        buildMode.set(if (variant.buildType == "debug") "incremental" else "full")
        sourceCacheDir.set(layout.buildDirectory.dir("xeniaSourceCache/${variant.name}"))
        workspaceCacheDir.set(layout.buildDirectory.dir("xeniaDevWorkspaces/${variant.name}"))
        outputDir.set(layout.buildDirectory.dir("generated/xeniaBringupAssets/${variant.name}"))
    }

    tasks.register<Delete>("reset${variantName}XeniaDevWorkspace") {
        delete(layout.buildDirectory.dir("xeniaDevWorkspaces/${variant.name}"))
    }

    val taskName = "stage${variant.name.replaceFirstChar(Char::uppercaseChar)}RuntimePayload"
    val taskProvider = tasks.register<StageRuntimePayloadTask>(taskName) {
        dependsOn(generateFexGuestAssetsTask)
        dependsOn(generateVulkanGuestAssetsTask)
        dependsOn(generateTurnipMesaAssetsTask)
        dependsOn(generateXeniaBringupAssetsTask)
        mockRuntimeDir.set(layout.projectDirectory.dir("src/main/mock-runtime"))
        generatedFexRuntimeDir.set(generateFexGuestAssetsTask.flatMap { it.outputDir })
        generatedVulkanRuntimeDir.set(generateVulkanGuestAssetsTask.flatMap { it.outputDir })
        generatedTurnipRuntimeDir.set(generateTurnipMesaAssetsTask.flatMap { it.outputDir })
        generatedXeniaRuntimeDir.set(generateXeniaBringupAssetsTask.flatMap { it.outputDir })
        val localDrop = rootProject.layout.projectDirectory.dir("_local/runtime-drop")
        if (localDrop.asFile.exists()) {
            localRuntimeDropDir.set(localDrop)
        }
        includeLocalDrop.set(variant.buildType == "debug")
        outputDir.set(layout.buildDirectory.dir("generated/runtimePayload/${variant.name}"))
    }

    variant.sources.assets?.addGeneratedSourceDirectory(taskProvider, StageRuntimePayloadTask::outputDir)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildFexArtifactsTask, BuildFexArtifactsTask::outputDir)
}
