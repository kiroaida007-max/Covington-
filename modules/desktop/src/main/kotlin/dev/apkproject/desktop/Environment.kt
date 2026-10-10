package dev.apkproject.desktop

import dev.apkproject.contracts.Profiles
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.contracts.isWindows
import dev.apkproject.orchestrator.ToolchainInstaller
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.prefs.Preferences

/**
 * Where the app keeps its tools and settings, and which toolchain profile and JDK it runs with.
 * Windows: %LOCALAPPDATA%\apkproject; macOS: ~/Library/Application Support/apkproject;
 * Linux: $XDG_DATA_HOME/apkproject. A repository checkout with `.tools` uses those tools.
 */
class Environment(val appData: Path, private val settings: Settings = Settings()) {
    val tools: Path
        get() = settings.toolsDir?.let { Paths.get(it) }
            ?: System.getenv("APKPROJECT_TOOLS")?.let { Paths.get(it) }
            ?: repositoryTools()
            ?: appData.resolve("tools")

    val profileName: String get() = ToolchainInstaller.profileName()

    /** The bundled runtime when it has javac (a packaged app), else an installed JDK 17. */
    fun jdkHome(): Path? {
        val running = Paths.get(System.getProperty("java.home"))
        val javac = running.resolve("bin").resolve(if (isWindows()) "javac.exe" else "javac")
        if (Runtime.version().feature() == 17 && Files.isRegularFile(javac)) return running
        return Profiles.findJdk(17)
    }

    /** The profile file, written out from the app's own copy so the pins always match this build. */
    fun profileFile(): Path {
        val dir = Files.createDirectories(appData.resolve("profiles"))
        for (name in listOf("android-full.json", "android-windows.json", "v0-jvm-pure.json")) {
            val bytes = javaClass.getResourceAsStream("profiles/$name")?.use { it.readAllBytes() } ?: continue
            val target = dir.resolve(name)
            if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) Files.write(target, bytes)
        }
        return dir.resolve(profileName)
    }

    fun profile(): ResolvedProfile {
        val env = HashMap(System.getenv())
        env["APKPROJECT_TOOLS"] = tools.toString()
        jdkHome()?.let { env["JDK17_HOME"] = it.toString() }
        return Profiles.load(profileFile(), env)
    }

    fun installer() = ToolchainInstaller(tools)

    /** `<checkout>/.tools` when the app runs from a source checkout that has its tools installed. */
    private fun repositoryTools(): Path? {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(".tools")
            if (Files.isDirectory(candidate.resolve("jadx-1.5.6")) && Files.isRegularFile(dir.resolve("settings.gradle.kts"))) return candidate
            dir = dir.parent
        }
        return null
    }

    companion object {
        fun detect(): Environment = Environment(appDataDir())

        fun appDataDir(): Path {
            val home = Paths.get(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            return when {
                isWindows() -> Paths.get(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString(), "apkproject")
                os.contains("mac") -> home.resolve("Library/Application Support/apkproject")
                else -> Paths.get(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString(), "apkproject")
            }
        }
    }
}

/** User choices that persist between runs (stored by java.util.prefs: the registry on Windows). */
class Settings(private val node: Preferences? = runCatching { Preferences.userRoot().node("dev/apkproject/desktop") }.getOrNull()) {
    var toolsDir: String?
        get() = node?.get("toolsDir", null)
        set(value) { if (value == null) node?.remove("toolsDir") else node?.put("toolsDir", value) }
    var lastFolder: String?
        get() = node?.get("lastFolder", null)
        set(value) { if (value != null) node?.put("lastFolder", value) }

    fun option(name: String, default: Boolean): Boolean = node?.getBoolean("option.$name", default) ?: default
    fun setOption(name: String, value: Boolean) { node?.putBoolean("option.$name", value) }
}

/** The steps of a recovery as the user sees them, recognised from the engine's log lines. */
enum class Stage(val title: String, private vararg val prefixes: String) {
    READ("Read the app", "input ", "anatomy:"),
    NAMES("Recover original names", "library naming", "original names", "names:"),
    DECOMPILE("Decompile to Java", "decompiling with JADX", "JADX ", "collecting REA", "removed ", "resolving ", "replaced ", "library "),
    PROVE("Prove simple methods", "cross-check"),
    COMPILE("Check that it compiles", "compiling recovered Java", "compile check"),
    VERIFY("Verify every method", "recompiling ", "javac ", "round-trip verification", "round trip:"),
    REPAIR("Repair decompiler mistakes", "verified repair", "repair:"),
    BACKEND("Backend contract and mock server", "backend contract", "recovering JavaScript"),
    WRITE("Write the project");

    companion object {
        fun of(line: String): Stage? = entries.firstOrNull { s -> s.prefixes.any { line.startsWith(it) } }
    }
}
