package dev.apkproject.contracts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractsTest {
    @Test
    fun `64-bit integers serialize as strings`() {
        val ref = ArtifactRef("ab".repeat(32), "classes.dex", Long.MAX_VALUE, "application/vnd.android.dex")
        val json = Json.encodeLine(ref)
        assertTrue(json.contains("\"byteSize\":\"9223372036854775807\""), json)
        assertEquals(ref, Json.decode<ArtifactRef>(json))
    }

    @Test
    fun `job state machine only allows declared transitions`() {
        assertTrue(JobState.CREATED.canMoveTo(JobState.INGESTED))
        assertTrue(JobState.COMPILED.canMoveTo(JobState.REFINING))
        assertTrue(JobState.REFINING.canMoveTo(JobState.CANDIDATE))
        assertFalse(JobState.CREATED.canMoveTo(JobState.ACCEPTED))
        assertFalse(JobState.EXPORTED.canMoveTo(JobState.CREATED))
        assertTrue(JobState.PARTIAL.terminal)
    }

    @Test
    fun `validation report without obligations is not accepted`() {
        val key = MethodKey("00".repeat(32), "LA;", "f", "(I)I")
        val empty = ValidationReport(method = key, candidateHash = "h", obligations = emptyList(), checks = emptyList(), environmentHash = "e")
        assertFalse(empty.accepted)
        val passed = empty.copy(
            obligations = listOf(ObligationRecord("o", "d", "z3", "4", "BV32", "q", CheckStatus.PASS)),
            checks = listOf(CheckRecord("runtime", CheckStatus.NOT_RUN, null, "no device")),
        )
        assertTrue(passed.accepted)
        val unknown = passed.copy(checks = listOf(CheckRecord("runtime", CheckStatus.UNKNOWN, null, "timeout")))
        assertFalse(unknown.accepted)
    }
}
