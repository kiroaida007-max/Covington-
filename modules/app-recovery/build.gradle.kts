// Whole-application recovery: every layer an APK contains, with an explicit
// evidence level per output (validated, decompiled, extracted, inferred, synthesized).
plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":archive"))
    api(project(":formats-dex"))
    api(project(":lift-dex"))
    api(project(":reconstruct"))
    api(project(":compiler-runner"))
    api(project(":validate"))
    api(project(":store"))
    testImplementation(project(":dex-asm"))
}

tasks.withType<Test>().configureEach {
    systemProperty("apkproject.worker.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty("apkproject.contract.fixtureDir", layout.buildDirectory.dir("contract-assurance").get().asFile.absolutePath)
    // Installing an optional real-app dataset must invalidate a previously cached skip.
    inputs.files(rootProject.fileTree(".tools/dl/tusky") { include("*.apk") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Keep R8 reports separate from the full suite, which this optional benchmark used to overwrite.
tasks.register<Test>("libraryMatchingBenchmark") {
    group = "verification"
    description = "Evaluates inferred library names against fixtures specified by APKPROJECT_LIBMATCH."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*LibraryMatchingBenchmark*") }
    outputs.upToDateWhen { false }
}
