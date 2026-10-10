plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":store"))
    api(project(":archive"))
    api(project(":formats-dex"))
    api(project(":lift-dex"))
    api(project(":reconstruct"))
    api(project(":compiler-runner"))
    api(project(":validate"))
    api(project(":experiments"))
    api(project(":export"))
    testImplementation(project(":dex-asm"))
}
