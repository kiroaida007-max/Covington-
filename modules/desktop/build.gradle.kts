plugins {
    application
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(project(":app-recovery"))
    implementation(libs.flatlaf)
    implementation(libs.rsyntaxtextarea)
    implementation(libs.serialization.json)
}

application {
    applicationName = "apkproject-studio"
    mainClass.set("dev.apkproject.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=60", "-Djava.net.useSystemProxies=true")
}

// The toolchain profiles travel inside the app; the desktop app writes them out next to its tools.
tasks.processResources {
    from(rootProject.file("profiles")) { into("dev/apkproject/desktop/profiles") }
}

/**
 * A self-contained app (or installer) with a Java runtime that includes javac, which the round
 * trip needs. `-Ptype=app-image` everywhere; `-Ptype=exe` or `msi` on Windows, `deb` on Linux,
 * `dmg` on macOS (jpackage builds installers only for the platform it runs on).
 */
val packageApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a native app image or installer with jpackage."
    dependsOn(tasks.named("installDist"))
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
        val jar = lib.get().asFile.listFiles()!!.single { it.name.startsWith("desktop-") }
        val args = mutableListOf(
            home.resolve("bin/" + if (windows) "jpackage.exe" else "jpackage").absolutePath,
            "--type", type, "--name", "APK Project Studio", "--app-version", "0.2.0", "--vendor", "apkproject",
            "--description", "Recover an Android app's source, names and backend contract, checked against its bytecode",
            "--input", lib.get().asFile.absolutePath, "--main-jar", jar.name, "--main-class", "dev.apkproject.desktop.MainKt",
            "--runtime-image", runtime.get().asFile.absolutePath, "--dest", out.get().asFile.absolutePath,
            "--java-options", "-XX:MaxRAMPercentage=60", "--java-options", "-Djava.net.useSystemProxies=true",
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
