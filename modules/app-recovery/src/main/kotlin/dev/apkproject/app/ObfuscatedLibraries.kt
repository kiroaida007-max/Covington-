package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Names for library classes R8 renamed. The library versions the APK itself declares
 * (META-INF version files) are fetched, dexed with D8 and matched class by class
 * ([LibraryMatcher]); matched classes are then decompiled under their original names.
 */
class ObfuscatedLibraries(private val profile: ResolvedProfile, private val cancel: CancellationToken? = null, private val log: (String) -> Unit = {}) {
    /** An APK whose classes are mostly short, meaningless names (or that R8 stamped) is treated as obfuscated. */
    fun obfuscated(dex: List<Pair<String, DexFile>>): Boolean {
        val names = dex.flatMap { (_, d) -> d.classDefs.map { d.type(it.classIdx) } }
        if (names.isEmpty()) return false
        val short = names.count { it.removeSuffix(";").substringAfterLast('/').substringBefore('$').length <= 2 }
        return short * 3 > names.size || CompilerMarker.read(dex).any { it.tool == "R8" }
    }

    fun recognise(dex: List<Pair<String, DexFile>>, candidates: List<LibraryVersion>, minApi: Int, workDir: Path, timeoutMs: Long = 900_000): LibraryMatcher.Result? {
        if (candidates.isEmpty() || profile.androidJar == null) return null
        val jars = LibraryResolver(profile).fetchClassJars(candidates, workDir)
        if (jars.isEmpty()) return null
        log("library naming: dexing ${jars.size} declared library versions for comparison...")
        val libDex = dexReference(jars.flatMap { it.second }, minApi, Files.createDirectories(workDir.resolve("reference-dex")), timeoutMs) ?: return null
        if (cancel?.cancelled == true) throw CancelledException()
        val apk = LibraryMatcher.profiles(dex.map { it.second })
        val lib = LibraryMatcher.profiles(libDex)
        val known = apk.keys.filter { it in lib }.associateWith { it }
        val result = LibraryMatcher.match(apk, lib, known)
        log("library naming: ${result.classes.size} renamed classes matched to library classes (${known.size} kept their names)")
        return result
    }

    /** One merged jar (first copy of each class wins) dexed with D8; per-jar D8 if the merged run fails. */
    private fun dexReference(jars: List<Path>, minApi: Int, out: Path, timeoutMs: Long): List<DexFile>? {
        val merged = out.resolve("reference.jar")
        val seen = HashSet<String>()
        ZipOutputStream(Files.newOutputStream(merged)).use { zout ->
            for (jar in jars) ZipInputStream(Files.newInputStream(jar)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.name.endsWith(".class") || e.name.startsWith("META-INF/") || e.name.endsWith("module-info.class") || !seen.add(e.name)) continue
                    zout.putNextEntry(ZipEntry(e.name))
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
            }
        }
        fun d8(inputs: List<Path>, dir: Path): Boolean {
            Files.createDirectories(dir)
            val argv = listOf(profile.java.toString(), "-Xmx4g", "-cp", profile.d8Jar.toString(), "com.android.tools.r8.D8", "--release", "--min-api", minApi.coerceAtLeast(21).toString(),
                "--lib", profile.androidJar.toString(), "--output", dir.toString()) + inputs.map { it.toString() }
            val r = ProcessWorker.run(argv, dir, timeoutMs, maxLogBytes = 1 shl 20, cancel = cancel)
            if (r.cancelled) throw CancelledException()
            return r.ok
        }
        val dirs = if (d8(listOf(merged), out.resolve("merged"))) listOf(out.resolve("merged")) else jars.mapIndexedNotNull { k, j -> out.resolve("jar-$k").takeIf { d8(listOf(j), it) } }
        val files = dirs.flatMap { d -> Files.list(d).use { s -> s.filter { it.toString().endsWith(".dex") }.toList() } }
        return files.mapNotNull { (DexFile.parse(Files.readAllBytes(it)) as? Outcome.Ok)?.value }.ifEmpty { null }
    }
}
