@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.room)
    alias(libs.plugins.ktfmt.gradle)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")

val splitApks = !providers.gradleProperty("noSplits").isPresent

val abiFilterList = providers.gradleProperty("ABI_FILTERS").get().split(';')

val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

val baseVersionName = currentVersion.name
val currentVersionCode = currentVersion.code.toInt()

android {
    compileSdk { version = release(37) }

    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
        signingConfigs {
            create("githubPublish") {
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
                storeFile = file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"].toString()
            }
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    defaultConfig {
        applicationId = "com.junkfood.seal"
        minSdk = 24
        targetSdk = 37
        versionCode = 200_000_150
        check(versionCode == currentVersionCode)

        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    isUniversalApk = true
                }
            }
        } else {
            ndk { abiFilters.addAll(abiFilterList) }
        }
    }

    room { schemaDirectory("$projectDir/schemas") }
    ksp { arg("room.incremental", "true") }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
        }
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Seal Debug")
        }
    }

    flavorDimensions += "publishChannel"

    productFlavors {
        create("generic") {
            dimension = "publishChannel"
            isDefault = true
        }

        create("githubPreview") {
            dimension = "publishChannel"
            applicationIdSuffix = ".preview"
            resValue("string", "app_name", "Seal Preview")
        }

        create("fdroid") {
            dimension = "publishChannel"
            versionName = "$baseVersionName-(F-Droid)"
        }
    }

    lint { disable.addAll(listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")) }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs.useLegacyPackaging = true
    }
    androidResources { generateLocaleConfig = true }

    namespace = "com.junkfood.seal"
}

ktfmt { kotlinLangStyle() }

kotlin {
    jvmToolchain(21)
    compilerOptions { optIn.add("kotlin.RequiresOptIn") }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi =
                if (splitApks) {
                    output.filters
                        .find { it.filterType == FilterConfiguration.FilterType.ABI }
                        ?.identifier
                } else {
                    abiFilterList.firstOrNull()
                }
            abiCodes[abi]?.let { code -> output.versionCode.set(currentVersionCode + code) }
            val outputName =
                listOfNotNull(
                        variant.flavorName?.takeIf { it.isNotEmpty() },
                        if (splitApks) abi ?: "universal" else null,
                        variant.buildType,
                    )
                    .joinToString("-")
            output.outputFileName.set("Seal-$baseVersionName-$outputName.apk")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":color"))

    implementation(libs.bundles.core)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidxCompose)
    implementation(libs.bundles.accompanist)

    implementation(libs.coil.kt.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)

    implementation(libs.bundles.youtubedlAndroid)

    implementation(libs.mmkv)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.compose.ui.tooling)
}

// The reference remains untransformed; exact artifact identity scopes the overlay.
val sealWebp16k = Attribute.of("com.junkfood.seal.webp16k", Boolean::class.javaObjectType)
val artifactType = Attribute.of("artifactType", String::class.java)
configurations.configureEach { attributes.attribute(sealWebp16k, true) }
val ffmpegReference = configurations.create("ffmpegReference") {
    isCanBeConsumed = false
    isTransitive = false
    attributes.attribute(sealWebp16k, false)
}
dependencies.add(ffmpegReference.name, libs.youtubedl.android.ffmpeg)
dependencies {
    attributesSchema { attribute(sealWebp16k) }
    artifactTypes.maybeCreate("aar").attributes.attribute(sealWebp16k, false)
    registerTransform(SealWebp16kTransform::class) {
        from.attribute(sealWebp16k, false).attribute(artifactType, "aar")
        to.attribute(sealWebp16k, true).attribute(artifactType, "aar")
        parameters {
            upstreamArtifact.set(layout.file(providers.provider { ffmpegReference.singleFile }))
            overlayDirectory.set(rootProject.layout.projectDirectory.dir("tools/webp16k/overlay"))
            overlayManifest.set(rootProject.layout.projectDirectory.file("tools/webp16k/SHA256SUMS"))
            overlayManifestSha256.set("0cadfac35a48a716f42fe8a0084222c55371091f92ea37dd09488f537f252a0b")
            upstreamSha256.set("0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49")
        }
    }
}
tasks.register<SealWebp16kTransform.WriteReference>("writeWebp16kReference") {
    upstreamAar.from(ffmpegReference)
    referenceFile.set(layout.buildDirectory.file("webp16k/upstream-aar.txt"))
}
