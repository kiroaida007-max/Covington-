// Ground-truth fixtures and evaluation. Separate access boundary: hidden
// sources are read here and never by the recovery modules.
plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(project(":compiler-runner"))
    implementation(project(":validate"))
    implementation(project(":app-recovery"))
}

application {
    mainClass.set("dev.apkproject.benchmarks.MainKt")
}

tasks.named<JavaExec>("run") {
    args(rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
    environment("APKPROJECT_TOOLS", System.getenv("APKPROJECT_TOOLS") ?: "${rootProject.projectDir}/.tools")
}
