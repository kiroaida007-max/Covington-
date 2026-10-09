// Ground-truth fixtures and evaluation. Separate access boundary: hidden
// sources are read here and never by the recovery modules.
plugins {
    application
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(project(":compiler-runner"))
    implementation(project(":validate"))
}

application {
    mainClass.set("dev.apkproject.benchmarks.MainKt")
}
