plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "pt.tiago.bubblenight"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "pt.tiago.bubblenight"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.14.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Release signing is read from the developer's ~/.gradle/gradle.properties and never committed.
    // Without these properties (CI, F-Droid) the release build is produced unsigned.
    val releaseStoreFile = providers.gradleProperty("bubbleNight.release.storeFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = providers.gradleProperty("bubbleNight.release.storePassword").get()
            keyAlias = providers.gradleProperty("bubbleNight.release.keyAlias").get()
            keyPassword = providers.gradleProperty("bubbleNight.release.keyPassword").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Keep the git revision out of the APK so release builds are reproducible from source.
            vcsInfo.include = false
        }
    }
    // The dependency metadata block is encrypted for Google Play only; F-Droid rejects it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("generated/bubbleAssets").get().asFile.path)
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.datastore)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

tasks.named("preBuild") { dependsOn(":leveltool:exportGameAssets") }
