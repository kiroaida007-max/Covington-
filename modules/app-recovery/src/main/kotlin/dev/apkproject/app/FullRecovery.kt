package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Budget
import dev.apkproject.contracts.ExitCode
import dev.apkproject.contracts.FormatException
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.ResolvedProfile
import dev.apkproject.dex.DexFile
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

data class FullRecoveryOptions(
    val deobfuscate: Boolean = false,
    val crossCheck: Boolean = true,
    val maxCrossCheckMethods: Int = 2_000,
    val compileCheck: Boolean = true,
    val rea: Boolean = true,
    val javaScript: Boolean = true,
    val threads: Int = maxOf(1, Runtime.getRuntime().availableProcessors()),
    val budget: Budget = Budget(randomInputsPerMethod = 16, compileTimeoutMs = 600_000, runtimeTimeoutMs = 600_000),
    val compileCheckTimeoutMs: Long = 1_200_000,
    /** Replace decompiled copies of verified libraries with Gradle dependencies. */
    val resolveLibraries: Boolean = true,
    val roundTrip: Boolean = true,
)

@Serializable
data class ManifestFacts(
    val packageName: String?,
    val versionName: String?,
    val versionCode: String?,
    val activities: Int,
    val services: Int,
    val receivers: Int,
    val providers: Int,
    val permissions: List<String>,
    val launcher: String?,
)

@Serializable
data class LayerSummary(val layer: String, val location: String, val producedBy: String, val evidence: String, val summary: String)

@Serializable
data class FullRecoveryReport(
    val schemaVersion: Int = 1,
    val engine: String,
    val input: String,
    val inputSha256: String,
    val inputKind: String,
    val profileId: String,
    val profileHash: String,
    val anatomy: AppAnatomy,
    val ownDexReader: String,
    val decompiler: DecompilerRun,
    val quality: DecompileQualitySummary,
    val crossCheck: CrossCheckCounts?,
    val removedArtifacts: Int,
    val libraries: List<ResolvedLibrary>,
    val librarySourcesRemoved: Int,
    val compileCheck: EcjResult,
    val roundTrip: RoundTripSummary,
    val manifest: ManifestFacts,
    val rea: ReaRun,
    val reaPackage: ReaPackageFacts?,
    val agreement: List<String>,
    val javaScript: List<JsRecovery>,
    val backend: BackendCounts,
    val native: NativeCounts,
    val layers: List<LayerSummary>,
    val limitations: List<String>,
    val elapsedMs: Long,
)

@Serializable
data class DecompileQualitySummary(val javaFiles: Int, val clean: Int, val withWarnings: Int, val withFailedMethods: Int, val appFiles: Int, val libraryFiles: Int, val appPackages: List<String>, val warningKinds: Map<String, Int>)

@Serializable
data class CrossCheckCounts(val eligible: Int, val checked: Int, val proved: Int, val cleaned: Int, val refutedReplaced: Int, val refuted: Int, val unknown: Int, val notLocated: Int, val skippedOverBudget: Int)

@Serializable
data class BackendCounts(val endpoints: Int, val baseUrls: Int, val hosts: Int, val urls: Int, val models: Int, val graphqlOperations: Int, val serviceConfig: Int, val skeletonCompiles: String)

@Serializable
data class NativeCounts(val libraries: Int, val javaNativeMethods: Int, val boundStatically: Int)

data class FullRecoveryResult(val exitCode: ExitCode, val outDir: Path?, val report: FullRecoveryReport?, val message: String)

/**
 * Whole-application recovery for any APK: every layer the package actually contains, each with an
 * explicit evidence level. The decompiler generates; the engine's own reader, validator and REA
 * provide the checks.
 */
class FullRecovery(
    private val profile: ResolvedProfile,
    private val options: FullRecoveryOptions = FullRecoveryOptions(),
    private val cancel: CancellationToken = CancellationToken(),
    private val log: (String) -> Unit = {},
) {
    fun run(inputPath: Path, outDir: Path): FullRecoveryResult {
        val started = System.nanoTime()
        if (Files.exists(outDir) && Files.list(outDir).use { it.findAny().isPresent }) {
            return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "output directory $outDir is not empty")
        }
        val jadx = JadxDecompiler(profile, cancel)
        if (!jadx.available()) return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "JADX is not installed; run scripts/setup-toolchain.sh --full and use --profile profiles/android-full.json")
        if (Hashing.sha256(profile.jadxJar!!) != profile.profile.jadx!!.sha256) return FullRecoveryResult(ExitCode.INVALID_CONFIGURATION, null, null, "JADX jar does not match the pinned SHA-256")

        val input = when (val o = AppInput.open(inputPath)) {
            is Outcome.Ok -> o.value
            is Outcome.Err -> return FullRecoveryResult(if (o.failure.unsupported) ExitCode.UNSUPPORTED_OR_PARTIAL else ExitCode.MALFORMED_INPUT, null, null, o.failure.toString())
        }
        log("input ${inputPath.fileName}: ${input.kind}, ${input.parts.size} part(s), sha256 ${input.sha256.take(12)}")
        // Our own DEX reader runs first; JADX is not trusted to establish what the bytecode is.
        val (dex, ownReader) = try {
            input.dexFiles() to "PASS"
        } catch (e: FormatException) {
            emptyList<Pair<String, DexFile>>() to "FAILED: ${e.failure} (decompilation continues; cross-check and native mapping need our reader)"
        }
        val anatomy = AnatomyDetector.detect(input, dex)
        log("anatomy: ${anatomy.frameworks.joinToString { it.name }}; ${anatomy.classCount} classes, ${anatomy.methodCount} methods, ${anatomy.nativeLibraries.size} native libraries")

        val parent = outDir.toAbsolutePath().parent
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".${outDir.fileName}.staging-")
        val work = Files.createTempDirectory(parent, ".${outDir.fileName}.work-")
        try {
            // 1. Front end and app logic: JADX full export (sources, resources, manifest, assets).
            log("decompiling with JADX ${profile.profile.jadx!!.version}...")
            val run = jadx.export(inputPath, staging, options.deobfuscate, options.threads, work)
            if (run.status == "FAILED") {
                return FullRecoveryResult(ExitCode.WORKER_OR_RESOURCE_FAILURE, null, null, "JADX failed (exit ${run.exitCode}); see ${work.resolve("jadx.log")}")
            }
            log("JADX ${run.status.lowercase()} in ${run.elapsedMs / 1000}s" + (run.reportedErrors?.let { ", $it decompilation errors reported" } ?: ""))
            checkCancel()
            val main = staging.resolve("app/src/main")
            val javaRoot = main.resolve("java")
            val manifestXml = main.resolve("AndroidManifest.xml").takeIf { it.isRegularFile() }?.let { Files.readString(it) }
            val manifest = manifestFacts(manifestXml)
            val appPackages = QualityScanner.appPackages(manifestXml)
            val recovery = Files.createDirectories(staging.resolve("recovery"))
            Files.copy(work.resolve("jadx.log"), recovery.resolve("jadx.log"), StandardCopyOption.REPLACE_EXISTING)

            // 2. REA evidence for the package (two independent readers of the same manifest).
            val hasManifest = input.parts.first().inventory.entry("AndroidManifest.xml") != null
            val (reaRun, reaFacts) = if (options.rea && input.kind == InputKind.APK && hasManifest) {
                log("collecting REA evidence...")
                ReaEvidence(profile, cancel).inspectPackage(inputPath, recovery.resolve("rea"), work)
            } else {
                ReaRun("inspect-android-package", "NOT_RUN", null, when {
                    !options.rea -> "disabled"
                    input.kind != InputKind.APK -> "REA inspects standalone APKs only (input is ${input.kind})"
                    else -> "the APK has no AndroidManifest.xml"
                }) to null
            }
            val agreement = agreement(manifest, reaFacts, anatomy)

            // 3. Remove toolchain artifacts, then replace verified libraries with real dependencies.
            val removedStubs = ArtifactCleaner.removePlatformStubs(javaRoot)
            if (removedStubs.isNotEmpty()) log("removed ${removedStubs.size} D8 platform stub classes (toolchain artifacts, not source)")
            Files.write(recovery.resolve("removed-artifacts.txt"), removedStubs)
            val resolution = if (options.resolveLibraries && dex.isNotEmpty()) {
                val candidates = anatomy.libraries + listOfNotNull(LibraryResolver.kotlinStdlibVersion(dex)).flatMap { k ->
                    val parts = k.version.split('.').mapNotNull { it.toIntOrNull() }
                    val old = parts.size >= 2 && (parts[0] < 1 || (parts[0] == 1 && parts[1] < 8))
                    listOf(k) + if (old) listOf(k.copy(coordinate = "org.jetbrains.kotlin:kotlin-stdlib-jdk7"), k.copy(coordinate = "org.jetbrains.kotlin:kotlin-stdlib-jdk8")) else emptyList()
                }
                log("resolving ${candidates.size} observed library identities against Google Maven / Maven Central...")
                LibraryResolver(profile).resolve(candidates, dex, work, log)
            } else {
                LibraryResolver.Resolution(emptyList(), emptyList(), emptySet(), emptySet())
            }
            val librarySourcesRemoved = LibraryApplier.removeSources(javaRoot, resolution)
            val toolchainClasses = ArtifactCleaner.removeUnreferencedToolchainClasses(javaRoot)
            if (toolchainClasses.isNotEmpty()) Files.write(recovery.resolve("removed-artifacts.txt"), removedStubs + toolchainClasses)
            LibraryApplier.addDependencies(staging.resolve("app/build.gradle"), resolution.libraries)
            if (librarySourcesRemoved.isNotEmpty()) log("replaced ${librarySourcesRemoved.size} decompiled library files with ${resolution.libraries.count { it.status == "VERIFIED" }} verified dependencies")
            Files.writeString(recovery.resolve("libraries.json"), Json.encode(resolution.libraries))

            // 4. Machine-check decompiled methods that lie inside the V0 semantic model.
            val quality = QualityScanner.scan(javaRoot, appPackages)
            val skip = resolution.classes + (removedStubs + toolchainClasses).map { it.removeSuffix(".java") }
            val recoveredDex = dex.map { (n, d) -> n to d }
            val cross = if (options.crossCheck && dex.isNotEmpty() && Files.isDirectory(javaRoot)) {
                log("cross-checking decompiled methods against the bytecode...")
                DecompilerCrossCheck(profile, options.budget, cancel, log).run(recoveredDex, javaRoot, work.resolve("crosscheck"), options.maxCrossCheckMethods, skip)
            } else {
                null
            }
            cross?.let { Files.writeString(recovery.resolve("cross-check.json"), Json.encode(it)) }
            cross?.let { log("cross-check: ${it.proved} proved (${it.cleaned} cleaned), ${it.refutedReplaced} refuted and replaced, ${it.refuted} refuted, ${it.unknown} unknown, ${it.notLocated} not located") }
            checkCancel()

            // 5. Compile check (ECJ) and round-trip verification of every recovered method.
            val ecj = EcjCompiler(profile, cancel)
            val classesDir = work.resolve("recompiled-classes")
            val (compile, diagnostics) = if (options.compileCheck && Files.isDirectory(javaRoot) && ecj.available() && profile.androidJar != null) {
                log("compiling recovered Java with ECJ against android.jar + verified libraries...")
                ecj.compile(javaRoot, resolution.jars, classesDir, work.resolve("ecj"), options.compileCheckTimeoutMs, appPackages)
            } else {
                EcjResult("NOT_RUN", "-", 0, 0, 0, 0, 0, emptyMap(), 0, if (!options.compileCheck) "disabled" else "ECJ or android.jar not installed (scripts/setup-toolchain.sh --full)", emptyList()) to emptyMap()
            }
            if (compile.status != "NOT_RUN") log("compile check: ${compile.files - compile.filesWithErrors}/${compile.files} files compile (${compile.appFiles - compile.appFilesWithErrors}/${compile.appFiles} app files)")
            Files.writeString(recovery.resolve("quality.json"), Json.encode(quality))
            Files.writeString(recovery.resolve("compile-diagnostics.json"), Json.encode(diagnostics.values.filter { it.errors > 0 }.sortedBy { it.path }))
            checkCancel()
            val minApi = staging.resolve("app/build.gradle").takeIf { it.isRegularFile() }?.let { Regex("""minSdkVersion\s+(\d+)""").find(Files.readString(it))?.groupValues?.get(1)?.toIntOrNull() } ?: 21
            val recoveredFiles = if (Files.isDirectory(javaRoot)) Files.walk(javaRoot).use { st -> st.filter { it.isRegularFile() && it.toString().endsWith(".java") }.map { javaRoot.relativize(it).toString().replace('\\', '/') }.toList() }.toSet() else emptySet()
            val (roundTrip, roundTripMethods) = if (options.roundTrip && compile.status != "NOT_RUN" && dex.isNotEmpty()) {
                log("round-trip verification: recompiled bytecode vs original, per method...")
                RoundTripVerifier(profile, cancel).verify(dex, classesDir, resolution.jars, diagnostics, recoveredFiles, appPackages, minApi, work.resolve("roundtrip"), options.compileCheckTimeoutMs)
            } else {
                RoundTripSummary("NOT_RUN", "disabled or no compiled classes", 0, 0, 0, 0, 0, 0, 0, 0, 0) to emptyList()
            }
            if (roundTrip.status == "MEASURED") log("round trip: ${roundTrip.equivalentDataflow}/${roundTrip.methods} methods equivalent dataflow (app: ${roundTrip.appEquivalentDataflow}/${roundTrip.appMethods}), ${roundTrip.similarOperations} similar, ${roundTrip.different} different, ${roundTrip.notCompiled} not compiled")
            Files.writeString(recovery.resolve("round-trip.json"), Json.encode(roundTripMethods))
            checkCancel()

            // 5. Backend: the client's view of its server.
            val contract = ApiContractExtractor.extract(javaRoot, main.resolve("res"), main.resolve("assets"), dex, appPackages)
            val backend = Files.createDirectories(staging.resolve("backend"))
            Files.writeString(backend.resolve("api-contract.json"), Json.encode(contract))
            Files.writeString(backend.resolve("openapi.yaml"), ContractRenderer.openApi(contract, manifest.packageName ?: inputPath.fileName.toString(), manifest.versionName ?: "unknown"))
            Files.writeString(backend.resolve("README.md"), ContractRenderer.readme(contract))
            val skeleton = Files.createDirectories(backend.resolve("server-skeleton")).resolve("BackendSkeleton.java")
            Files.writeString(skeleton, ContractRenderer.serverSkeleton(contract))
            val skeletonCheck = javacCheck(skeleton, work.resolve("skeleton"))
            log("backend contract: ${contract.endpoints.size} endpoints, ${contract.hosts.size} hosts, ${contract.models.size} models")

            // 6. Native layer and JavaScript.
            val native = if (dex.isNotEmpty()) NativeMapper.map(input, dex) else NativeLayer(emptyList(), emptyList(), emptyList(), emptyList(), listOf("our DEX reader did not accept the input; native mapping skipped"))
            val nativeDir = Files.createDirectories(staging.resolve("native"))
            Files.writeString(nativeDir.resolve("jni-map.json"), Json.encode(native))
            Files.writeString(nativeDir.resolve("README.md"), NativeMapper.readme(native))
            val js = if (options.javaScript) recoverJavaScript(input, anatomy, staging, recovery, work) else emptyList()

            // 7. Observed library versions, recorded beside the build file (dependencies are left untouched).
            appendLibraryNotes(staging.resolve("app/build.gradle"), anatomy.libraries)

            val layers = layers(anatomy, quality, cross, compile, roundTrip, resolution.libraries, contract, native, js)
            val report = FullRecoveryReport(
                engine = "apkproject 0.2.0",
                input = inputPath.fileName.toString(),
                inputSha256 = input.sha256,
                inputKind = input.kind.name,
                profileId = profile.profile.id,
                profileHash = profile.profileHash,
                anatomy = anatomy,
                ownDexReader = ownReader,
                decompiler = run,
                quality = DecompileQualitySummary(quality.javaFiles, quality.clean, quality.withWarnings, quality.withFailedMethods, quality.appFiles, quality.libraryFiles, quality.appPackages, quality.warningKinds),
                crossCheck = cross?.let { CrossCheckCounts(it.eligible, it.checked, it.proved, it.cleaned, it.refutedReplaced, it.refuted, it.unknown, it.notLocated, it.skippedOverBudget) },
                removedArtifacts = removedStubs.size + toolchainClasses.size,
                libraries = resolution.libraries,
                librarySourcesRemoved = librarySourcesRemoved.size,
                compileCheck = compile,
                roundTrip = roundTrip,
                manifest = manifest,
                rea = reaRun,
                reaPackage = reaFacts,
                agreement = agreement,
                javaScript = js,
                backend = BackendCounts(contract.endpoints.size, contract.baseUrls.size, contract.hosts.size, contract.urls.size, contract.models.size, contract.graphqlOperations.size, contract.serviceConfig.size, skeletonCheck),
                native = NativeCounts(native.libraries.size, native.javaNativeMethods.size, native.boundStatically.map { it.javaMethod }.distinct().size),
                layers = layers,
                limitations = limitations(anatomy),
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
            )
            Files.writeString(recovery.resolve("report.json"), Json.encode(report))
            Files.writeString(recovery.resolve("RECOVERY.md"), RecoveryMarkdown.render(report))
            Files.writeString(staging.resolve("README.md"), RecoveryMarkdown.projectReadme(report))
            val index = Files.walk(staging).use { s -> s.filter { it.isRegularFile() }.map { staging.relativize(it).toString().replace('\\', '/') }.sorted().toList() }
            Files.writeString(recovery.resolve("files.sha256"), index.joinToString("\n") { "${Hashing.sha256(staging.resolve(it))}  $it" } + "\n")
            if (Files.exists(outDir)) Files.delete(outDir)
            Files.move(staging, outDir, StandardCopyOption.ATOMIC_MOVE)
            val code = if (run.status == "COMPLETE") ExitCode.EXPORT_POLICY_SATISFIED else ExitCode.UNSUPPORTED_OR_PARTIAL
            return FullRecoveryResult(code, outDir, report, summaryLine(report))
        } catch (e: CancelledException) {
            return FullRecoveryResult(ExitCode.CANCELLED, null, null, "cancelled")
        } finally {
            AtomicFiles.deleteRecursively(staging)
            // APKPROJECT_KEEP_WORK=1 keeps intermediate files (recompiled classes and DEX) for diagnosis.
            if (System.getenv("APKPROJECT_KEEP_WORK") == "1") log("work directory kept: $work") else AtomicFiles.deleteRecursively(work)
        }
    }

    private fun checkCancel() {
        if (cancel.cancelled) throw CancelledException()
    }

    private fun javacCheck(file: Path, workDir: Path): String {
        Files.createDirectories(workDir)
        val r = ProcessWorker.run(listOf(profile.javac.toString(), "-d", workDir.toString(), file.toString()), workDir, 120_000, cancel = cancel)
        return if (r.ok) "PASS" else "FAIL: ${r.stderr.take(300)}"
    }

    private fun recoverJavaScript(input: AppInput, anatomy: AppAnatomy, staging: Path, recovery: Path, work: Path): List<JsRecovery> {
        if (anatomy.javaScriptBundles.isEmpty()) return emptyList()
        val rea = ReaEvidence(profile, cancel)
        return anatomy.javaScriptBundles.take(10).map { name ->
            val (part, entry) = input.allEntries().first { it.second.name == name }
            val file = Files.createDirectories(work.resolve("js")).resolve(name.replace('/', '_'))
            Files.write(file, part.read(entry))
            val label = name.substringAfterLast('/').removeSuffix(".js").removeSuffix(".bundle")
            log("recovering JavaScript $name with REA + Wakaru...")
            rea.recoverJavaScript(file, name, staging.resolve("web/recovered/$label"), recovery.resolve("rea"), work)
        }
    }

    private fun appendLibraryNotes(buildFile: Path, libs: List<LibraryVersion>) {
        if (!buildFile.isRegularFile() || libs.isEmpty()) return
        val note = buildString {
            append("\n// apkproject: library versions observed in the APK (META-INF). The decompiled copies of these\n")
            append("// libraries are in app/src/main/java; replacing them with these dependencies is a manual step.\n")
            libs.forEach { append("//   implementation '${it.coordinate}:${it.version}'\n") }
        }
        Files.writeString(buildFile, Files.readString(buildFile) + note)
    }

    companion object {
        fun manifestFacts(xml: String?): ManifestFacts {
            if (xml == null) return ManifestFacts(null, null, null, 0, 0, 0, 0, emptyList(), null)
            fun attr(name: String) = Regex("""\b$name="([^"]+)"""").find(xml)?.groupValues?.get(1)
            fun count(tag: String) = Regex("""<$tag\b""").findAll(xml).count()
            val launcher = Regex("""<activity(?:-alias)?\b[^>]*android:name="([^"]+)"[^>]*>(?:(?!</activity).)*?android\.intent\.category\.LAUNCHER""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
            return ManifestFacts(
                packageName = attr("package"),
                versionName = attr("android:versionName"),
                versionCode = attr("android:versionCode"),
                activities = count("activity") + count("activity-alias"),
                services = count("service"),
                receivers = count("receiver"),
                providers = count("provider"),
                permissions = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.distinct().toList(),
                launcher = launcher,
            )
        }

        /** Facts two independent readers must agree on. */
        fun agreement(m: ManifestFacts, rea: ReaPackageFacts?, anatomy: AppAnatomy): List<String> = buildList {
            if (rea == null) {
                add("REA evidence not available: manifest facts come from JADX only")
                return@buildList
            }
            add(if (rea.packageName == m.packageName) "AGREE package name ${m.packageName} (JADX manifest, REA)" else "DISAGREE package name: JADX ${m.packageName}, REA ${rea.packageName}")
            add(if (rea.versionName == m.versionName) "AGREE versionName ${m.versionName}" else "DISAGREE versionName: JADX ${m.versionName}, REA ${rea.versionName}")
            val jadxPerms = m.permissions.toSet()
            val reaPerms = rea.permissions.toSet()
            add(if (jadxPerms == reaPerms) "AGREE ${jadxPerms.size} permissions" else "DIFFER permissions: only JADX ${jadxPerms - reaPerms}, only REA ${reaPerms - jadxPerms}")
            rea.classCount?.let { add("CLASSES apkproject DEX reader ${anatomy.classCount}, REA/JADX engine $it") }
        }

        fun limitations(a: AppAnatomy): List<String> = buildList {
            add("Server-side (backend) code is not inside an APK. backend/ holds the client's view: endpoints, models and configuration the app reveals, plus a synthesized skeleton.")
            add("Decompiled Java is a derived representation. Only methods listed as PROVED in recovery/cross-check.json are machine-checked against the bytecode.")
            add("Kotlin sources are recovered as Java. Comments, original local names and formatting are not in the APK.")
            add("Obfuscated names (R8/ProGuard) stay obfuscated unless --deobfuscate renames them to readable placeholders; original names cannot be recovered without the mapping file.")
            for (f in a.frameworks.filter { it.recoverability == Recoverability.BINARY_ONLY }) add("${f.name}: ${f.note}")
        }

        fun summaryLine(r: FullRecoveryReport): String =
            "recovered ${r.quality.javaFiles} Java files (${r.quality.clean} clean), ${r.crossCheck?.proved ?: 0} methods proved, " +
                "${r.backend.endpoints} endpoints / ${r.backend.hosts} hosts, ${r.native.libraries} native libraries" +
                (if (r.compileCheck.status != "NOT_RUN") ", ${r.compileCheck.files - r.compileCheck.filesWithErrors}/${r.compileCheck.files} files compile" else "") +
                (if (r.roundTrip.status == "MEASURED") ", ${r.roundTrip.equivalentDataflow}/${r.roundTrip.methods} methods round-trip equivalent" else "")

        fun layers(a: AppAnatomy, q: DecompileQuality, cross: CrossCheckSummary?, compile: EcjResult, rt: RoundTripSummary, libs: List<ResolvedLibrary>, c: ApiContract, n: NativeLayer, js: List<JsRecovery>): List<LayerSummary> = buildList {
            add(LayerSummary("UI and resources", "app/src/main/res, app/src/main/AndroidManifest.xml", "JADX resource decoder", "DECODED", "layouts, strings, styles, drawables and manifest decoded from binary XML and resources.arsc"))
            if (a.webAssetRoots.isNotEmpty()) add(LayerSummary("Web front end", a.webAssetRoots.joinToString { "app/src/main/$it" }, "copied from the APK", "SOURCE AS SHIPPED", "HTML/CSS/JavaScript of the hybrid app"))
            add(LayerSummary("Application logic", "app/src/main/java", "JADX", "DECOMPILED" + (cross?.let { "; ${it.proved} methods PROVED" } ?: ""), "${q.javaFiles} files: ${q.clean} clean, ${q.withWarnings} with decompiler warnings, ${q.withFailedMethods} with failed methods; ${q.appFiles} in app packages"))
            val verified = libs.filter { it.status == "VERIFIED" }
            if (verified.isNotEmpty()) add(LayerSummary("Libraries", "app/build.gradle dependencies", "Google Maven / Maven Central artifacts", "VERIFIED IDENTITY", "${verified.size} libraries matched by class and method signatures; their decompiled copies were replaced by the real artifacts"))
            if (compile.status != "NOT_RUN") add(LayerSummary("Compile check", "recovery/compile-diagnostics.json", compile.compiler, compile.status, compile.note))
            if (rt.status == "MEASURED") add(LayerSummary("Round-trip verification", "recovery/round-trip.json", "ECJ + D8 + dataflow fingerprint", "ROUND-TRIP EQUIVALENT for ${rt.equivalentDataflow}/${rt.methods} methods", "app code: ${rt.appEquivalentDataflow}/${rt.appMethods}; ${rt.similarOperations} similar, ${rt.different} different, ${rt.notCompiled} not compiled"))
            js.forEach { add(LayerSummary("JavaScript", "web/recovered", "REA + Wakaru", if (it.status == "PASS") "DECOMPILED" else it.status, "${it.bundle}: ${it.modules} modules. ${it.detail}")) }
            add(LayerSummary("Native code", "native/", "ELF reader", "OBSERVED BOUNDARIES", "${n.libraries.size} libraries, ${n.javaNativeMethods.size} Java native methods, ${n.boundStatically.size} JNI bindings"))
            add(LayerSummary("Backend (client view)", "backend/", "DEX strings, decompiled code, resources", "OBSERVED + INFERRED; skeleton SYNTHESIZED", "${c.endpoints.size} endpoints, ${c.hosts.size} hosts, ${c.models.size} models, ${c.serviceConfig.size} service settings"))
        }
    }
}

object RecoveryMarkdown {
    fun render(r: FullRecoveryReport): String = buildString {
        append("# Recovery report: ${r.input}\n\n")
        append("Input sha256 `${r.inputSha256}` (${r.inputKind}); profile `${r.profileId}`; ${r.elapsedMs / 1000}s.\n\n")
        append("## What the app is made of\n\n| Runtime | Recoverability | Note |\n|---|---|---|\n")
        r.anatomy.frameworks.forEach { append("| ${it.name} | ${it.recoverability} | ${it.note} |\n") }
        append("\n${r.anatomy.classCount} classes and ${r.anatomy.methodCount} methods in ${r.anatomy.dexFiles.size} DEX file(s) (apkproject's own DEX reader: ${r.ownDexReader}).\n\n")
        append("## Layers\n\n| Layer | Where | Produced by | Evidence | Summary |\n|---|---|---|---|---|\n")
        r.layers.forEach { append("| ${it.layer} | `${it.location}` | ${it.producedBy} | ${it.evidence} | ${it.summary} |\n") }
        append("\n## Decompiler\n\nJADX ${r.decompiler.version}: ${r.decompiler.status}")
        r.decompiler.reportedErrors?.let { append(", $it errors reported") }
        append(". Most frequent decompiler warnings:\n\n")
        r.quality.warningKinds.entries.take(8).forEach { append("- ${it.value} × ${it.key}\n") }
        r.crossCheck?.let {
            append("\n## Machine-checked methods\n\n")
            append("${it.eligible} methods fall inside the V0 semantic model (static, int-only, no calls, loops or heap). ")
            append("${it.checked} were located in the decompiled source and checked: **${it.proved} proved** equal to the bytecode by Z3 and runtime tests ")
            append("(${it.cleaned} of them rewritten because the decompiler printed a literal as an unrelated constant), ")
            append("${it.refutedReplaced} refuted and replaced by a proved reconstruction, ${it.refuted} refuted, ${it.unknown} unknown; ${it.notLocated} not located.\n")
        }
        if (r.removedArtifacts > 0) append("\n${r.removedArtifacts} toolchain-generated classes were removed (D8 platform stubs that duplicate android.jar, unreferenced D8 desugaring helpers); they were never source. List: `recovery/removed-artifacts.txt`.\n")
        val verified = r.libraries.filter { it.status == "VERIFIED" }
        if (r.libraries.isNotEmpty()) {
            append("\n## Libraries\n\n${verified.size} of ${r.libraries.size} observed library identities verified against the published artifact; ${r.librarySourcesRemoved} decompiled library files replaced by dependencies.\n\n")
            append("| Library | Status | Evidence |\n|---|---|---|\n")
            r.libraries.forEach { append("| `${it.coordinate}` | ${it.status} | ${it.evidence} |\n") }
        }
        append("\n## Compile check\n\n${r.compileCheck.compiler}: ${r.compileCheck.status}. ${r.compileCheck.note}.\n")
        if (r.compileCheck.topErrors.isNotEmpty()) {
            append("\n| Compiler error | Count |\n|---|---|\n")
            r.compileCheck.topErrors.forEach { (k, v) -> append("| `${k.replace("|", "\\|")}` | $v |\n") }
        }
        if (r.roundTrip.status == "MEASURED") {
            val rt = r.roundTrip
            append("\n## Round-trip verification\n\nEvery method of the recovered classes was recompiled (ECJ, D8) and compared with the original bytecode by dataflow fingerprint ")
            append("(operations, constants, members, control flow and operand origins; register allocation and moves ignored).\n\n")
            append("| Result | Methods |\n|---|---|\n| Equivalent dataflow | ${rt.equivalentDataflow} |\n| Same operations, different operand flow | ${rt.similarOperations} |\n")
            append("| Different | ${rt.different} |\n| Not compiled (file has errors) | ${rt.notCompiled} |\n| Not found after recompilation | ${rt.notFound} |\n| **Total** | **${rt.methods}** |\n\n")
            append("App code only: ${rt.appEquivalentDataflow} of ${rt.appMethods} methods equivalent.\n")
        }
        append("\n## Manifest\n\n")
        r.manifest.let { append("Package `${it.packageName}`, version ${it.versionName} (${it.versionCode}); ${it.activities} activities, ${it.services} services, ${it.receivers} receivers, ${it.providers} providers, ${it.permissions.size} permissions; launcher `${it.launcher}`.\n\n") }
        append("REA: ${r.rea.status} (${r.rea.detail}).\n\n")
        r.agreement.forEach { append("- $it\n") }
        if (r.anatomy.libraries.isNotEmpty()) {
            append("\n## Libraries observed\n\n")
            r.anatomy.libraries.take(60).forEach { append("- `${it.coordinate}:${it.version}`\n") }
            if (r.anatomy.libraries.size > 60) append("- … ${r.anatomy.libraries.size - 60} more in report.json\n")
        }
        append("\n## Limitations\n\n")
        r.limitations.forEach { append("- $it\n") }
    }

    fun projectReadme(r: FullRecoveryReport): String = buildString {
        append("# ${r.manifest.packageName ?: r.input} (recovered)\n\n")
        append("Recovered by apkproject from `${r.input}` (sha256 `${r.inputSha256.take(16)}…`). Open this directory in Android Studio.\n\n")
        append("| Layer | Where | Evidence |\n|---|---|---|\n")
        r.layers.forEach { append("| ${it.layer} | `${it.location}` | ${it.evidence} |\n") }
        append("\nEvidence levels: SOURCE AS SHIPPED (original files), DECODED (exact data, reformatted), DECOMPILED (derived source; may be imperfect), ")
        append("PROVED (machine-checked equal to the bytecode), OBSERVED (read directly from the package), INFERRED (derived from code), SYNTHESIZED (generated scaffolding, not recovered).\n\n")
        append("Details: `recovery/RECOVERY.md`. The backend's own code is not in the APK; see `backend/README.md`.\n")
    }
}
