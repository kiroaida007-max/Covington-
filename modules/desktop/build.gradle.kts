import java.security.MessageDigest

plugins {
    application
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(project(":app-recovery"))
    implementation(libs.rsyntaxtextarea)
    implementation(libs.serialization.json)
}

application {
    applicationName = "apkproject-studio"
    mainClass.set("dev.apkproject.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx512m", "-Djava.net.useSystemProxies=true")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "512m" // Exercise report/model loading within the shipped desktop heap budget.
    inputs.property("performanceMode", providers.environmentVariable("APKPROJECT_PERFORMANCE").orElse("0"))
}

// The toolchain profiles travel inside the app; the desktop app writes them out next to its tools.
tasks.processResources {
    from(rootProject.file("profiles")) { into("dev/apkproject/desktop/profiles") }
    val releaseVersion = providers.gradleProperty("appVersion").getOrElse("0.3.0")
    inputs.property("appVersion", releaseVersion)
    doLast {
        require(releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        destinationDir.resolve("release-version.txt").writeText(releaseVersion)
    }
    providers.gradleProperty("updatePublicKey").orNull?.let { key -> from(file(key)) { rename { "update-public-key.der" } } }
}

val productSbom by tasks.registering {
    group = "distribution"
    description = "Inventories exact bundled JVM artifacts (CycloneDX 1.5)."
    dependsOn(tasks.named("installDist"))
    val sbom = layout.buildDirectory.file("product-sbom.cdx.json")
    outputs.file(sbom)
    inputs.files(configurations.runtimeClasspath)
    inputs.file(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    inputs.property("appVersion", project.version.toString())
    doLast {
        fun sha(file: File) = MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input -> val block = ByteArray(65536); while (true) { val n = input.read(block); if (n < 0) break; md.update(block, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        val components = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }.map { artifact ->
                val id = artifact.moduleVersion.id
                mapOf("type" to "library", "group" to id.group, "name" to id.name, "version" to id.version,
                    "bom-ref" to "pkg:maven/${id.group}/${id.name}@${id.version}", "purl" to "pkg:maven/${id.group}/${id.name}@${id.version}",
                    "properties" to listOf(mapOf("name" to "apkproject:artifact-file", "value" to artifact.file.name)),
                    "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha(artifact.file))))
            }
        val data = mapOf("bomFormat" to "CycloneDX", "specVersion" to "1.5", "version" to 1,
            "metadata" to mapOf("component" to mapOf("type" to "application", "name" to "APK Project Studio", "version" to project.version.toString(),
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha(tasks.named<Jar>("jar").get().archiveFile.get().asFile)))),
                "properties" to listOf(mapOf("name" to "apkproject:inventory-scope", "value" to "Bundled JVM library artifacts; OpenJDK notices/runtime legal and separately downloaded toolchain have separate inventories"))),
            "components" to components)
        sbom.get().asFile.apply { parentFile.mkdirs(); writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(data)) + "\n") }
    }
}

/**
 * A self-contained app (or installer) with a Java runtime that includes javac, which the round
 * trip needs. `-Ptype=app-image` everywhere; `-Ptype=exe` or `msi` on Windows, `deb` on Linux,
 * `dmg` on macOS (jpackage builds installers only for the platform it runs on).
 */
val packageApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a native app image or installer with jpackage."
    dependsOn(productSbom)
    val type = (project.findProperty("type") as String?) ?: "app-image"
    val javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.map { it.metadata.installationPath.asFile }
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val out = layout.buildDirectory.dir("jpackage")
    val runtime = layout.buildDirectory.dir("jpackage-runtime")
    val lib = layout.buildDirectory.dir("install/apkproject-studio/lib")
    val icon = file(if (windows) "packaging/icon.ico" else "packaging/icon.png")
    doFirst {
        val home = javaHome.get()
        val jlink = home.resolve("bin/" + if (windows) "jlink.exe" else "jlink")
        delete(runtime, out.get().asFile.resolve("APK Project Studio"))
        project.exec {
            commandLine(
                jlink.absolutePath, "--add-modules",
                "java.se,jdk.compiler,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata,jdk.charsets,jdk.jdeps,jdk.management,jdk.accessibility",
                "--strip-debug", "--no-header-files", "--no-man-pages", "--output", runtime.get().asFile.absolutePath,
            )
        }
        copy { from(layout.buildDirectory.file("product-sbom.cdx.json")); from("packaging/THIRD-PARTY-NOTICES.md"); from(rootProject.file("LICENSE")); into(lib) }
        val jar = lib.get().asFile.listFiles()!!.single { it.name.startsWith("desktop-") }
        val args = mutableListOf(
            home.resolve("bin/" + if (windows) "jpackage.exe" else "jpackage").absolutePath,
            "--type", type, "--name", "APK Project Studio", "--app-version", providers.gradleProperty("appVersion").getOrElse("0.3.0"), "--vendor", "apkproject",
            "--description", "Recover an Android app's source, names and backend contract, checked against its bytecode",
            "--input", lib.get().asFile.absolutePath, "--main-jar", jar.name, "--main-class", "dev.apkproject.desktop.MainKt",
            "--runtime-image", runtime.get().asFile.absolutePath, "--dest", out.get().asFile.absolutePath,
            "--java-options", "-Xmx512m", "--java-options", "-Djava.net.useSystemProxies=true",
        )
        if (icon.isFile) args += listOf("--icon", icon.absolutePath)
        if (windows && type != "app-image") args += listOf("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install", "--win-menu-group", "APK Project Studio", "--file-associations", file("packaging/apk-association.properties").absolutePath)
        if (type == "deb") args += listOf("--linux-shortcut")
        commandLine(args)
    }
}

/** Opens every screen and dialog and saves screenshots to build/screenshots (needs a display and the tools). */
val screenshots by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Recovers a demo app and saves a screenshot of every screen and dialog."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.apkproject.desktop.ScreenshotTour")
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    // -PscreenshotsJava=21 where the JDK 17 install is headless (no X11 libraries).
    val java = (project.findProperty("screenshotsJava") as String?)?.toInt() ?: 17
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(java)) })
    jvmArgs("-Djava.net.useSystemProxies=true")
    // X11 (Linux, Xvfb): the forked JVM does not always inherit the display.
    System.getenv("DISPLAY")?.let { environment("DISPLAY", it) }
    System.getenv("XAUTHORITY")?.let { environment("XAUTHORITY", it) }
}

// Regenerate committed native launcher artwork from AppIcon.
tasks.register<JavaExec>("generateIcons") {
    group = "distribution"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.apkproject.desktop.DesktopIcons")
    args(file("packaging").absolutePath)
}
