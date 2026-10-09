plugins {
    application
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(project(":app-recovery"))
    implementation(project(":dex-asm"))
}

application {
    applicationName = "apkproject"
    mainClass.set("dev.apkproject.cli.MainKt")
}
