package dev.apkproject.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer

const val SCHEMA_VERSION = 1

/** Resource and iteration budgets (section 26). Defaults follow the 8 GB host profile. */
@Serializable
data class Budget(
    val maxCandidatesPerMethod: Int = 5,
    val maxProbesPerPattern: Int = 12,
    val solverTimeoutMs: Long = 10_000,
    val compileTimeoutMs: Long = 120_000,
    val runtimeTimeoutMs: Long = 60_000,
    val maxMethodCodeUnits: Int = 4_096,
    val maxStructuredStatements: Int = 2_000,
    val randomInputsPerMethod: Int = 64,
)

@Serializable
data class JobSpec(
    val schemaVersion: Int = SCHEMA_VERSION,
    val jobId: String,
    val inputRefs: List<ArtifactRef>,
    val profileId: String,
    val profileHash: String,
    val policy: RecoveryPolicy,
    val budget: Budget,
    val engineVersion: String,
)

/** One observed or derived statement about a subject, with its byte provenance. */
@Serializable
data class EvidenceFact(
    val id: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val byteRanges: List<ByteRange>,
    val kind: EvidenceKind,
)

/** Something the selected engine cannot currently model. Gaps block full-source claims. */
@Serializable
data class CapabilityGap(
    val subject: String,
    val code: String,
    val detail: String,
    val byteRange: ByteRange? = null,
)

@Serializable
data class RuleApplication(
    val ruleId: String,
    val site: String,
    val assumptions: List<String> = emptyList(),
)

@Serializable
data class CandidateRef(
    val id: String,
    val sourceHash: String,
    val coveredMethods: List<MethodKey>,
    val assumptions: List<String>,
    val ruleIds: List<String>,
    val unresolvedGaps: List<CapabilityGap>,
)

/** One formal obligation sent to a checker, with enough identity to be replayed. */
@Serializable
data class ObligationRecord(
    val id: String,
    val description: String,
    val checker: String,
    val checkerVersion: String,
    val modelBounds: String,
    val queryHash: String,
    val status: CheckStatus,
    val reason: String? = null,
    val counterexample: Map<String, String>? = null,
    @Serializable(with = LongAsStringSerializer::class) val elapsedMs: Long = 0,
)

/** Result of one non-formal check (compile, runtime agreement, structural comparison). */
@Serializable
data class CheckRecord(
    val kind: String,
    val status: CheckStatus,
    val evidence: EvidenceKind?,
    val detail: String,
    val refs: List<String> = emptyList(),
)

@Serializable
data class ValidationReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val method: MethodKey,
    val candidateHash: String,
    val obligations: List<ObligationRecord>,
    val checks: List<CheckRecord>,
    val environmentHash: String,
    val counterexamples: List<Map<String, String>> = emptyList(),
) {
    /** Acceptance requires every recorded obligation and check to pass; NOT_RUN checks are listed, not counted. */
    val accepted: Boolean
        get() = obligations.isNotEmpty() &&
            obligations.all { it.status == CheckStatus.PASS } &&
            checks.none { it.status == CheckStatus.FAIL || it.status == CheckStatus.UNKNOWN }
}

/** Line in recovery/source-map.json: a generated source line mapped to DEX byte ranges and rules. */
@Serializable
data class SourceMapEntry(
    val file: String,
    val line: Int,
    val method: String,
    val byteRanges: List<ByteRange>,
    val ruleIds: List<String>,
)

@Serializable
data class MethodOutcome(
    val method: MethodKey,
    val status: String,
    val evidence: List<EvidenceKind>,
    val acceptedCandidateHash: String? = null,
    val candidatesTried: Int = 0,
    val structuralMatch: String? = null,
    val gaps: List<CapabilityGap> = emptyList(),
    val obligations: List<ObligationRecord> = emptyList(),
    val checks: List<CheckRecord> = emptyList(),
    val ruleIds: List<String> = emptyList(),
)

@Serializable
data class ExportManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val jobId: String,
    val policy: RecoveryPolicy,
    val profileId: String,
    val profileHash: String,
    val environmentHash: String,
    val inputs: List<ArtifactRef>,
    /** Denominator: every method counted as application code, including unsupported ones. */
    val methodsInScope: Int,
    val methodsAccepted: Int,
    val methodsUnresolved: Int,
    val methods: List<MethodOutcome>,
    val sourceFiles: List<String>,
    val buildCheck: CheckRecord,
    val policySatisfied: Boolean,
    val notes: List<String>,
)

/** Newline-delimited JSON record exchanged with workers and written to the job journal (section 22). */
@Serializable
data class WorkerEnvelope(
    val schemaVersion: Int = SCHEMA_VERSION,
    val jobId: String,
    val stage: JobState,
    val event: String,
    @Serializable(with = LongAsStringSerializer::class) val sequence: Long,
    val artifactRefs: List<ArtifactRef> = emptyList(),
    val status: CheckStatus,
    val diagnosticRefs: List<String> = emptyList(),
    val message: String? = null,
)
