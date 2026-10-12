plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
application { mainClass.set("pt.tiago.bubblenight.leveltool.MainKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    maxHeapSize = "768m"
}
tasks.withType<Test>().configureEach { maxHeapSize = "512m" }

val contentRoot = rootProject.layout.projectDirectory.dir("content")
val assetOutput = rootProject.layout.projectDirectory.dir("app/build/generated/bubbleAssets")
val verificationStamp = layout.buildDirectory.file("reports/content-verified.txt")
val validateGameContent = tasks.register<JavaExec>("validateGameContent") {
    group = "verification"
    description = "Replays every published certificate with the actual game engine."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "768m"
    args("verify-certificates", "--catalog", "content/catalog.json")
    inputs.dir(contentRoot)
    outputs.file(verificationStamp)
    // Only use the Task itself inside the lambda: script references break the configuration cache.
    doLast {
        val stamp = outputs.files.singleFile
        stamp.parentFile.mkdirs()
        stamp.writeText("All packaged variants verified with current engine.\n")
    }
}
tasks.register<JavaExec>("exportGameAssets") {
    group = "build"
    description = "Exports the validated offline catalog into Android assets."
    dependsOn(validateGameContent)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "768m"
    args("export-assets", "--catalog", "content/catalog.json", "--output", assetOutput.asFile.absolutePath)
    inputs.dir(contentRoot)
    outputs.dir(assetOutput)
}
