pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "apkproject"

// Module ownership follows sections 08-09 of the architecture dossier.
// Modules for later milestones (formats-resources, graph, desktop) are added
// when their first gate is worked on, not as empty placeholders.
listOf(
    "contracts", "store", "archive", "formats-dex", "dex-asm", "ir", "lift-dex",
    "source-model", "reconstruct", "compiler-runner", "validate", "experiments",
    "export", "orchestrator", "cli", "benchmarks",
).forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("modules/$name")
}
