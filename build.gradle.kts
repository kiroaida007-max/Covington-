plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = "dev.apkproject"
    version = providers.gradleProperty("appVersion").getOrElse("0.3.0")
    val engineVersionDir = layout.buildDirectory.dir("generated/engine-version")
    val writeEngineVersion = tasks.register("writeEngineVersion") {
        inputs.property("engineVersion", project.version.toString())
        outputs.dir(engineVersionDir)
        doLast { engineVersionDir.get().asFile.apply { mkdirs(); resolve("engine-version.txt").writeText(project.version.toString()) } }
    }
    tasks.withType<ProcessResources>().configureEach { dependsOn(writeEngineVersion); from(engineVersionDir) }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            allWarningsAsErrors.set(false)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(kotlin("test"))
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        // Integration tests that need external tools read their locations from
        // the toolchain profile; see scripts/setup-toolchain.sh.
        systemProperty("apkproject.root", rootProject.projectDir.absolutePath)
        environment("APKPROJECT_TOOLS", System.getenv("APKPROJECT_TOOLS") ?: "${rootProject.projectDir}/.tools")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
