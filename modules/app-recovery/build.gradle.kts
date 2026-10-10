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

// Keep R8 reports separate from the full suite, which this optional benchmark used to overwrite.
tasks.register<Test>("libraryMatchingBenchmark") {
    group = "verification"
    description = "Evaluates inferred library names against fixtures specified by APKPROJECT_LIBMATCH."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*LibraryMatchingBenchmark*") }
    outputs.upToDateWhen { false }
}
