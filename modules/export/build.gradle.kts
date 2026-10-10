plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":contracts"))
    api(project(":source-model"))
    api(project(":store"))
}

// Ship the repository's own pinned Gradle wrapper so exported projects build with a
// checksum-verified distribution.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory) {
        include("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
        into("wrapper")
    }
}
