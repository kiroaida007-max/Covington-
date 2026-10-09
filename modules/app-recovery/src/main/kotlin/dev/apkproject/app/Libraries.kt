package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.InstructionDecoder
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.io.path.isRegularFile

/** Class name (internal form) and its method signatures, read from a .class file. */
data class ClassSignatures(val name: String, val methods: Set<String>)

/** Minimal, bounded JVM class-file reader: constant pool, this_class and method name/descriptor pairs. */
object ClassFileReader {
    fun read(bytes: ByteArray): ClassSignatures? {
        return try {
            parse(DataInputStream(ByteArrayInputStream(bytes)))
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun parse(in_: DataInputStream): ClassSignatures? {
        if (in_.readInt() != 0xCAFEBABE.toInt()) return null
        run {
            in_.readUnsignedShort(); in_.readUnsignedShort()
            val count = in_.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classIdx = IntArray(count)
            var i = 1
            while (i < count) {
                when (in_.readUnsignedByte()) {
                    1 -> utf8[i] = in_.readUTF()
                    7 -> classIdx[i] = in_.readUnsignedShort()
                    8, 16, 19, 20 -> in_.readUnsignedShort()
                    3, 4 -> in_.readInt()
                    5, 6 -> { in_.readLong(); i++ }
                    9, 10, 11, 12, 17, 18 -> in_.readInt()
                    15 -> { in_.readUnsignedByte(); in_.readUnsignedShort() }
                    else -> return null
                }
                i++
            }
            in_.readUnsignedShort()
            val name = utf8[classIdx[in_.readUnsignedShort()]] ?: return null
            in_.readUnsignedShort()
            repeat(in_.readUnsignedShort()) { in_.readUnsignedShort() }
            fun skipMembers(collect: MutableSet<String>?) {
                repeat(in_.readUnsignedShort()) {
                    in_.readUnsignedShort()
                    val n = utf8[in_.readUnsignedShort()]
                    val d = utf8[in_.readUnsignedShort()]
                    if (collect != null && n != null && d != null) collect += n + d
                    repeat(in_.readUnsignedShort()) {
                        in_.readUnsignedShort()
                        in_.skipBytes(in_.readInt())
                    }
                }
            }
            skipMembers(null)
            val methods = HashSet<String>()
            skipMembers(methods)
            return ClassSignatures(name, methods)
        }
    }
}

/** Fetches and caches Maven artifacts, verifying the repository's published SHA-1. */
class MavenRepository(private val cache: Path, private val repositories: List<String>) {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()

    data class Artifact(val coordinate: String, val file: Path, val sha256: String, val repository: String, val packaging: String)

    fun fetch(coordinate: String): Artifact? {
        val (g, a, v) = coordinate.split(':').takeIf { it.size == 3 } ?: return null
        val dir = Files.createDirectories(cache.resolve(g).resolve(a).resolve(v))
        for (ext in listOf("aar", "jar")) {
            val file = dir.resolve("$a-$v.$ext")
            if (Files.isRegularFile(file) && Files.isRegularFile(dir.resolve("$a-$v.$ext.verified"))) {
                return Artifact(coordinate, file, Hashing.sha256(file), Files.readString(dir.resolve("$a-$v.$ext.verified")).trim(), ext)
            }
        }
        val ordered = repositories.sortedBy { r -> if (g.startsWith("androidx") || g.startsWith("com.google.android") || g.startsWith("com.google.firebase") || g.startsWith("com.android")) (if ("dl.google.com" in r) 0 else 1) else (if ("dl.google.com" in r) 1 else 0) }
        for (repo in ordered) for (ext in listOf("aar", "jar")) {
            val base = repo.trimEnd('/') + "/" + g.replace('.', '/') + "/$a/$v/$a-$v.$ext"
            val bytes = get(base) ?: continue
            val sha1 = get("$base.sha1")?.let { String(it).trim().substringBefore(' ') } ?: continue
            val actual = Hashing.hex(MessageDigest.getInstance("SHA-1").digest(bytes))
            if (!actual.equals(sha1, ignoreCase = true)) continue
            val file = dir.resolve("$a-$v.$ext")
            val tmp = dir.resolve("$a-$v.$ext.part")
            Files.write(tmp, bytes)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(dir.resolve("$a-$v.$ext.verified"), repo)
            return Artifact(coordinate, file, Hashing.sha256(bytes), repo, ext)
        }
        return null
    }

    /**
     * Runtime dependencies declared in the artifact's POM (sha1-verified), with `${...}` properties
     * defined in the same POM substituted. Test, provided, system and optional dependencies are skipped.
     */
    fun pomDependencies(coordinate: String): List<String> {
        val (g, a, v) = coordinate.split(':').takeIf { it.size == 3 } ?: return emptyList()
        val dir = Files.createDirectories(cache.resolve(g).resolve(a).resolve(v))
        val file = dir.resolve("$a-$v.pom")
        val text = if (Files.isRegularFile(file)) {
            Files.readString(file)
        } else {
            val bytes = repositories.firstNotNullOfOrNull { repo ->
                val url = repo.trimEnd('/') + "/" + g.replace('.', '/') + "/$a/$v/$a-$v.pom"
                val body = get(url) ?: return@firstNotNullOfOrNull null
                val sha1 = get("$url.sha1")?.let { String(it).trim().substringBefore(' ') } ?: return@firstNotNullOfOrNull null
                body.takeIf { Hashing.hex(MessageDigest.getInstance("SHA-1").digest(it)).equals(sha1, ignoreCase = true) }
            } ?: return emptyList()
            Files.write(file, bytes)
            String(bytes, Charsets.UTF_8)
        }
        return parsePom(text, v)
    }

    /** Published versions of `group:artifact` from maven-metadata.xml (first repository that has it). */
    fun versions(ga: String): List<String> {
        val (g, a) = ga.split(':').takeIf { it.size == 2 } ?: return emptyList()
        val dir = Files.createDirectories(cache.resolve(g).resolve(a))
        val file = dir.resolve("maven-metadata.xml")
        val text = if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() > System.currentTimeMillis() - 7L * 24 * 3600 * 1000) {
            Files.readString(file)
        } else {
            val ordered = repositories.sortedBy { r -> if (g.startsWith("androidx") || g.startsWith("com.google.android") || g.startsWith("com.google.firebase")) (if ("dl.google.com" in r) 0 else 1) else (if ("dl.google.com" in r) 1 else 0) }
            val bytes = ordered.firstNotNullOfOrNull { repo -> get(repo.trimEnd('/') + "/" + g.replace('.', '/') + "/$a/maven-metadata.xml") } ?: return emptyList()
            Files.write(file, bytes)
            String(bytes, Charsets.UTF_8)
        }
        return Regex("""<version>([^<]+)</version>""").findAll(text).map { it.groupValues[1].trim() }.distinct().toList()
    }

    companion object {
        fun parsePom(text: String, ownVersion: String): List<String> {
            val noComments = text.replace(Regex("""<!--[\s\S]*?-->"""), "")
            val props = HashMap<String, String>()
            props["project.version"] = ownVersion
            props["version"] = ownVersion
            Regex("""<properties>([\s\S]*?)</properties>""").find(noComments)?.groupValues?.get(1)?.let { block ->
                Regex("""<([\w.-]+)>([^<]*)</\1>""").findAll(block).forEach { props[it.groupValues[1]] = it.groupValues[2].trim() }
            }
            fun resolve(s: String) = Regex("""\$\{([^}]+)}""").replace(s) { props[it.groupValues[1]] ?: it.value }
            // Only <dependencies> outside <dependencyManagement> are real dependencies.
            val body = noComments.replace(Regex("""<dependencyManagement>[\s\S]*?</dependencyManagement>"""), "")
            val deps = Regex("""<dependencies>([\s\S]*?)</dependencies>""").findAll(body).flatMap { b -> Regex("""<dependency>([\s\S]*?)</dependency>""").findAll(b.groupValues[1]) }
            return deps.mapNotNull { d ->
                fun tag(t: String) = Regex("""<$t>\s*([^<]*?)\s*</$t>""").find(d.groupValues[1])?.groupValues?.get(1)?.let(::resolve)
                val scope = tag("scope") ?: "compile"
                if (scope !in setOf("compile", "runtime") || tag("optional") == "true") return@mapNotNull null
                val version = tag("version")?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() && '$' !in it && ',' !in it } ?: return@mapNotNull null
                "${tag("groupId") ?: return@mapNotNull null}:${tag("artifactId") ?: return@mapNotNull null}:$version"
            }.distinct().toList()
        }
    }

    private fun get(url: String): ByteArray? = try {
        var attempt = 0
        var result: ByteArray? = null
        while (attempt < 3) {
            val resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() == 200) { result = resp.body(); break }
            if (resp.statusCode() != 429 && resp.statusCode() < 500) break
            Thread.sleep(1000L shl attempt)
            attempt++
        }
        result
    } catch (e: Exception) {
        null
    }
}

@Serializable
data class ResolvedLibrary(
    val coordinate: String,
    /** VERIFIED: class and method signatures match the artifact; REJECTED: they do not; UNAVAILABLE: not downloadable. */
    val status: String,
    val artifactSha256: String?,
    val repository: String?,
    val artifactClasses: Int,
    val classesInApk: Int,
    val methodsChecked: Int,
    val methodsMatching: Int,
    val evidence: String,
)

/**
 * Library identities (dossier section 17: only proven library identities become external
 * dependencies). A candidate coordinate observed in the APK is accepted only when the genuine
 * artifact's classes are present in the DEX and their method signatures agree.
 */
class LibraryResolver(private val profile: ResolvedProfile) {
    data class Resolution(val libraries: List<ResolvedLibrary>, val jars: List<Path>, val classes: Set<String>, val androidPackages: Set<String>)

    fun resolve(candidates: List<LibraryVersion>, dex: List<Pair<String, DexFile>>, workDir: Path, log: (String) -> Unit): Resolution {
        val cache = profile.libraryCache ?: return Resolution(emptyList(), emptyList(), emptySet(), emptySet())
        val repos = profile.profile.libraries?.repositories ?: emptyList()
        val maven = MavenRepository(cache, repos)
        val apk = HashMap<String, Set<String>>()
        for ((_, d) in dex) for (c in d.classDefs) {
            val name = d.type(c.classIdx).removePrefix("L").removeSuffix(";")
            val methods = HashSet<String>()
            c.classData?.let { data -> (data.directMethods + data.virtualMethods).forEach { m -> methods += d.string(d.methodIds[m.methodIdx].nameIdx) + d.protoDescriptor(d.methodIds[m.methodIdx].protoIdx) } }
            apk[name] = methods
        }
        val libs = ArrayList<ResolvedLibrary>()
        val jars = ArrayList<Path>()
        val classes = HashSet<String>()
        val androidPackages = HashSet<String>()
        val jarDir = Files.createDirectories(workDir.resolve("library-jars"))
        // Observed identities first; dependencies declared by verified libraries' POMs are added as they verify.
        val queue = ArrayDeque(candidates.distinctBy { it.coordinate }.map { it to false })
        val seen = HashSet(candidates.map { it.coordinate })
        while (queue.isNotEmpty() && libs.size < 400) {
            val (c, transitive) = queue.removeFirst()
            val coordinate = "${c.coordinate}:${c.version}"
            if (c.coordinate.count { it == ':' } != 1) continue
            val first = evaluate(maven, c.coordinate, c.version, apk, jarDir)
            if (first == null) {
                if (!transitive) libs += ResolvedLibrary(coordinate, "UNAVAILABLE", null, null, 0, 0, 0, 0, "not found in ${repos.size} repositories")
                continue
            }
            if (transitive && first.present.isEmpty()) continue // declared dependency that the build did not package
            var e: Evaluation = first
            var versionNote = ""
            if (!e.verified && e.present.isNotEmpty() && e.agreement >= 0.80) {
                // Gradle resolves version conflicts upwards, so the packaged version can be newer than the
                // one a POM or version file names. Newer published versions are tried, nearest first.
                for (v in maven.versions(c.coordinate).filter { compareVersions(it, c.version) > 0 && !isPreRelease(it) }.sortedWith(::compareVersions).take(12)) {
                    val alt = evaluate(maven, c.coordinate, v, apk, jarDir) ?: continue
                    if (alt.verified) {
                        e = alt; versionNote = "; ${c.version} named by ${c.evidence.substringBefore(';')} disagreed, the APK matches $v"
                        break
                    }
                }
            }
            val verified = e.verified
            val artifactJars = e.jars
            val present = e.present
            val resolvedCoordinate = "${c.coordinate}:${e.version}"
            libs += ResolvedLibrary(
                resolvedCoordinate, if (verified) "VERIFIED" else "REJECTED", e.artifact.sha256, e.artifact.repository, e.sigs.size, present.size, e.checked, e.matching,
                "${present.size}/${e.sigs.size} artifact classes in the APK; ${"%.1f".format(e.agreement * 100)}% of their APK method signatures in the artifact${e.variant}$versionNote" +
                    if (transitive) "; identity from ${c.evidence}" else "",
            )
            log("library $resolvedCoordinate: ${if (verified) "verified" else "rejected"} (${present.size} classes, ${"%.1f".format(e.agreement * 100)}% method agreement)")
            val manifestPackage = e.manifestPackage
            if (verified) {
                jars.addAll(artifactJars)
                classes += present.map { it.name }
                manifestPackage?.let { androidPackages += it }
                for (dep in maven.pomDependencies(resolvedCoordinate)) {
                    val ga = dep.substringBeforeLast(':')
                    if (seen.add(ga)) queue.addLast(LibraryVersion(ga, dep.substringAfterLast(':'), "POM of $resolvedCoordinate") to true)
                }
            }
        }
        return Resolution(libs, jars, classes, androidPackages)
    }

    private class Evaluation(
        val version: String,
        val artifact: MavenRepository.Artifact,
        val jars: List<Path>,
        val manifestPackage: String?,
        val sigs: List<ClassSignatures>,
        val present: List<ClassSignatures>,
        val checked: Int,
        val matching: Int,
        val variant: String,
    ) {
        val agreement: Double get() = if (checked == 0) 0.0 else matching.toDouble() / checked
        val verified: Boolean get() = present.isNotEmpty() && agreement >= 0.98 && (present.size >= 3 || present.size == sigs.size)
    }

    /** Fetches one version and measures how well its classes agree with the APK's. */
    private fun evaluate(maven: MavenRepository, ga: String, version: String, apk: Map<String, Set<String>>, jarDir: Path): Evaluation? {
        val artifact = maven.fetch("$ga:$version") ?: return null
        var (artifactJars, manifestPackage) = classJars(artifact, jarDir)
        var sigs = artifactJars.flatMap { readClasses(it) }
        var present = sigs.filter { it.name in apk }
        var variant = ""
        if (present.isEmpty()) {
            // Kotlin Multiplatform libraries publish the JVM/Android classes in a platform artifact;
            // Gradle picks it from module metadata, so the declared dependency stays the root coordinate.
            for (suffix in listOf("-jvm", "-android")) {
                val alt = maven.fetch("$ga$suffix:$version") ?: continue
                val (altJars, altPkg) = classJars(alt, jarDir)
                val altSigs = altJars.flatMap { readClasses(it) }
                if (altSigs.none { it.name in apk }) continue
                artifactJars = altJars; manifestPackage = altPkg; sigs = altSigs; present = altSigs.filter { it.name in apk }
                variant = " (classes from the ${ga.substringAfter(':')}$suffix platform artifact)"
                break
            }
        }
        var checked = 0
        var matching = 0
        for (s in present) {
            val inApk = apk.getValue(s.name).filterNot { synthetic(it) }
            checked += inApk.size
            matching += inApk.count { it in s.methods }
        }
        return Evaluation(version, artifact, artifactJars, manifestPackage, sigs, present, checked, matching, variant)
    }

    /** Ignore methods D8/R8 or kotlinc synthesize differently between builds. */
    private fun synthetic(sig: String): Boolean {
        val name = sig.substringBefore('(')
        return '$' in name || name.startsWith("lambda") || name.startsWith("access") || name == "<clinit>"
    }

    private fun classJars(a: MavenRepository.Artifact, jarDir: Path): Pair<List<Path>, String?> {
        if (a.packaging == "jar") return listOf(a.file) to null
        val out = ArrayList<Path>()
        var pkg: String? = null
        ZipInputStream(Files.newInputStream(a.file)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "classes.jar" || (e.name.startsWith("libs/") && e.name.endsWith(".jar")) -> {
                        val target = jarDir.resolve(a.coordinate.replace(':', '_') + "-" + e.name.replace('/', '_'))
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                        out.add(target)
                    }
                    e.name == "AndroidManifest.xml" -> pkg = Regex("""package="([^"]+)"""").find(String(zip.readAllBytes()))?.groupValues?.get(1)
                }
            }
        }
        return out to pkg
    }

    private fun readClasses(jar: Path): List<ClassSignatures> {
        val out = ArrayList<ClassSignatures>()
        ZipInputStream(Files.newInputStream(jar)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                ClassFileReader.read(zip.readAllBytes())?.let { out += it }
            }
        }
        return out
    }

    companion object {
        /** Numeric-aware version order: 1.10.0 > 1.9.2, 1.6.1 > 1.6.0-rc01 is not decided here (pre-releases are skipped). */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-'); val pb = b.split('.', '-')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i); val y = pb.getOrNull(i)
                if (x == null) return -1
                if (y == null) return 1
                val nx = x.toIntOrNull(); val ny = y.toIntOrNull()
                val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
                if (c != 0) return c
            }
            return 0
        }

        fun isPreRelease(v: String) = Regex("""(?i)(alpha|beta|rc|dev|eap|snapshot|m\d)""").containsMatchIn(v)

        /** kotlin-stdlib carries no version file; its version is the constant built into kotlin.KotlinVersion.CURRENT. */
        fun kotlinStdlibVersion(dex: List<Pair<String, DexFile>>): LibraryVersion? {
            // Before Kotlin 1.9.20 the constant is built in KotlinVersion.<clinit>; later in KotlinVersionCurrentValue.get().
            val holders = setOf("Lkotlin/KotlinVersion;-><clinit>", "Lkotlin/KotlinVersionCurrentValue;->get")
            for ((name, d) in dex) for (m in d.methods().filter { "${it.owner}->${it.name}" in holders }) {
                val decoded = (InstructionDecoder.decode(d, m.code ?: continue) as? Outcome.Ok)?.value ?: continue
                val consts = HashMap<Int, Long>()
                for (i in decoded.insns) {
                    if (i.op.mnemonic.startsWith("const") && !i.op.mnemonic.startsWith("const-")) consts[i.regs[0]] = i.literal
                    if (i.op.mnemonic.startsWith("invoke-direct") && d.methodDescriptor(i.index) == "Lkotlin/KotlinVersion;-><init>(III)V") {
                        val v = i.regs.drop(1).map { consts[it] ?: return@map null }
                        if (v.all { it != null }) return LibraryVersion("org.jetbrains.kotlin:kotlin-stdlib", v.joinToString("."), "$name: kotlin.KotlinVersion.CURRENT")
                    }
                }
            }
            return null
        }
    }
}

/** Applies a verified resolution: removes the library's decompiled copies and records the dependencies. */
object LibraryApplier {
    fun removeSources(javaRoot: Path, r: LibraryResolver.Resolution): List<String> {
        val removed = ArrayList<String>()
        if (!Files.isDirectory(javaRoot)) return removed
        // A file belongs to a verified library when its outermost class does: this covers inner and
        // anonymous classes the decompiler emitted as separate files, and D8 synthetic lambdas.
        val owners = r.classes.map { it.substringBefore('$') }.toSet()
        Files.walk(javaRoot).use { s -> s.filter { it.isRegularFile() && it.toString().endsWith(".java") }.toList() }.forEach { f ->
            val rel = javaRoot.relativize(f).toString().replace('\\', '/')
            if (rel.removeSuffix(".java").substringBefore('$') in owners) {
                Files.delete(f); removed += rel
            }
        }
        // Library R classes are regenerated by the Android build from the dependency's resources.
        for (pkg in r.androidPackages) {
            val f = javaRoot.resolve(pkg.replace('.', '/') + "/R.java")
            if (Files.isRegularFile(f)) {
                Files.delete(f); removed += pkg.replace('.', '/') + "/R.java"
            }
        }
        return removed.sorted()
    }

    fun addDependencies(buildFile: Path, libs: List<ResolvedLibrary>) {
        if (!Files.isRegularFile(buildFile)) return
        val verified = libs.filter { it.status == "VERIFIED" }
        if (verified.isEmpty()) return
        val deps = verified.joinToString("\n") { "    implementation '${it.coordinate}' // verified by apkproject: ${it.evidence}" }
        var text = Files.readString(buildFile)
        text = if (Regex("""(?m)^dependencies\s*\{""").containsMatchIn(text)) {
            text.replaceFirst(Regex("""(?m)^dependencies\s*\{"""), "dependencies {\n$deps")
        } else {
            "$text\ndependencies {\n$deps\n}\n"
        }
        Files.writeString(buildFile, text)
    }
}
