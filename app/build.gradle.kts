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
        versionCode = 24
        versionName = "0.14.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
