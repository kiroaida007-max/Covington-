package dev.apkproject.app

import dev.apkproject.contracts.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LocalRepairAssistantTest {
    @TempDir lateinit var dir: Path
    private val source = """
        package p;
        public class C {
            public static int f(int value, int shift) { return value >> shift; }
            public static int protectedMethod() { return 7; }
        }
    """.trimIndent()
    private val key = "Lp/C;->f(II)I"

    @Test fun `model bodies cannot edit another method or escape into class declarations`() {
        val reply = """{"body":"{ return value >>> shift; }"}"""
        val candidate = assertNotNull(AssistantPatch.candidate("p/C.java", source, key, reply))
        assertContains(candidate.text, "protectedMethod() { return 7; }")
        assertContains(candidate.text, "return value >>> shift;")
        assertNotNull(AssistantPatch.candidate("p/C.java", source, key, """{"body":"public static int f(int value, int shift) { return value >>> shift; }"}"""))
        assertNull(AssistantPatch.candidate("p/C.java", source, key, """{"body":"public static int changed(int value, int shift) { return value >>> shift; }"}"""))
        for (invalid in listOf("""{"body":"{ return 0; } public static int stolen() { return 1; }"}""",
            """{"body":"{ class Hidden {} return 1; }"}""", """{"body":"{ return 0; \\u007d static int stolen() \\u007b return 1; }"}""",
            """{"body":"{ return 1; }","path":"outside.java"}""", """{"abstain":true,"body":"{ return 1; }"}""",
            "```json\n$reply\n```", reply + " another response")) {
            assertNull(AssistantPatch.candidate("p/C.java", source, key, invalid), invalid)
        }
        assertNull(AssistantPatch.candidate("p/C.java", source, key, """{"abstain":true}"""))
        assertNull(AssistantPatch.candidate("p/C.java", source, "Lp/C;->missing()I", reply))
        assertNull(AssistantPatch.candidate("p/C.java", source + "\nclass Other {\n int f(int x) { return x; }\n}", key, reply))
    }

    @Test fun `prompt encoding neutralizes chat control tokens without obscuring Java operators`() {
        val data = AssistantPatch.promptData("{\"java\":\"return value >>> shift; // <|im_start|>\"}")
        assertContains(data, ">>>")
        assertFalse("<|im_start|>" in data)
        assertContains(data, "\\u003c|im_start|>")
    }

    @Test fun `compilation restoration needs the complete original method set and does not invent a pairwise label`() {
        val missing = MethodRoundTrip("Lp/C;->f(II)I", "NOT_COMPILED", "p/C.java")
        val protected = MethodRoundTrip("Lp/C;->protectedMethod()I", "EQUIVALENT_DATAFLOW", "p/C.java")
        val restored = missing.copy(result = "EQUIVALENT_MODULO_LAYOUT")
        assertTrue(RepairAcceptance.completeOriginalRecovery(listOf(missing, protected), listOf(restored, protected)))
        assertNull(RepairLearning.checkedLabel(listOf(missing, protected), listOf(restored, protected)))
        assertFalse(RepairAcceptance.completeOriginalRecovery(listOf(missing, protected), listOf(restored)))
        assertFalse(RepairAcceptance.completeOriginalRecovery(listOf(missing, protected), listOf(restored, protected.copy(result = "DIFFERENT"))))
        assertFalse(RepairAcceptance.completeOriginalRecovery(listOf(missing, missing), listOf(restored)))
    }

    @Test fun `retrieval keeps held out source units out and reference advice cannot pretend to be checked data`() {
        val knowledge = RepairKnowledge.decode(RepairKnowledge.builtInBytes())
        val units = knowledge.entries.mapNotNull { it.unitSha256 }.toSet()
        val onlyGuides = RepairRetriever(knowledge).retrieve("float division cast long const-wide signed shift", units)
        assertTrue(onlyGuides.all { it.evidence == "REFERENCE_GUIDANCE" })
        val cast = assertNotNull(RepairRetriever(knowledge).retrieve("reference generics cast null").firstOrNull())
        assertEquals("COMPLETE_ROUNDTRIP_NO_REGRESSION", cast.evidence)
        assertFailsWith<IllegalArgumentException> { RepairKnowledge(entries = listOf(cast.copy(queryCodeSha256 = null))).checked() }
        assertFailsWith<IllegalArgumentException> { RepairKnowledge(entries = listOf(cast.copy(evidence = "MODEL_GUESS"))).checked() }
        assertFailsWith<IllegalArgumentException> { RepairKnowledge(entries = listOf(cast, cast)).checked() }
    }

    @Test fun `invalid unregistered and oversized model configurations fall back without native execution`() {
        val config = dir.resolve("assistant.json")
        val invalid = LocalAssistantConfig(modelId = "unknown", modelFile = "missing.gguf", runtimeExecutable = "missing",
            runtimeFiles = listOf(AssistantPinnedFile("missing", "0".repeat(64))))
        Files.writeString(config, Json.encode(invalid))
        assertNull(LocalRepairAssistant.prepare(config, dir.resolve("stage")))
        Files.writeString(config, " ".repeat(65_537))
        assertNull(LocalRepairAssistant.prepare(config, dir.resolve("stage")))
        assertNull(LocalRepairAssistant.prepare(null, dir.resolve("stage")))
    }
}
