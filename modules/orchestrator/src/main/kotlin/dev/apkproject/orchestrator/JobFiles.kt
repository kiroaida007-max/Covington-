package dev.apkproject.orchestrator

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.ArtifactRef
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.JobSpec
import dev.apkproject.contracts.JobState
import dev.apkproject.contracts.Json
import dev.apkproject.contracts.WorkerEnvelope
import dev.apkproject.store.ArtifactStore
import dev.apkproject.store.AtomicFiles
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/** Persistent job state (section 07). Written atomically after every transition. */
@Serializable
data class JobStateFile(
    val schemaVersion: Int = 1,
    val jobId: String,
    val state: JobState,
    val spec: JobSpec,
    val inputPath: String,
    val reportRef: ArtifactRef? = null,
    val bundleRef: ArtifactRef? = null,
    val message: String? = null,
)

/** Layout of one run directory: `state.json`, `journal.ndjson`, `store/`, `work/`, `reports/`. */
class JobDirectory(val root: Path) {
    val store: ArtifactStore by lazy { ArtifactStore(root.resolve("store")) }
    val journalFile: Path = root.resolve("journal.ndjson")
    val stateFile: Path = root.resolve("state.json")
    val work: Path = root.resolve("work")
    val reports: Path = root.resolve("reports")
    val cancelMarker: Path = root.resolve("cancel.requested")

    fun readState(): JobStateFile? = if (Files.isRegularFile(stateFile)) Json.decode<JobStateFile>(Files.readString(stateFile)) else null

    fun writeState(s: JobStateFile) = AtomicFiles.writeString(stateFile, Json.encode(s))

    fun requestCancel() {
        Files.createDirectories(root)
        Files.writeString(cancelMarker, "cancel requested\n")
    }

    /** A daemon thread that turns a cancel marker written by another process into a token cancel. */
    fun watchCancel(token: CancellationToken): Thread = Thread {
        while (!token.cancelled) {
            if (Files.exists(cancelMarker)) token.cancel()
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }.also { it.isDaemon = true; it.start() }
}

/** Newline-delimited journal of stage events, with strictly increasing sequence numbers. */
class Journal(private val dir: JobDirectory, private val jobId: String) {
    private var sequence: Long = if (Files.exists(dir.journalFile)) Files.readAllLines(dir.journalFile).size.toLong() else 0L
    var state: JobState = JobState.CREATED
        private set

    fun resumeFrom(s: JobState) {
        state = s
    }

    fun event(event: String, status: CheckStatus, refs: List<ArtifactRef> = emptyList(), message: String? = null) {
        val e = WorkerEnvelope(jobId = jobId, stage = state, event = event, sequence = ++sequence, artifactRefs = refs, status = status, message = message)
        AtomicFiles.appendLine(dir.journalFile, Json.encodeLine(e))
    }

    fun transition(next: JobState, message: String? = null) {
        check(state == next || state.canMoveTo(next)) { "illegal transition $state -> $next" }
        state = next
        event("state.$next", CheckStatus.PASS, message = message)
    }
}
