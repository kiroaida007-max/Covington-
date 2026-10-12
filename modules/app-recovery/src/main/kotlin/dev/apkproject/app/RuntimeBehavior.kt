package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.CancelledException
import dev.apkproject.compiler.ProcessOutcome
import dev.apkproject.compiler.ProcessWorker
import dev.apkproject.contracts.Hashing
import dev.apkproject.contracts.Json
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class RuntimeAction(val kind: String, val text: String = "", val contains: String? = null, val code: Int = 0, val milliseconds: Long = 0)

@Serializable
data class RuntimeScenario(val schemaVersion: Int = 1, val `package`: String, val activity: String, val actions: List<RuntimeAction>)

data class RuntimeRequest(val apk: Path, val adb: Path, val aapt2: Path, val serial: String, val out: Path,
    val scenario: RuntimeScenario? = null, val sessionSeconds: Int = 180)

@Serializable
data class RuntimeUiNode(val attributes: Map<String, String>)
@Serializable
data class RuntimeSocket(val protocol: String, val local: String, val remote: String, val state: String)
@Serializable
data class RuntimeSnapshot(val action: Int, val elapsedMs: Long, val ui: List<RuntimeUiNode>,
    val expectationPassed: Boolean? = null, val processIds: List<Int> = emptyList(), val totalPssKb: Long? = null,
    val sockets: List<RuntimeSocket> = emptyList())
@Serializable
data class RuntimeFile(val path: String, val bytes: Long, val modifiedSeconds: Long, val prefixSha256: String? = null, val hashedBytes: Long = 0)
@Serializable
data class RuntimeFileChange(val change: String, val before: RuntimeFile? = null, val after: RuntimeFile? = null)
@Serializable
data class RuntimeCollector(val name: String, val status: String, val detail: String)
@Serializable
data class RuntimeBehaviorReport(val schemaVersion: Int = 1, val evidence: String = "OBSERVED", val sessionId: String,
    val startedAt: String, val serial: String, val apkSha256: String?, val scenarioSha256: String?,
    val packageName: String?, val activity: String?, val uid: Int?, val status: String, val elapsedMs: Long,
    val actionsCompleted: Int, val snapshots: List<RuntimeSnapshot>, val filesBefore: List<RuntimeFile>, val filesAfter: List<RuntimeFile>,
    val fileChanges: List<RuntimeFileChange>, val appLog: String, val crashExcerpts: List<String>,
    val permissions: Map<String, Boolean>, val deniedPackets: Map<String, Long>, val collectors: List<RuntimeCollector>,
    val issues: List<String>, val cleanupComplete: Boolean, val failure: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val scope: String = "Observed execution of the declared scenario on one rooted disposable emulator; unvisited paths and whole-app equivalence are not established.")

@Serializable
data class RuntimeComparison(val schemaVersion: Int = 1, val status: String, val originalApkSha256: String?,
    val candidateApkSha256: String?, val scenarioSha256: String?, val differentObservations: List<Int>,
    val scope: String = "Canonical visible app UI at the declared observation points only; storage, timing, network payloads and unvisited paths are outside this comparison.")

internal fun interface RuntimeCommand {
    fun run(argv: List<String>, timeoutMs: Long, cancel: CancellationToken?): ProcessOutcome
    fun close() {}
}

/** Actual APK execution. No inferred/static flow is promoted to a runtime observation. */
object RuntimeBehavior {
    private const val MAX_APK_BYTES = 1L shl 30
    private const val MAX_COMMAND_BYTES = 1 shl 20
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val ACTIVITY = Regex("\\.?[A-Za-z_$][A-Za-z0-9_.$]*")

    fun readScenario(path: Path): RuntimeScenario {
        val bytes = Files.newInputStream(path).use { it.readNBytes(65_537) }
        require(bytes.size <= 65_536) { "Runtime scenario exceeds 64 KiB" }
        return Json.decode<RuntimeScenario>(bytes.toString(Charsets.UTF_8)).also(::validate)
    }

    fun validate(scenario: RuntimeScenario) {
        require(scenario.schemaVersion == 1 && PACKAGE.matches(scenario.`package`) && scenario.`package`.length <= 200) { "Invalid runtime scenario package/version" }
        require(ACTIVITY.matches(scenario.activity) && scenario.activity.length <= 200) { "Invalid runtime activity" }
        require(scenario.actions.size in 1..32 && scenario.actions.any { it.kind == "snapshot" }) { "Scenario needs 1–32 actions and at least one snapshot" }
        scenario.actions.forEach { a ->
            require(a.kind in setOf("snapshot", "tap_text", "text", "key", "wait")) { "Unsupported runtime action: ${a.kind}" }
            require(a.text.length <= 256 && (a.contains?.length ?: 0) <= 256) { "Runtime action text exceeds 256 characters" }
            require(a.text.none { it.code < 32 } && a.contains?.none { it.code < 32 } != false) { "Control characters in runtime action" }
            when (a.kind) {
                "tap_text" -> require(a.text.isNotEmpty()) { "tap_text needs text" }
                // Android input text interprets a small escape syntax. Restrict it, rather than
                // claiming faithful entry of arbitrary Unicode or interpreting shell metacharacters.
                "text" -> require(a.text.matches(Regex("[A-Za-z0-9 ._@+-]{1,256}"))) { "Text entry supports ASCII letters, digits, spaces and ._@+-" }
                "key" -> require(a.code in 0..288) { "Invalid Android key code" }
                "wait" -> require(a.milliseconds in 1..10_000) { "Wait must be 1–10,000 ms" }
            }
        }
    }

    fun run(request: RuntimeRequest, cancel: CancellationToken = CancellationToken(), log: (String) -> Unit = {}): RuntimeBehaviorReport =
        runWith(request, cancel, log, RuntimeAdbTransport(request))

    internal fun runWith(request: RuntimeRequest, cancel: CancellationToken, log: (String) -> Unit, command: RuntimeCommand): RuntimeBehaviorReport {
        require(request.serial.matches(Regex("emulator-[0-9]{4,5}"))) { "Select a disposable emulator, not a physical device" }
        require(request.sessionSeconds in 10..600) { "Runtime session limit must be 10–600 seconds" }
        request.scenario?.let(::validate)
        for (p in listOf(request.apk, request.adb, request.aapt2)) require(Files.isRegularFile(p)) { "Runtime input/tool not found: $p" }
        require(Files.size(request.apk) in 1..MAX_APK_BYTES) { "APK must be nonempty and at most 1 GiB" }
        val out = request.out.toAbsolutePath().normalize()
        require(out.parent != null) { "Select a new runtime session directory, not a filesystem root" }
        Files.createDirectories(out.parent)
        Files.createDirectory(out) // Never overwrite another session's evidence.
        val session = Session(request.copy(out = out), cancel, log, command)
        return session.run()
    }

    fun compare(original: RuntimeBehaviorReport, candidate: RuntimeBehaviorReport): RuntimeComparison {
        val eligible = original.status == "COMPLETED" && candidate.status == "COMPLETED" && original.cleanupComplete && candidate.cleanupComplete &&
            original.scenarioSha256 != null && original.scenarioSha256 == candidate.scenarioSha256 &&
            original.packageName == candidate.packageName && original.snapshots.isNotEmpty() &&
            original.collectors.any { it.name == "UI" && it.status == "CAPTURED" } && candidate.collectors.any { it.name == "UI" && it.status == "CAPTURED" }
        val differences = if (eligible) (0 until maxOf(original.snapshots.size, candidate.snapshots.size)).filter { i ->
            original.snapshots.getOrNull(i)?.ui != candidate.snapshots.getOrNull(i)?.ui || original.snapshots.getOrNull(i)?.action != candidate.snapshots.getOrNull(i)?.action
        } else emptyList()
        return RuntimeComparison(status = if (!eligible) "INCONCLUSIVE" else if (differences.isEmpty()) "SCENARIO_MATCHED" else "DIFFERENT",
            originalApkSha256 = original.apkSha256, candidateApkSha256 = candidate.apkSha256, scenarioSha256 = original.scenarioSha256, differentObservations = differences)
    }

    /** ADB concatenates shell arguments remotely; quote every data argument explicitly. */
    internal fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private class Refused(message: String) : IllegalStateException(message)

    private class Session(val request: RuntimeRequest, val cancel: CancellationToken, val log: (String) -> Unit, val command: RuntimeCommand) {
        val id = UUID.randomUUID().toString()
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val deadline = started + request.sessionSeconds * 1_000_000_000L
        val issues = linkedSetOf<String>()
        val collectors = linkedMapOf<String, RuntimeCollector>()
        val snapshots = arrayListOf<RuntimeSnapshot>()
        var scenario: RuntimeScenario? = request.scenario
        var apkHash: String? = null
        var scenarioHash: String? = null
        var uid: Int? = null
        var attemptedInstall = false
        var ownedEmulator = false
        var completed = 0
        var uiCharacters = 0
        var before = emptyList<RuntimeFile>()
        var after = emptyList<RuntimeFile>()
        var beforeSampled = false
        var afterSampled = false
        var appLog = ""
        var crashes = emptyList<String>()
        var permissions = emptyMap<String, Boolean>()
        val packets = linkedMapOf<String, Long>()
        val guardedTools = arrayListOf<String>()
        val chain = "APKR_" + id.replace("-", "").take(16)
        val uiPath = "/data/local/tmp/apkproject-$id.xml"
        var logSince: String? = null
        val environment = linkedMapOf<String, String>()
        var state = "FAILED"
        var failure: String? = null
        var cleaned = true
        var rescueDeadline = Long.MAX_VALUE
        var observationDeadline = Long.MAX_VALUE

        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        fun issue(s: String) { if (issues.size < 64) issues.add(s.take(1024)) }
        fun coverage(name: String, status: String, detail: String) {
            val old = collectors[name]
            // An unavailable/partial sample cannot later be hidden by a successful sample.
            val rank = mapOf("CAPTURED" to 0, "PARTIAL" to 1, "UNAVAILABLE" to 2, "NOT_IMPLEMENTED" to 3)
            if (old == null || rank.getValue(status) >= rank.getValue(old.status)) collectors[name] = RuntimeCollector(name, status, detail)
        }

        fun check() {
            if (cancel.cancelled || Thread.currentThread().isInterrupted) throw CancelledException()
            if (System.nanoTime() >= deadline) throw IllegalStateException("Runtime session exceeded ${request.sessionSeconds} seconds")
        }

        fun invoke(argv: List<String>, cleanup: Boolean = false, timeout: Long = 20_000): ProcessOutcome {
            if (!cleanup) check()
            val limit = if (cleanup) minOf(rescueDeadline, observationDeadline) else deadline
            check(System.nanoTime() < limit) { "Runtime ${if (cleanup) "cleanup/observation" else "session"} deadline exhausted" }
            val remaining = minOf(timeout, ((limit - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
            return command.run(argv, remaining, if (cleanup) null else cancel).also {
                if (it.cancelled) throw CancelledException()
                if (!cleanup && Thread.currentThread().isInterrupted) throw CancelledException()
            }
        }

        fun adb(vararg args: String, cleanup: Boolean = false, timeout: Long = 20_000): ProcessOutcome =
            invoke(listOf(request.adb.toAbsolutePath().toString(), "-s", request.serial) + args, cleanup, timeout)

        fun shell(vararg args: String, cleanup: Boolean = false): ProcessOutcome = adb("shell", args.joinToString(" ") { quote(it) }, cleanup = cleanup)
        fun required(r: ProcessOutcome, operation: String): String {
            check(r.ok && !r.truncated) { "$operation failed${if (r.timedOut) " (timeout)" else ""}: ${r.stderr.take(2000)} ${r.stdout.take(1000)}" }
            return r.stdout.trim()
        }

        fun run(): RuntimeBehaviorReport {
            var channel: FileChannel? = null
            var lock: java.nio.channels.FileLock? = null
            try {
                val locks = Path.of(System.getProperty("user.home"), ".apkproject", "runtime-locks")
                Files.createDirectories(locks)
                require(!Files.isSymbolicLink(locks)) { "Runtime lock directory must not be a symlink" }
                channel = FileChannel.open(locks.resolve(request.serial + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) throw Refused("Another runtime session owns this emulator")
                log("Runtime: checking disposable emulator ${request.serial}")
                required(adb("wait-for-device", timeout = 30_000), "Emulator connection")
                required(adb("get-state"), "Emulator connection").let { if (it != "device") throw Refused("Emulator is not ready") }
                if (required(shell("getprop", "ro.kernel.qemu"), "Emulator identity") != "1") throw Refused("Physical devices are refused")
                if (required(shell("getprop", "sys.boot_completed"), "Emulator boot") != "1") throw Refused("Wait until the disposable emulator has finished booting")
                if (required(shell("am", "get-current-user"), "Android user") != "0") throw Refused("Runtime sessions require the fresh emulator's primary user")
                val sdk = required(shell("getprop", "ro.build.version.sdk"), "Android version").toIntOrNull()
                if (sdk == null || sdk < 30) throw Refused("Runtime sessions require Android API 30 or newer")
                environment["androidApi"] = sdk.toString()
                environment["androidBuild"] = required(shell("getprop", "ro.build.fingerprint"), "Android build").take(512)
                environment["abi"] = required(shell("getprop", "ro.product.cpu.abi"), "Android ABI").take(128)
                environment["adbVersion"] = required(invoke(listOf(request.adb.toAbsolutePath().toString(), "version")), "ADB version").take(1024)
                environment["aapt2Version"] = required(invoke(listOf(request.aapt2.toAbsolutePath().toString(), "version")), "aapt2 version").take(1024)
                required(adb("root"), "Emulator root")
                required(adb("wait-for-device", timeout = 30_000), "Emulator reconnection")
                if (required(shell("id", "-u"), "Root identity") != "0") throw Refused("A rooted disposable emulator is required")
                ownedEmulator = true
                // Hash exactly the bounded immutable staging copy that will be installed.
                apkHash = stageApk()
                val badging = required(invoke(listOf(request.aapt2.toAbsolutePath().toString(), "dump", "badging", request.out.resolve("input.apk").toString())), "APK metadata")
                val pkg = Regex("(?m)^package: name='([^']+)'").find(badging)?.groupValues?.get(1) ?: throw Refused("APK has no package identity")
                if (!PACKAGE.matches(pkg)) throw Refused("APK package identity is invalid")
                if (scenario == null) {
                    val activity = Regex("(?m)^launchable-activity: name='([^']+)'").find(badging)?.groupValues?.get(1) ?: throw Refused("No launcher activity; supply a scenario")
                    scenario = RuntimeScenario(`package` = pkg, activity = activity, actions = listOf(RuntimeAction("wait", milliseconds = 1000), RuntimeAction("snapshot")))
                }
                val s = scenario!!.also(::validate)
                if (s.`package` != pkg) throw Refused("APK package differs from the scenario")
                scenarioHash = Hashing.sha256(Json.encodeLine(s))
                AtomicFiles.writeString(request.out.resolve("scenario.json"), Json.encode(s))
                val installed = required(shell("cmd", "package", "list", "packages", pkg), "Existing installation check")
                if (installed.lineSequence().any { it == "package:$pkg" }) throw Refused("Target package already exists; use a fresh emulator")
                logSince = required(shell("date", "+%m-%d %H:%M:%S.000"), "Emulator clock").also {
                    require(it.matches(Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.000"))) { "Unexpected emulator clock" }
                }
                log("Runtime: installing ${s.`package`}")
                // Installation can complete even if the local ADB client times out: cleanup
                // ownership begins before sending the command, not after seeing Success.
                attemptedInstall = true
                if (required(adb("install", "--no-streaming", "-t", request.out.resolve("input.apk").toString(), timeout = 60_000), "APK installation").lineSequence().none { it == "Success" }) error("APK installation was not confirmed")
                val listing = required(shell("cmd", "package", "list", "packages", "-U"), "App UID")
                uid = Regex("(?m)^package:" + Regex.escape(pkg) + " uid:([0-9]+)\\s*$").find(listing)?.groupValues?.get(1)?.toIntOrNull()
                if (uid == null || uid!! < 10_000 || uid!! >= 100_000) throw Refused("Dedicated primary-user app UID unavailable")
                if (Regex("(?m) uid:${uid}\\s*$").findAll(listing).count() != 1) throw Refused("Shared app UIDs are refused")
                for (tool in listOf("iptables", "ip6tables")) {
                    guardedTools.add(tool) // Journal potentially committed commands before sending.
                    required(shell(tool, "-w", "10", "-N", chain), "$tool session chain")
                    required(shell(tool, "-w", "10", "-A", chain, "-j", "REJECT"), "$tool denial rule")
                    required(shell(tool, "-w", "10", "-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", uid.toString(), "-j", chain), "$tool app network denial")
                }
                coverage("Network policy", "CAPTURED", "Dedicated app-UID IPv4/IPv6 denial installed before launch; installation is outside this boundary.")
                before = files()
                beforeSampled = true
                required(shell("input", "keyevent", "KEYCODE_WAKEUP"), "Wake emulator display")
                required(shell("wm", "dismiss-keyguard"), "Dismiss disposable emulator's keyguard")
                val component = pkg + "/" + s.activity
                log("Runtime: launching $component")
                val launch = required(shell("am", "start", "-W", "-n", component), "Activity launch")
                check(!launch.contains("Error:") && !launch.contains("Exception")) { "Activity launch failed: ${launch.take(2000)}" }
                s.actions.forEachIndexed { index, action ->
                    check()
                    log("Runtime: action ${index + 1}/${s.actions.size} (${action.kind})")
                    when (action.kind) {
                        "wait" -> {
                            val until = System.nanoTime() + action.milliseconds * 1_000_000
                            while (System.nanoTime() < until) { check(); Thread.sleep(25) }
                        }
                        "text" -> required(shell("input", "text", action.text.replace(" ", "%s")), "Text entry")
                        "key" -> required(shell("input", "keyevent", action.code.toString()), "Key event")
                        "tap_text" -> {
                            val (_, elements) = ui()
                            val targets = elements.filter { it.getAttribute("text").equals(action.text, ignoreCase = true) }
                            check(targets.size == 1) { "Tap target must have exactly one match in the app UI" }
                            val match = Regex("\\[([0-9]+),([0-9]+)]\\[([0-9]+),([0-9]+)]").matchEntire(targets.single().getAttribute("bounds")) ?: error("Tap target has no bounds")
                            val b = match.groupValues.drop(1).map { it.toInt() }
                            check(b.all { it in 0..16_384 } && b[2] > b[0] && b[3] > b[1]) { "Tap target bounds are invalid" }
                            required(shell("input", "tap", ((b[0] + b[2]) / 2).toString(), ((b[1] + b[3]) / 2).toString()), "Tap")
                        }
                        "snapshot" -> {
                            val nodes = ui().first
                            val passed = action.contains?.let { expected -> nodes.any { it.attributes["text"]?.contains(expected) == true } }
                            snapshots.add(RuntimeSnapshot(index, elapsed(), nodes, passed, processes(), memory(), sockets()))
                            if (passed == false) error("UI expectation failed at action ${index + 1}")
                        }
                    }
                    completed++
                }
                state = "COMPLETED"
            } catch (e: Exception) {
                state = when (e) { is CancelledException, is InterruptedException -> "CANCELLED"; is Refused -> "REFUSED"; else -> "FAILED" }
                failure = (e.message ?: e.javaClass.simpleName).take(4096)
            } finally {
                // Cancellation stops work, but cannot prevent owned emulator cleanup.
                Thread.interrupted()
                rescueDeadline = System.nanoTime() + 45_000_000_000L
                observationDeadline = System.nanoTime() + 10_000_000_000L
                if (attemptedInstall && uid != null && ownedEmulator) {
                    runCatching { finalEvidence() }.onFailure { issue("Final observations incomplete: ${it.message}") }
                }
                observationDeadline = Long.MAX_VALUE
                if (attemptedInstall && ownedEmulator) cleaned = cleanup()
                if (ownedEmulator) runCatching { shell("rm", "-f", uiPath, cleanup = true) }
                runCatching { Files.deleteIfExists(request.out.resolve("input.apk")) }.onFailure { issue("Staged APK could not be removed") }
                runCatching { command.close() }.onFailure { cleaned = false; issue("Host ADB server cleanup failed: ${it.message}") }
                runCatching { lock?.release() }.onFailure { issue("Host emulator lock cleanup failed") }
                runCatching { channel?.close() }.onFailure { issue("Host emulator lock close failed") }
            }
            if (!cleaned) { state = "FAILED"; failure = listOfNotNull(failure, "Emulator cleanup failed; inspect issues before reuse").joinToString("; ") }
            for (name in listOf("UI", "App logs", "Storage", "Processes", "Memory", "Network sockets", "Permissions", "Network policy")) {
                if (name !in collectors) coverage(name, "UNAVAILABLE", "No observation was collected in this session.")
            }
            coverage("API calls and network payloads", "NOT_IMPLEMENTED", "No method hooks, decrypted request bodies or native instruction tracing; socket samples are metadata only.")
            val storageComparable = beforeSampled && afterSampled && collectors["Storage"]?.status == "CAPTURED"
            val changes = if (storageComparable) changes(before, after) else emptyList()
            if (!storageComparable) issue("Storage delta withheld because an inventory was unavailable or incomplete; inspect the individual samples.")
            val report = RuntimeBehaviorReport(sessionId = id, startedAt = startedAt, serial = request.serial, apkSha256 = apkHash,
                scenarioSha256 = scenarioHash, packageName = scenario?.`package`, activity = scenario?.activity, uid = uid, status = state,
                elapsedMs = elapsed(), actionsCompleted = completed, snapshots = snapshots, filesBefore = before, filesAfter = after,
                fileChanges = changes, appLog = appLog, crashExcerpts = crashes, permissions = permissions, deniedPackets = packets,
                collectors = collectors.values.toList(), issues = issues.toList(), cleanupComplete = cleaned, failure = failure, environment = environment)
            val json = Json.encode(report)
            check(json.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Runtime report exceeded 8 MiB" }
            AtomicFiles.writeString(request.out.resolve("report.json"), json)
            AtomicFiles.writeString(request.out.resolve("README.md"), markdown(report))
            log("Runtime: $state; ${snapshots.size} observations; report ${request.out.resolve("report.json")}")
            return report
        }

        fun stageApk(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(request.apk).use { input -> Files.newOutputStream(request.out.resolve("input.apk"), StandardOpenOption.CREATE_NEW).use { output ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    check(); val n = input.read(bytes); if (n < 0) break
                    size += n; require(size <= MAX_APK_BYTES) { "APK grew beyond 1 GiB while staging" }
                    digest.update(bytes, 0, n); output.write(bytes, 0, n)
                }
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun ui(): Pair<List<RuntimeUiNode>, List<org.w3c.dom.Element>> {
            required(shell("uiautomator", "dump", uiPath), "UI observation")
            val xml = required(shell("cat", uiPath), "UI XML")
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 128)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false; isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val list = document.getElementsByTagName("node")
            require(list.length <= 5000) { "UI hierarchy exceeds 5,000 nodes" }
            val elements = (0 until list.length).map { list.item(it) as org.w3c.dom.Element }.filter { it.getAttribute("package") == scenario!!.`package` }
            if (elements.isEmpty()) {
                val title = (0 until list.length).map { list.item(it) as org.w3c.dom.Element }
                    .firstOrNull { it.getAttribute("resource-id") == "android:id/alertTitle" }?.getAttribute("text").orEmpty()
                check(!title.contains("isn't responding") && !title.contains("is not responding") && !title.contains("keeps stopping")) {
                    "Emulator UI blocked by an Android error dialog: ${title.take(256)}"
                }
            }
            check(elements.isNotEmpty()) { "No visible app UI was observed (active packages: " + (0 until list.length).map { (list.item(it) as org.w3c.dom.Element).getAttribute("package") }.distinct().take(8).joinToString().take(512) + ")" }
            val keys = listOf("class", "resource-id", "text", "content-desc", "checked", "enabled", "clickable")
            var limited = elements.size > 500
            val nodes = elements.take(500).map { element ->
                RuntimeUiNode(keys.associateWith { key ->
                    val text = element.getAttribute(key)
                    if (text.length > 256) limited = true
                    uiCharacters += minOf(text.length, 256)
                    require(uiCharacters <= 256 * 1024) { "Session UI character budget exhausted" }
                    text.take(256)
                })
            }
            coverage("UI", if (limited) "PARTIAL" else "CAPTURED", "Canonical visible app nodes at declared snapshot points; at most 500 nodes/256 characters per field. Geometry and system UI excluded.")
            return nodes to elements
        }

        fun optional(r: ProcessOutcome, name: String): String? {
            if (!r.ok || r.truncated) {
                coverage(name, if (r.truncated) "PARTIAL" else "UNAVAILABLE", "Observation command failed, timed out or exceeded its byte limit.")
                issue("$name: ${r.stderr.take(1000)}${if (r.truncated) " output limit reached" else " observation unavailable"}")
                return if (r.ok) r.stdout else null
            }
            return r.stdout
        }

        fun processes(): List<Int> {
            val raw = optional(shell("ps", "-A", "-o", "PID,UID,NAME"), "Processes") ?: return emptyList()
            val pkg = scenario!!.`package`
            val result = raw.lineSequence().map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 3 && it[1].toIntOrNull() == uid && (it[2] == pkg || it[2].startsWith("$pkg:")) }.mapNotNull { it[0].toIntOrNull() }.take(65).toList()
            coverage("Processes", if (result.size > 64) "PARTIAL" else "CAPTURED", "App-name and dedicated-UID matched process IDs sampled at observation points.")
            return result.take(64)
        }

        fun memory(): Long? {
            val raw = optional(shell("dumpsys", "meminfo", "--local", scenario!!.`package`), "Memory") ?: return null
            val pss = Regex("TOTAL PSS:\\s*([0-9]+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("(?m)^\\s*TOTAL\\s+([0-9]+)\\s").find(raw)?.groupValues?.get(1)?.toLongOrNull()
            coverage("Memory", if (pss == null) "UNAVAILABLE" else "CAPTURED", "dumpsys sampled total proportional-set memory in KiB; not a peak measurement.")
            return pss
        }

        fun sockets(): List<RuntimeSocket> {
            val result = arrayListOf<RuntimeSocket>()
            for (protocol in listOf("tcp", "tcp6", "udp", "udp6")) {
                val raw = optional(shell("cat", "/proc/net/$protocol"), "Network sockets") ?: continue
                raw.lineSequence().drop(1).forEach { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size >= 10 && f[7].toIntOrNull() == uid) {
                        if (result.size < 128) result.add(RuntimeSocket(protocol, f[1].take(80), f[2].take(80), f[3].take(4)))
                        else coverage("Network sockets", "PARTIAL", "More than 128 UID-attributed sockets at one sample.")
                    }
                }
            }
            coverage("Network sockets", "CAPTURED", "UID-attributed /proc socket metadata sampled at observation points; addresses are kernel hexadecimal. Short-lived sockets, request contents and method calls are not observed.")
            return result
        }

        fun files(cleanup: Boolean = false): List<RuntimeFile> {
            val pkg = scenario!!.`package`
            val roots = listOf("/data/user/0/$pkg", "/data/user_de/0/$pkg")
            val script = "( " + roots.joinToString("; ") { root -> "if [ -d ${quote(root)} ]; then find ${quote(root)} -xdev -maxdepth 8 -type f -exec stat -c '%s %Y %n' {} \\; ; fi" } + " ) | head -n 129"
            val captured = adb("shell", script, cleanup = cleanup)
            if (captured.stderr.isNotBlank()) {
                coverage("Storage", "PARTIAL", "Storage enumeration emitted errors; the inventory may omit files.")
                issue("Storage enumeration: ${captured.stderr.take(1000)}")
            }
            val raw = optional(captured, "Storage") ?: return emptyList()
            val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
            var limited = lines.size > 128
            val result = arrayListOf<RuntimeFile>()
            for (line in lines.take(128)) {
                val parts = line.split(' ', limit = 3)
                val bytes = parts.getOrNull(0)?.toLongOrNull()
                val time = parts.getOrNull(1)?.toLongOrNull()
                val path = parts.getOrNull(2)
                val root = roots.firstOrNull { path?.startsWith("$it/") == true }
                if (bytes == null || bytes < 0 || time == null || path == null || path.length > 512 || path.any { it.code < 32 } || root == null || path.contains("/../")) { limited = true; continue }
                val label = (if (root == roots[0]) "credential/" else "device/") + path.removePrefix("$root/")
                var hash: String? = null
                if (result.size < 16) {
                    // A bounded prefix hash, never described as a complete large-file digest.
                    val q = quote(path)
                    val digest = optional(adb("shell", "if test -f $q && test ! -L $q; then head -c 1048576 $q | sha256sum; else exit 1; fi", cleanup = cleanup), "Storage")
                    val observedSize = optional(shell("stat", "-c", "%s %Y", path, cleanup = cleanup), "Storage")?.trim()
                    if (observedSize == "$bytes $time") hash = Regex("^[0-9a-f]{64}").find(digest.orEmpty())?.value
                    else { limited = true; issue("Storage file changed during sampling: $label") }
                }
                result.add(RuntimeFile(label, bytes, time, hash, if (hash == null) 0 else minOf(bytes, 1_048_576)))
            }
            coverage("Storage", if (limited) "PARTIAL" else "CAPTURED", "Private app files before launch and after the scenario: max depth 8, 128 entries, first 16 prefixes hashed up to 1 MiB. Contents are not exported; external storage and transient/deleted files are outside the inventory.")
            return result
        }

        fun finalEvidence() {
            // Independent bounded reads also preserve crash evidence after a failed action or cancellation.
            // API 30 has UID formatting but no --uid filter. Parse the logger's UID column,
            // never app-supplied message contents, and never persist another UID's entries.
            val raw = optional(shell("logcat", "-d", "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime,uid", "-T", logSince!!, cleanup = true), "App logs")
            if (raw != null) {
                val scoped = filterUidLog(raw, uid!!)
                appLog = scoped.first.take(64 * 1024)
                coverage("App logs", if (!scoped.second) "UNAVAILABLE" else if (scoped.first.length > appLog.length) "PARTIAL" else "CAPTURED", "Logcat UID-column entries filtered by the installed app UID and session start clock; logs can contain personal data. Native/system crash records under other UIDs are outside this filter.")
                if (!scoped.second) issue("Logcat returned no recognized UID-formatted entries; an empty app log does not establish absence of logs.")
                val lines = appLog.lines()
                crashes = lines.indices.filter { lines[it].contains("FATAL EXCEPTION:") || lines[it].contains("Fatal signal ") }.take(16).map { i -> lines.subList(i, minOf(i + 12, lines.size)).joinToString("\n").take(2048) }
            }
            val packageDump = optional(shell("dumpsys", "package", scenario!!.`package`, cleanup = true), "Permissions")
            if (packageDump != null) {
                permissions = Regex("([A-Za-z][A-Za-z0-9_.]+): granted=(true|false)").findAll(packageDump).take(256).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
                coverage("Permissions", "CAPTURED", "Permission grant state after execution; grant state does not establish permission use.")
            }
            for (tool in guardedTools) {
                val rawCounters = optional(shell(tool, "-w", "10", "-n", "-v", "-x", "-L", chain, cleanup = true), "Network policy") ?: continue
                val count = Regex("(?m)^\\s*([0-9]+)\\s+[0-9]+\\s+REJECT\\s").find(rawCounters)?.groupValues?.get(1)?.toLongOrNull()
                if (count != null) packets[if (tool == "iptables") "IPv4" else "IPv6"] = count
                else coverage("Network policy", "PARTIAL", "App-UID denial installed; packet counter format unavailable.")
            }
            if (guardedTools.size == 2) { after = files(cleanup = true); afterSampled = true }
        }

        fun cleanup(): Boolean {
            val pkg = scenario!!.`package`
            var ok = true
            runCatching { required(shell("am", "force-stop", pkg, cleanup = true), "Stop app") }.onFailure { ok = false; issue("Cleanup: ${it.message}") }
            val removal = runCatching { required(adb("uninstall", pkg, cleanup = true, timeout = 30_000), "Remove app") }
            val absent = runCatching { required(shell("cmd", "package", "list", "packages", pkg, cleanup = true), "Confirm removal").lineSequence().none { it == "package:$pkg" } }.getOrDefault(false)
            if (!absent) { issue("Cleanup: app remains installed; network denial rules retained ($chain). ${removal.exceptionOrNull()?.message.orEmpty()}"); return false }
            // Confirm absence even when install/uninstall timed out. Only remove our unique chain.
            for (tool in guardedTools.asReversed()) {
                try {
                    val attached = shell(tool, "-w", "10", "-C", "OUTPUT", "-m", "owner", "--uid-owner", uid.toString(), "-j", chain, cleanup = true)
                    if (attached.ok) required(shell(tool, "-w", "10", "-D", "OUTPUT", "-m", "owner", "--uid-owner", uid.toString(), "-j", chain, cleanup = true), "Remove app network rule")
                    else check(attached.exitCode == 1 && !attached.timedOut && !attached.truncated) { "Unable to inspect owned firewall rule" }
                    val exists = shell(tool, "-w", "10", "-n", "-L", chain, cleanup = true)
                    if (exists.ok) {
                        required(shell(tool, "-w", "10", "-F", chain, cleanup = true), "Flush session chain")
                        required(shell(tool, "-w", "10", "-X", chain, cleanup = true), "Remove session chain")
                    } else check(exists.exitCode == 1 && !exists.timedOut && !exists.truncated) { "Unable to inspect owned firewall chain" }
                } catch (e: Exception) { ok = false; issue("Cleanup $tool: ${e.message}; inspect $chain before emulator reuse") }
            }
            return ok
        }
    }

    internal fun changes(before: List<RuntimeFile>, after: List<RuntimeFile>): List<RuntimeFileChange> {
        val a = before.associateBy { it.path }; val b = after.associateBy { it.path }
        return (a.keys + b.keys).sorted().mapNotNull { key ->
            when { key !in a -> RuntimeFileChange("CREATED", after = b[key]); key !in b -> RuntimeFileChange("REMOVED", before = a[key]); a[key] != b[key] -> RuntimeFileChange("CHANGED", a[key], b[key]); else -> null }
        }
    }

    internal fun filterUidLog(raw: String, uid: Int): Pair<String, Boolean> {
        val header = Regex("^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]+\\s+(\\S+)\\s+[0-9]+\\s+[0-9]+\\s+[VDIWEFAS]\\s+.*")
        var recognized = false
        val kept = ArrayList<String>()
        for (line in raw.lineSequence()) {
            val match = header.matchEntire(line) ?: continue
            recognized = true
            if (match.groupValues[1].toIntOrNull() == uid) kept.add(line)
        }
        return kept.joinToString("\n") to recognized
    }

    fun markdown(r: RuntimeBehaviorReport): String = buildString {
        append("# Runtime behavior session\n\nStatus: **${r.status}**. Evidence: observed execution.\n\n${r.scope}\n\n")
        append("Package: `${r.packageName ?: "unavailable"}`. Activity: `${r.activity ?: "unavailable"}`. Emulator: `${r.serial}`.\n\n")
        append("APK SHA-256: `${r.apkSha256 ?: "unavailable"}`\n\nScenario SHA-256: `${r.scenarioSha256 ?: "unavailable"}`\n\n")
        append("${r.actionsCompleted} actions completed; ${r.snapshots.size} UI observations; ${r.fileChanges.size} sampled storage changes; ${r.crashExcerpts.size} crash log excerpts.\n\n")
        append("Cleanup complete: ${r.cleanupComplete}. ${r.failure.orEmpty().replace('\n', ' ')}\n\n")
        append("Reports stay on this machine. UI text and app logs can contain personal data. Prefix hashes and metadata do not recover deleted files or establish complete storage equivalence.\n\n")
        r.collectors.forEach { append("- **${it.name} (${it.status})**: ${it.detail}\n") }
        if (r.issues.isNotEmpty()) { append("\n## Observation issues\n\n"); r.issues.forEach { append("- ${it.replace('\n', ' ')}\n") } }
    }
}
