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
    /** For obfuscated apps, decompile R8-renamed library classes under the names of the library classes they match. */
    val nameLibraries: Boolean = true,
    /** Try verified edits on methods that do not round-trip (see [SourceRepair]). */
    val repair: Boolean = true,
    val repairBudgetMs: Long = 600_000,
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
    val repair: RepairSummary,
    val repairs: List<RepairRecord>,
    val manifest: ManifestFacts,
    /** D8/R8 stamps found in the input's DEX files: which compiler, version, mode and min-api built it. */
    val originalToolchain: List<CompilerMarker> = emptyList(),
    /** R8-renamed classes decompiled under the name of the library class they matched (INFERRED). */
    val libraryClassesNamed: Int = 0,
    /** The app's front end: UI toolkit, screens, and a static UI-quality + security review. */
    val frontend: FrontendReport? = null,
    /** Classes, fields and methods given back their original names from compiler-written evidence. */
    val evidenceNames: List<Int> = emptyList(),
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
            // Obfuscated names that Java cannot express (a package obscured by a class or field of the
            // same name) are renamed through a mapping; the round trip maps them back.
            val libraryMatch = if (!options.deobfuscate && options.nameLibraries && dex.isNotEmpty()) {
                val obf = ObfuscatedLibraries(profile, cancel, log)
                if (obf.obfuscated(dex) && anatomy.libraries.isNotEmpty()) {
                    val minApi = CompilerMarker.read(dex).mapNotNull { it.minApi }.minOrNull() ?: 21
                    runCatching { obf.recognise(dex, anatomy.libraries, minApi, work.resolve("library-naming")) }.onFailure { if (it is CancelledException) throw it; log("library naming skipped: ${it.message}") }.getOrNull()
                } else null
            } else null
            // Names the compilers wrote into the bytecode (data-class toString, Moshi, ViewBinding, Room, enums).
            val evidence = if (options.deobfuscate || !options.nameLibraries || dex.isEmpty()) NameOracle.Result.EMPTY else {
                val resources = input.parts.firstNotNullOfOrNull { it.read("resources.arsc") }?.let(ResourceTable::parse) ?: ResourceTable.EMPTY
                NameOracle.recover(dex.map { it.second }, resources)
            }
            val names = if (options.deobfuscate || dex.isEmpty()) NameMapping.EMPTY else NameMapping.compute(dex, libraryMatch, evidence)
            val mappingFile = if (names.isEmpty) null else work.resolve("names.tiny").also { names.writeTiny(it, dex) }
            if (evidence.all.isNotEmpty()) log("original names from compiler evidence: ${names.evidenceClasses} classes, ${names.evidenceFields} fields, ${names.evidenceMethods} methods" + (if (names.libraryMatchesVetoed > 0) "; ${names.libraryMatchesVetoed} library matches dropped (contradicted by evidence, a kept name, or a synthetic lambda)" else ""))
            if (!names.isEmpty) log("names: ${names.libraryMatches} renamed library classes get their library names; ${names.classes.size} classes and ${names.fields.values.sumOf { it.size }} fields renamed in all (library names, recovered names and Java name-obscuring fixes)")
            val run = jadx.export(inputPath, staging, options.deobfuscate, options.threads, work, mappingFile)
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
                DecompilerCrossCheck(profile, options.budget, cancel, log).run(recoveredDex, javaRoot, work.resolve("crosscheck"), options.maxCrossCheckMethods, skip, names)
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
            Files.writeString(recovery.resolve("name-mapping.json"), Json.encode(names))
            if (evidence.all.isNotEmpty()) Files.writeString(recovery.resolve("recovered-names.json"), Json.encode(evidence.all))
            libraryMatch?.let { Files.writeString(recovery.resolve("library-matches.json"), Json.encode(it.classes)) }
            val verifier = RoundTripVerifier(profile, cancel)
            verifier.names = names
            verifier.renames = JadxRenames.parse(javaRoot).also { if (it.size > 0) log("JADX renamed ${it.size} members to make valid Java; the round trip maps them back") }
            var repairSummary = RepairSummary("NOT_RUN", "disabled or no round trip", 0, 0, 0, 0, 0)
            var repairs = emptyList<RepairRecord>()
            val (roundTrip, roundTripMethods) = if (options.roundTrip && compile.status != "NOT_RUN" && dex.isNotEmpty()) {
                val clean = recoveredFiles.filter { (diagnostics[it]?.errors ?: 1) == 0 }
                // Files whose only errors are checked-exception rules (decompiled Kotlin) compile with those relaxed.
                val relaxable = recoveredFiles.filter { diagnostics[it]?.onlyCheckedExceptionErrors == true }
                log("recompiling ${clean.size} error-free files (+${relaxable.size} with only checked-exception errors) with javac for a like-for-like round trip...")
                val overlay = JavacOverlay(profile, cancel).apply(javaRoot, clean, classesDir, resolution.jars, work.resolve("javac"), options.compileCheckTimeoutMs, relaxable)
                if (overlay.javacFiles.isEmpty()) log("javac overlay skipped: ${overlay.note}")
                else log("javac accepted ${overlay.javacFiles.size}/${clean.size + relaxable.size} files (${overlay.relaxedFiles.size} with checked-exception rules relaxed; ${overlay.rejected.size} kept ECJ output)")
                Files.write(recovery.resolve("javac-rejected.txt"), overlay.rejected)
                Files.write(recovery.resolve("javac-relaxed.txt"), overlay.relaxedFiles)
                // Relaxed files now have javac classes for every method: verify and repair them like error-free files.
                val effective = diagnostics - overlay.relaxedFiles.toSet()
                log("round-trip verification: recompiled bytecode vs original, per method...")
                var (sum, methods) = verifier.verify(dex, classesDir, resolution.jars, effective, recoveredFiles, appPackages, minApi, work.resolve("roundtrip"), options.compileCheckTimeoutMs)
                if (options.repair && sum.status == "MEASURED" && methods.any { it.result == "DIFFERENT" || it.result == "SIMILAR_OPERATIONS" }) {
                    log("verified repair: trying edits on methods that do not round-trip...")
                    val (rs, records, updated) = SourceRepair(profile, verifier, cancel, log).run(
                        javaRoot, classesDir, resolution.jars, verifier.originalByClass(dex, recoveredFiles), methods, effective, minApi, work.resolve("repair"), options.repairBudgetMs,
                    )
                    repairSummary = rs
                    repairs = records
                    if (records.isNotEmpty()) {
                        methods = updated
                        sum = RoundTripVerifier.summarize(updated, appPackages, sum.note + "; after ${records.size} verified repairs", sum.elapsedMs + rs.elapsedMs)
                    }
                    log("verified repair: ${rs.accepted} edits kept of ${rs.candidatesTried} tried, ${rs.methodsFixed} methods fixed (${rs.note})")
                }
                sum.copy(compiledWithJavac = overlay.javacFiles.size, compiledWithRelaxedExceptions = overlay.relaxedFiles.size, compiledWithEcjOnly = recoveredFiles.size - overlay.javacFiles.size) to methods
            } else {
                RoundTripSummary("NOT_RUN", "disabled or no compiled classes", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) to emptyList()
            }
            Files.writeString(recovery.resolve("repairs.json"), Json.encode(repairs))
            if (roundTrip.status == "MEASURED") log("round trip: ${roundTrip.equivalent}/${roundTrip.methods} methods equivalent (${roundTrip.equivalentDataflow} identical dataflow, ${roundTrip.equivalentModuloLayout} modulo layout; app: ${roundTrip.appEquivalent}/${roundTrip.appMethods}), ${roundTrip.similarOperations} same operations reordered, ${roundTrip.different} different, ${roundTrip.notCompiled} not compiled; ${roundTrip.syntheticNotCompared} compiler-generated not compared")
            Files.writeString(recovery.resolve("round-trip.json"), Json.encode(roundTripMethods))
            checkCancel()

            // 5. Backend: the client's view of its server.
            val contract = ApiContractExtractor.extract(javaRoot, main.resolve("res"), main.resolve("assets"), dex, appPackages).renamedTypes(names.classes)
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

            // 8. Front end: UI toolkit, screens, and a static UI-quality + security review with a risk score.
            val frontend = FrontendAudit.analyze(main, manifestXml, dex, anatomy, input.allEntries().map { it.second.name })
            val auditDir = Files.createDirectories(staging.resolve("audit"))
            Files.writeString(auditDir.resolve("ui-audit.json"), Json.encode(frontend))
            Files.writeString(auditDir.resolve("README.md"), FrontendMarkdown.render(frontend))
            log("front end: ${frontend.toolkit.name}; ${frontend.screens.count { it.kind.startsWith("ACTIVITY") }} activities; risk ${frontend.risk.score}/100 (grade ${frontend.risk.grade}), ${frontend.risk.critical} critical / ${frontend.risk.high} high / ${frontend.risk.medium} medium findings")

            val layers = layers(anatomy, quality, cross, compile, roundTrip, repairSummary, resolution.libraries, contract, native, js, frontend)
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
                repair = repairSummary,
                repairs = repairs,
                manifest = manifest,
                originalToolchain = CompilerMarker.read(dex),
                libraryClassesNamed = names.libraryMatches,
                frontend = frontend,
                evidenceNames = listOf(names.evidenceClasses, names.evidenceFields, names.evidenceMethods),
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
            add("Obfuscated app names (R8/ProGuard) cannot generally be recovered without the original mapping. Library names may be inferred from declared artifacts; inferred names are not verified identities.")
            for (f in a.frameworks.filter { it.recoverability == Recoverability.BINARY_ONLY }) add("${f.name}: ${f.note}")
        }

        fun summaryLine(r: FullRecoveryReport): String =
            "recovered ${r.quality.javaFiles} Java files (${r.quality.clean} clean), ${r.crossCheck?.proved ?: 0} methods proved, " +
                "${r.backend.endpoints} endpoints / ${r.backend.hosts} hosts, ${r.native.libraries} native libraries" +
                (if (r.compileCheck.status != "NOT_RUN") ", ${r.compileCheck.files - r.compileCheck.filesWithErrors}/${r.compileCheck.files} files compile" else "") +
                (if (r.roundTrip.status == "MEASURED") ", ${r.roundTrip.equivalent}/${r.roundTrip.methods} methods round-trip equivalent" else "")

        fun layers(a: AppAnatomy, q: DecompileQuality, cross: CrossCheckSummary?, compile: EcjResult, rt: RoundTripSummary, repair: RepairSummary, libs: List<ResolvedLibrary>, c: ApiContract, n: NativeLayer, js: List<JsRecovery>, frontend: FrontendReport?): List<LayerSummary> = buildList {
            add(LayerSummary("UI and resources", "app/src/main/res, app/src/main/AndroidManifest.xml", "JADX resource decoder", "DECODED", "layouts, strings, styles, drawables and manifest decoded from binary XML and resources.arsc"))
            frontend?.let { f -> add(LayerSummary("Front-end audit", "audit/ui-audit.json", "static UI + security review", "RISK ${f.risk.score}/100 (grade ${f.risk.grade})", "${f.toolkit.name}; ${f.screens.count { it.kind.startsWith("ACTIVITY") }} activities; ${f.risk.critical} critical / ${f.risk.high} high / ${f.risk.medium} medium findings")) }
            if (a.webAssetRoots.isNotEmpty()) add(LayerSummary("Web front end", a.webAssetRoots.joinToString { "app/src/main/$it" }, "copied from the APK", "SOURCE AS SHIPPED", "HTML/CSS/JavaScript of the hybrid app"))
            add(LayerSummary("Application logic", "app/src/main/java", "JADX", "DECOMPILED" + (cross?.let { "; ${it.proved} methods PROVED" } ?: ""), "${q.javaFiles} files: ${q.clean} clean, ${q.withWarnings} with decompiler warnings, ${q.withFailedMethods} with failed methods; ${q.appFiles} in app packages"))
            val verified = libs.filter { it.status == "VERIFIED" }
            if (verified.isNotEmpty()) add(LayerSummary("Libraries", "app/build.gradle dependencies", "Google Maven / Maven Central artifacts", "VERIFIED IDENTITY", "${verified.size} libraries matched by class and method signatures; their decompiled copies were replaced by the real artifacts"))
            if (compile.status != "NOT_RUN") {
                val status = if (compile.filesWithErrors == 0) "COMPILES (all ${compile.files} files)" else "COMPILES: ${compile.files - compile.filesWithErrors}/${compile.files} files (app ${compile.appFiles - compile.appFilesWithErrors}/${compile.appFiles})"
                add(LayerSummary("Compile check", "recovery/compile-diagnostics.json", compile.compiler, status, compile.note))
            }
            if (repair.status == "MEASURED" && repair.accepted > 0) add(LayerSummary("Verified repairs", "recovery/repairs.json", "candidate edits + round trip", "REPAIRED: ${repair.methodsFixed} methods", "${repair.accepted} decompiler mistakes corrected; each edit kept only because recompiling proved it"))
            if (rt.status == "MEASURED") add(LayerSummary("Round-trip verification", "recovery/round-trip.json", "ECJ + D8 + dataflow fingerprint", "ROUND-TRIP EQUIVALENT for ${rt.equivalent}/${rt.methods} methods", "app code: ${rt.appEquivalent}/${rt.appMethods}; ${rt.similarOperations} same operations reordered, ${rt.different} different, ${rt.notCompiled} not compiled"))
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
            append("\n## Round-trip verification\n\nEvery method of the recovered classes was recompiled (javac, or ECJ for files with errors; then D8) and compared with the original bytecode by dataflow fingerprint ")
            append("(operations, constants, members, control flow and operand origins; register allocation and moves ignored). ")
            append("\"Modulo layout\" additionally ignores block order, branch polarity and shared vs duplicated return tails, which compilers and decompilers choose freely. ")
            append("A method listed as different is where to look for a decompiler error: `recovery/round-trip.json` names each one.\n\n")
            append("| Result | Methods |\n|---|---|\n| Identical dataflow | ${rt.equivalentDataflow} |\n| Equivalent modulo layout | ${rt.equivalentModuloLayout} |\n| Same operations, different order or operand flow | ${rt.similarOperations} |\n")
            append("| Different | ${rt.different} |\n| Not compiled (file has errors) | ${rt.notCompiled} |\n| Not found after recompilation | ${rt.notFound} |\n| **Total** | **${rt.methods}** |\n\n")
            append("Not counted: ${rt.syntheticNotCompared} compiler-generated methods (accessors, bridges, desugared lambdas) that have no source of their own. Anonymous classes are matched by structure, since a decompiler may emit them in a different order than the original source.\n\n")
            append("App code only: ${rt.appEquivalent} of ${rt.appMethods} methods equivalent.\n\n")
            append("${rt.compiledWithJavac} files were recompiled with javac (the compiler Android builds use); ${rt.compiledWithEcjOnly} kept ECJ output because they have compile errors or javac rejected them (`recovery/javac-rejected.txt`).\n")
            if (rt.compiledWithRelaxedExceptions > 0) {
                append("${rt.compiledWithRelaxedExceptions} of the javac files break only Java's checked-exception rules (typical of decompiled Kotlin, which has none); they were compiled with those rules relaxed, ")
                append("which changes no generated code, so their methods are compared too (`recovery/javac-relaxed.txt`). As delivered, these files still need `throws` clauses or catches to compile.\n")
            }
        }
        if (r.repair.status == "MEASURED") {
            append("\n## Verified repairs\n\nFor methods that did not round-trip, candidate edits that undo known decompiler mistakes were tried. ")
            append("An edit was kept only when recompiling proved that more methods became equivalent to the original bytecode and none got worse ")
            append("(${r.repair.accepted} kept of ${r.repair.candidatesTried} tried, ${r.repair.methodsFixed} methods fixed; ${r.repair.note}).\n")
            if (r.repairs.isNotEmpty()) {
                append("\n| File | Rule | Before | After | Methods fixed |\n|---|---|---|---|---|\n")
                fun cell(s: String) = "`" + s.replace("|", "\\|").replace("`", "'").take(120) + "`"
                r.repairs.forEach { append("| `${it.file}:${it.line}` | ${it.rule} | ${cell(it.before)} | ${cell(it.after)} | ${it.fixedMethods.size} |\n") }
            }
        }
        if (r.originalToolchain.isNotEmpty()) {
            append("\n## Original toolchain\n\nThe DEX files carry the compiler's stamp: ${r.originalToolchain.joinToString("; ")}.")
            if (r.originalToolchain.any { it.tool == "R8" }) append(" R8 renames, inlines and merges code, so names in the recovered source are R8's, and some code has no direct Java form.")
            if (r.libraryClassesNamed > 0) append(" ${r.libraryClassesNamed} renamed classes were matched to classes of the library versions the APK declares and carry those names (INFERRED: matched by strings, framework calls and member shapes, with size and hierarchy constraints). These names are not verified artifact identities. The list is in `recovery/library-matches.json`.")
            if (r.evidenceNames.sum() > 0) append(
                " ${r.evidenceNames[0]} classes, ${r.evidenceNames[1]} fields and ${r.evidenceNames[2]} methods have their original names back from strings the compilers wrote into the bytecode " +
                    "(OBSERVED: Kotlin data-class `toString`, Moshi adapters, ViewBinding layouts and view ids, Room tables and columns, enum constants). " +
                    "A class whose name would clash with another class it is used with carries its package as a suffix. Each name and its evidence is in `recovery/recovered-names.json`.",
            )
            append("\n")
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
        append("Recovered by apkproject from `${r.input}` (sha256 `${r.inputSha256.take(16)}...`). Open this directory in Android Studio.\n\n")
        append("| Layer | Where | Evidence |\n|---|---|---|\n")
        r.layers.forEach { append("| ${it.layer} | `${it.location}` | ${it.evidence} |\n") }
        append("\nEvidence levels: SOURCE AS SHIPPED (original files), DECODED (exact data, reformatted), DECOMPILED (derived source; may be imperfect), ")
        append("PROVED (machine-checked equal to the bytecode for all inputs), ROUND-TRIP EQUIVALENT (recompiles to the same dataflow as the original method), ")
        append("REPAIRED (a decompiler mistake corrected and the correction verified by round trip), VERIFIED IDENTITY (a library matched to its published artifact), ")
        append("OBSERVED (read directly from the package), INFERRED (derived from code), SYNTHESIZED (generated scaffolding, not recovered).\n\n")
        append("Details: `recovery/RECOVERY.md`. The backend's own code is not in the APK; see `backend/README.md`.\n")
    }
}

/**
 * Re-runs the round trip of a recovered project against its original APK: after edits (yours or
 * the repair loop's), it shows which methods still compile back to the original bytecode. Library
 * jars come from the project's `recovery/libraries.json` (verified coordinates, cached artifacts).
 */
class ProjectVerifier(private val profile: ResolvedProfile, private val cancel: CancellationToken = CancellationToken(), private val log: (String) -> Unit = {}) {
    fun run(inputPath: Path, projectDir: Path, outFile: Path, timeoutMs: Long = 1_800_000): RoundTripSummary {
        val input = AppInput.open(inputPath).getOrThrow()
        val dex = input.dexFiles()
        val javaRoot = projectDir.resolve("app/src/main/java")
        val work = Files.createTempDirectory("apkproject-roundtrip")
        try {
            val manifest = projectDir.resolve("app/src/main/AndroidManifest.xml").takeIf { it.isRegularFile() }?.let { Files.readString(it) }
            val appPackages = QualityScanner.appPackages(manifest)
            val libs = projectDir.resolve("recovery/libraries.json").takeIf { it.isRegularFile() }?.let { f ->
                Json.decode<List<ResolvedLibrary>>(Files.readString(f)).filter { it.status == "VERIFIED" }
            }.orEmpty()
            val jars = if (libs.isEmpty()) emptyList() else {
                val candidates = libs.map { LibraryVersion(it.coordinate.substringBeforeLast(':'), it.coordinate.substringAfterLast(':'), "recovery/libraries.json") }
                LibraryResolver(profile).resolve(candidates, dex, work, log).jars
            }
            val ecj = EcjCompiler(profile, cancel)
            val classesDir = work.resolve("classes")
            log("compiling ${projectDir.fileName} with ECJ...")
            val (compile, diagnostics) = ecj.compile(javaRoot, jars, classesDir, work.resolve("ecj"), timeoutMs, appPackages)
            log("compile check: ${compile.files - compile.filesWithErrors}/${compile.files} files compile")
            val files = Files.walk(javaRoot).use { st -> st.filter { it.isRegularFile() && it.toString().endsWith(".java") }.map { javaRoot.relativize(it).toString().replace('\\', '/') }.toList() }.toSet()
            val clean = files.filter { (diagnostics[it]?.errors ?: 1) == 0 }
            val relaxable = files.filter { diagnostics[it]?.onlyCheckedExceptionErrors == true }
            val overlay = JavacOverlay(profile, cancel).apply(javaRoot, clean, classesDir, jars, work.resolve("javac"), timeoutMs, relaxable)
            log("javac accepted ${overlay.javacFiles.size}/${clean.size + relaxable.size} files (${overlay.relaxedFiles.size} with checked-exception rules relaxed)")
            val minApi = projectDir.resolve("app/build.gradle").takeIf { it.isRegularFile() }?.let { Regex("""minSdkVersion\s+(\d+)""").find(Files.readString(it))?.groupValues?.get(1)?.toIntOrNull() } ?: 21
            val verifier = RoundTripVerifier(profile, cancel)
            verifier.names = projectDir.resolve("recovery/name-mapping.json").takeIf { it.isRegularFile() }?.let { Json.decode<NameMapping>(Files.readString(it)) } ?: NameMapping.EMPTY
            verifier.renames = JadxRenames.parse(javaRoot)
            val (summary, methods) = verifier.verify(dex, classesDir, jars, diagnostics - overlay.relaxedFiles.toSet(), files, appPackages, minApi, work.resolve("roundtrip"), timeoutMs)
            Files.createDirectories(outFile.toAbsolutePath().parent)
            Files.writeString(outFile, Json.encode(methods))
            return summary.copy(compiledWithJavac = overlay.javacFiles.size, compiledWithRelaxedExceptions = overlay.relaxedFiles.size, compiledWithEcjOnly = files.size - overlay.javacFiles.size)
        } finally {
            if (System.getenv("APKPROJECT_KEEP_WORK") == "1") log("work directory kept: $work") else AtomicFiles.deleteRecursively(work)
        }
    }
}
