// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026-present Open Handheld Collective
//   https://github.com/OpenHandheldCollective

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val installer = file("../VERSION").readText().trim()
val (major, minor, patch) = (installer.split(".").map { it.toInt() } + listOf(0, 0)).take(3)

android {
    namespace = "org.openhandheldcollective.openabl.installer"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.openhandheldcollective.openabl.installer"
        minSdk = 30
        targetSdk = 35
        versionCode = major * 10000 + minor * 100 + patch
        versionName = installer
    }

    // A release is signed only when the key is given in the environment.
    val keystore = providers.environmentVariable("OPENABL_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("OPENABL_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("OPENABL_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("OPENABL_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The hash table and licenses go into the APK's assets. The images are downloaded by the app.
val copyAssets by tasks.registering(Copy::class) {
    from("../known-images.txt")
    from("../licenses") { into("licenses") }
    into("src/main/assets")
}

tasks.named("preBuild") { dependsOn(copyAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
