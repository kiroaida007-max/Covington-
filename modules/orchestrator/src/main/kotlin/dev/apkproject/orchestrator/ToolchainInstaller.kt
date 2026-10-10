package dev.apkproject.orchestrator

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.isWindows
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Downloads, verifies and unpacks the pinned tools a profile names: the portable equivalent of
 * `scripts/setup-toolchain.sh`, for the desktop app and for Windows. Every download is checked
 * against its pinned SHA-256 before anything is unpacked, and every installed file again after.
 */
class ToolchainInstaller(
    private val tools: Path,
    private val specs: List<ToolSpec> = pinned(Platform.current()),
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build(),
) {
    enum class Platform {
        LINUX_X64, WINDOWS_X64, MAC_X64, MAC_ARM64, OTHER;

        companion object {
            fun current(): Platform {
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                val x64 = arch == "amd64" || arch == "x86_64"
                return when {
                    isWindows() && x64 -> WINDOWS_X64
                    os.contains("linux") && x64 -> LINUX_X64
                    os.contains("mac") && x64 -> MAC_X64
                    os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> MAC_ARM64
                    else -> OTHER
                }
            }
        }
    }

    /** How a downloaded file becomes installed files. */
    sealed interface Unpack {
        /** The download is the tool: saved as [path]. */
        data class AsFile(val path: String, val executable: Boolean = false) : Unpack

        /** Every zip entry under [prefix], written below [into] with the prefix removed. */
        data class Unzip(val into: String, val prefix: String = "", val executables: Set<String> = emptySet(), val only: Set<String>? = null) : Unpack
    }

    data class ToolSpec(
        val id: String,
        val title: String,
        /** Mirrors, tried in order. */
        val urls: List<String>,
        val sha256: String,
        val unpack: Unpack,
        /** Installed file (relative to the tools folder) and its pinned SHA-256: the tool counts as installed when it matches. */
        val installed: Pair<String, String>,
        val optional: Boolean = false,
    )

    data class Status(val spec: ToolSpec, val state: State, val detail: String) {
        enum class State { INSTALLED, MISSING, CORRUPT, FAILED, UNSUPPORTED }
    }

    fun interface Progress {
        /** [total] is null while the size is unknown; [step] is a spec id. */
        fun report(step: String, message: String, done: Long, total: Long?)
    }

    fun status(): List<Status> = specs.map { spec ->
        val file = tools.resolve(spec.installed.first)
        when {
            !Files.isRegularFile(file) -> Status(spec, Status.State.MISSING, "not installed")
            Hashing.sha256(file) != spec.installed.second -> Status(spec, Status.State.CORRUPT, "${spec.installed.first} does not match its pinned SHA-256")
            else -> Status(spec, Status.State.INSTALLED, file.toString())
        }
    }

    /** Installs every missing or damaged tool. Failures of optional tools do not stop the others. */
    fun install(progress: Progress = Progress { _, _, _, _ -> }, cancel: CancellationToken? = null): List<Status> {
        Files.createDirectories(tools)
        val results = ArrayList<Status>()
        for (current in status()) {
            if (cancel?.cancelled == true) throw dev.apkproject.compiler.CancelledException()
            val spec = current.spec
            if (current.state == Status.State.INSTALLED) {
                results += current
                continue
            }
            results += try {
                fetchAndUnpack(spec, progress, cancel)
                val file = tools.resolve(spec.installed.first)
                if (Hashing.sha256(file) != spec.installed.second) Status(spec, Status.State.FAILED, "installed ${spec.installed.first} does not match its pinned SHA-256")
                else Status(spec, Status.State.INSTALLED, file.toString()).also { progress.report(spec.id, "${spec.title} ready", 1, 1) }
            } catch (e: dev.apkproject.compiler.CancelledException) {
                throw e
            } catch (e: Exception) {
                Status(spec, Status.State.FAILED, e.message ?: e.toString())
            }
        }
        return results
    }

    private fun fetchAndUnpack(spec: ToolSpec, progress: Progress, cancel: CancellationToken?) {
        val downloads = Files.createDirectories(tools.resolve(".downloads"))
        val archive = downloads.resolve(spec.id + ".part")
        var lastError: Exception? = null
        for (url in spec.urls) {
            for (attempt in 0 until 3) {
                try {
                    download(url, archive, spec, progress, cancel)
                    val actual = Hashing.sha256(archive)
                    if (actual != spec.sha256) throw IOException("${spec.title}: downloaded file has SHA-256 $actual, expected ${spec.sha256} ($url)")
                    progress.report(spec.id, "Unpacking ${spec.title}", 0, null)
                    unpack(spec.unpack, archive)
                    Files.deleteIfExists(archive)
                    return
                } catch (e: dev.apkproject.compiler.CancelledException) {
                    Files.deleteIfExists(archive)
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (e.message?.contains("SHA-256") == true) break // a wrong file does not get better on retry
                    Thread.sleep(1000L shl attempt)
                }
            }
        }
        Files.deleteIfExists(archive)
        throw IOException("could not install ${spec.title}: ${lastError?.message}", lastError)
    }

    private fun download(url: String, target: Path, spec: ToolSpec, progress: Progress, cancel: CancellationToken?) {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).header("User-Agent", "apkproject-toolchain").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("${spec.title}: HTTP ${response.statusCode()} from $url")
        }
        val total = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent && it.asLong > 0) it.asLong else null }
        response.body().use { input ->
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                var reported = 0L
                while (true) {
                    if (cancel?.cancelled == true) throw dev.apkproject.compiler.CancelledException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - reported >= 1 shl 20 || (total != null && done == total)) {
                        progress.report(spec.id, "Downloading ${spec.title}", done, total)
                        reported = done
                    }
                }
            }
        }
    }

    private fun unpack(how: Unpack, archive: Path) {
        when (how) {
            is Unpack.AsFile -> {
                val target = tools.resolve(how.path)
                Files.createDirectories(target.parent)
                Files.move(archive, target, StandardCopyOption.REPLACE_EXISTING)
                if (how.executable) makeExecutable(target)
            }
            is Unpack.Unzip -> {
                val into = tools.resolve(how.into).normalize()
                Files.createDirectories(into)
                ZipInputStream(Files.newInputStream(archive)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory || !entry.name.startsWith(how.prefix)) continue
                        val relative = entry.name.removePrefix(how.prefix)
                        if (relative.isEmpty() || (how.only != null && relative !in how.only)) continue
                        val target = into.resolve(relative).normalize()
                        // Zip-slip: an entry may not escape its folder.
                        if (!target.startsWith(into)) throw IOException("archive entry escapes its folder: ${entry.name}")
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                        if (relative in how.executables) makeExecutable(target)
                    }
                }
            }
        }
    }

    private fun makeExecutable(file: Path) {
        runCatching {
            val permissions = Files.getPosixFilePermissions(file).toMutableSet()
            permissions += setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    /** Copies runtime DLLs the Windows Z3 build expects but does not ship from the JDK that runs us. */
    fun completeWindowsZ3(jdkHome: Path) {
        val bin = tools.resolve("z3/bin")
        if (!Files.isDirectory(bin)) return
        for (dll in listOf("vcruntime140_1.dll", "vcruntime140.dll", "msvcp140.dll")) {
            val source = jdkHome.resolve("bin").resolve(dll)
            val target = bin.resolve(dll)
            if (!Files.exists(target) && Files.isRegularFile(source)) Files.copy(source, target)
        }
    }

    companion object {
        private const val CENTRAL = "https://repo.maven.apache.org/maven2"
        private const val CENTRAL_MIRROR = "https://maven-central.storage-download.googleapis.com/maven2"

        /** The tools of profiles/android-full.json (Linux, macOS) and profiles/android-windows.json, as pinned there. */
        fun pinned(platform: Platform): List<ToolSpec> {
            val common = listOf(
                ToolSpec(
                    "d8", "D8/R8 8.7.18",
                    listOf("https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar"),
                    "58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903",
                    Unpack.AsFile("r8-8.7.18.jar"),
                    "r8-8.7.18.jar" to "58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903",
                ),
                ToolSpec(
                    "jadx", "JADX 1.5.6",
                    listOf("https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip"),
                    "545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974",
                    Unpack.Unzip("jadx-1.5.6"),
                    "jadx-1.5.6/lib/jadx-1.5.6-all.jar" to "fe3e12c45acf75f92369685fd02d1d7a7323385dc725680a9b98a0dac0ea554b",
                ),
                ToolSpec(
                    "android", "Android platform 35",
                    listOf("https://dl.google.com/android/repository/platform-35_r02.zip"),
                    "0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0",
                    Unpack.Unzip("android-35", "android-35/", only = setOf("android.jar")),
                    "android-35/android.jar" to "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b",
                ),
                ToolSpec(
                    "ecj", "Eclipse compiler 3.45.0",
                    listOf("$CENTRAL/org/eclipse/jdt/ecj/3.45.0/ecj-3.45.0.jar", "$CENTRAL_MIRROR/org/eclipse/jdt/ecj/3.45.0/ecj-3.45.0.jar"),
                    "c8f5e66787ea6e4e0394ba3051c86a4edc50683ce9850f71739646529a99a2d7",
                    Unpack.AsFile("ecj-3.45.0.jar"),
                    "ecj-3.45.0.jar" to "c8f5e66787ea6e4e0394ba3051c86a4edc50683ce9850f71739646529a99a2d7",
                ),
            )
            val z3 = when (platform) {
                Platform.WINDOWS_X64 -> ToolSpec(
                    "z3", "Z3 4.13.3 (proofs)",
                    listOf("https://github.com/Z3Prover/z3/releases/download/z3-4.13.3/z3-4.13.3-x64-win.zip"),
                    "014e313f5aa414c48f36620a32eced705002fdc8c840ed8a1b51f186f0da0348",
                    Unpack.Unzip("z3/bin", "z3-4.13.3-x64-win/bin/"),
                    "z3/bin/z3.exe" to "97a17cbe1f93f859a3b2b7951211e30fc789be8bf548db464408018f4429b740",
                    optional = true,
                )
                Platform.LINUX_X64 -> ToolSpec(
                    "z3", "Z3 4.13.3 (proofs)",
                    listOf("https://files.pythonhosted.org/packages/b6/f2/036f6501d3a2835cd4a17fd67364cd56337a5183e20b063d3d8a005f7288/z3_solver-4.13.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl"),
                    "794843e4946ade1561e40a75ffc1163b45d36b493fd6cc269ad1d6a65bddb8e5",
                    Unpack.Unzip("z3/bin", "z3_solver-4.13.3.0.data/data/bin/", executables = setOf("z3")),
                    "z3/bin/z3" to "1cc0766515f49af2b79d2243685f496814b0feb9ceab28744cfecdbf7c5f5d9c",
                    optional = true,
                )
                else -> null
            }
            return common + listOfNotNull(z3)
        }

        /** The profile the desktop app uses on this platform. */
        fun profileName(platform: Platform = Platform.current()): String = if (platform == Platform.WINDOWS_X64) "android-windows.json" else "android-full.json"
    }
}
