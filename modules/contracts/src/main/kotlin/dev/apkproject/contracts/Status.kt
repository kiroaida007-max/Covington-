package dev.apkproject.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class CheckStatus { PASS, FAIL, UNKNOWN, NOT_RUN }

/**
 * Evidence levels (dossier section 02):
 * OBSERVED = recorded directly from an artifact; INFERRED = a candidate explanation;
 * PROVED = an obligation passed within a stated formal model; TESTED = agreement on recorded executions.
 */
@Serializable
enum class EvidenceKind { OBSERVED, INFERRED, PROVED, TESTED }

/** Job state machine from section 07. */
@Serializable
enum class JobState {
    CREATED, INGESTED, DECODED, MODELED, CANDIDATE, COMPILED, REFINING, ACCEPTED,
    EXPORTED, PARTIAL, FAILED, CANCELLED;

    val terminal: Boolean get() = this in setOf(EXPORTED, PARTIAL, FAILED, CANCELLED)

    fun canMoveTo(next: JobState): Boolean = next in (TRANSITIONS[this] ?: emptySet())

    companion object {
        private val TRANSITIONS: Map<JobState, Set<JobState>> = mapOf(
            CREATED to setOf(INGESTED, FAILED, CANCELLED),
            INGESTED to setOf(DECODED, PARTIAL, FAILED, CANCELLED),
            DECODED to setOf(MODELED, PARTIAL, FAILED, CANCELLED),
            MODELED to setOf(CANDIDATE, PARTIAL, FAILED, CANCELLED),
            CANDIDATE to setOf(COMPILED, REFINING, FAILED, CANCELLED),
            COMPILED to setOf(ACCEPTED, REFINING, PARTIAL, FAILED, CANCELLED),
            REFINING to setOf(CANDIDATE, PARTIAL, FAILED, CANCELLED),
            ACCEPTED to setOf(EXPORTED, PARTIAL, FAILED, CANCELLED),
        )
    }
}

/** Application exit codes (section 07). These are our codes, not SDK exit codes. */
enum class ExitCode(val code: Int) {
    EXPORT_POLICY_SATISFIED(0),
    INVALID_CONFIGURATION(2),
    MALFORMED_INPUT(3),
    UNSUPPORTED_OR_PARTIAL(4),
    BUILD_OR_VERIFICATION_FAILURE(5),
    WORKER_OR_RESOURCE_FAILURE(6),
    CANCELLED(130),
}

@Serializable
enum class RecoveryPolicy {
    /** Every in-scope method must become accepted source; anything less is PARTIAL. */
    FULL_SOURCE,

    /** Accepted methods are exported; unaccepted ones are listed, never stubbed. */
    PARTIAL_REPORT,
}
