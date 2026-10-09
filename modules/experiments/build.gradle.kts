plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":reconstruct"))
    api(project(":lift-dex"))
    api(project(":compiler-runner"))
    testImplementation(project(":dex-asm"))
}
