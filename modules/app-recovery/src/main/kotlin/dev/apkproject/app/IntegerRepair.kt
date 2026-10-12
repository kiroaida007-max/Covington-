package dev.apkproject.app

import dev.apkproject.contracts.Hashing
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.AccessFlags
import dev.apkproject.lift.DexLifter
import dev.apkproject.reconstruct.ReconstructOptions
import dev.apkproject.reconstruct.Reconstructor
import dev.apkproject.source.JavaNames
import dev.apkproject.source.JavaPrinter
import kotlinx.serialization.Serializable

@Serializable
data class IntegerRepairOrigin(val method: String, val originalDexSha256: String, val bodySha256: String,
    val rules: List<String>, val evidence: String = "INFERRED_RECONSTRUCTED",
    val scope: String = "Bounded V0 integer reconstruction; acceptance requires complete compiler/bytecode comparison. No solver proof is claimed.")

/** An additional proposal source, never an acceptance authority or an approximation of unsupported DEX. */
object IntegerRepair {
    fun eligible(original: DexMethod): Boolean = original.isStatic &&
        original.accessFlags and (AccessFlags.SYNCHRONIZED or AccessFlags.DECLARED_SYNCHRONIZED) == 0 &&
        original.code != null && original.code!!.insns.size <= 192 &&
        original.proto.parameters.size <= 8 && original.dex.type(original.proto.returnType) == "I" &&
        original.proto.parameters.all { original.dex.type(it) == "I" }

    fun candidate(file: String, source: String, sourceKey: String, original: DexMethod): RepairCandidate? = runCatching {
        if (!eligible(original)) return null
        val name = sourceKey.substringAfter(";->").substringBefore('(')
        val found = (JavaMethodLocator.find(source, name, original.proto.parameters.size) as? JavaMethodLocator.Result.Found)?.found ?: return null
        val declaration = found.text.substringBefore('{')
        if (Regex("\\bsynchronized\\b").containsMatchIn(JavaMethodLocator.codeOnly(declaration)) ||
            Regex("\\\\u+[0-9a-fA-F]{4}").containsMatchIn(declaration)) return null
        val signature = Regex("\\bint\\s+${Regex.escape(name)}\\s*\\(([^()]*)\\)").find(JavaMethodLocator.codeOnly(found.text)) ?: return null
        val parameters = signature.groupValues[1].trim().let { if (it.isEmpty()) emptyList() else it.split(',') }
            .map { parameter -> Regex("^\\s*(?:final\\s+)?int\\s+(\\w+)\\s*$").matchEntire(parameter)?.groupValues?.get(1) ?: return null }
        if (parameters.distinct().size != parameters.size || parameters.any { !JavaNames.isLegalIdentifier(it) }) return null
        val ir = DexLifter.lift(original).ir ?: return null
        if (ir.blocks.size > 32) return null
        val reconstructed = Reconstructor.reconstruct(ir.copy(parameterNames = parameters), name, listOf("static"), ReconstructOptions(maxStatements = 128))
        val printed = JavaPrinter.method(reconstructed.method).trimEnd()
        val body = printed.substring(printed.indexOf('{')).lines().mapIndexed { index, line -> if (index == 0) line else found.indent + line }.joinToString("\n")
        val region = AssistantPatch.bodyRegion(source, sourceKey) ?: return null
        if (body.length > 8192 || region.second - region.first > 8192) return null
        val before = source.substring(region.first, region.second)
        if (RepairLearning.sourceFingerprint(before) == RepairLearning.sourceFingerprint(body)) return null
        RepairCandidate(file, "integer-ir", source.take(region.first).count { it == '\n' } + 1, before, body,
            source.substring(0, region.first) + body + source.substring(region.second),
            reconstruction = IntegerRepairOrigin(original.key.display(), original.dex.sha256, Hashing.sha256(body),
                reconstructed.rules.map { it.ruleId }.distinct().take(128)))
    }.getOrNull()
}
