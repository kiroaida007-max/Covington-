package dev.apkproject.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The privacy API classifier (trained by `scripts/ml/train_api_classifier.py`): from a platform
 * method's signature alone, whether it returns personal data (a source, by data kind) or sends data
 * out (a sink, by kind), with a probability. The privacy map consults it only for platform methods its
 * own tables do not name, and labels what it finds as model-based.
 *
 * The model is multinomial logistic regression over hashed signature tokens, stored as int8 weights
 * with one scale per class (`privacy-api-model.bin`, 0.5 MB). Tokens and arithmetic here mirror the
 * training script exactly; a golden test checks the probabilities. Training data and accuracy are in
 * `privacy-api-model.json` and the model card (`scripts/ml/README.md`).
 */
object PrivacyApiModel {
    class Prediction(val label: String, val probability: Double)

    private class Model(val dims: Int, val labels: List<String>, val scale: DoubleArray, val bias: DoubleArray, val weights: ByteArray)

    private val model: Model? by lazy { load() }

    /** Whether the model file is present (it ships with the module). */
    val available: Boolean get() = model != null

    /** Reads the model from its bundled resource, or null when missing or in an unknown format. */
    private fun load(): Model? {
        val bytes = PrivacyApiModel::class.java.getResourceAsStream("/dev/apkproject/app/privacy-api-model.bin")?.use { it.readBytes() } ?: return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        if (String(magic, Charsets.US_ASCII) != "APSM" || b.short.toInt() != 1) return null
        val dims = b.int
        val k = b.short.toInt()
        val labels = List(k) { ByteArray(b.get().toInt() and 0xff).also { a -> b.get(a) }.toString(Charsets.UTF_8) }
        val scale = DoubleArray(k) { b.float.toDouble() }
        val bias = DoubleArray(k) { b.float.toDouble() }
        val weights = ByteArray(dims * k).also { b.get(it) }
        return Model(dims, labels, scale, bias, weights)
    }

    /**
     * The most likely class of a method: [cls], [ret] and [params] are Java names
     * (`android.location.LocationManager`, `java.lang.String[]`, `int`).
     */
    fun predict(cls: String, ret: String, name: String, params: List<String>): Prediction? {
        val m = model ?: return null
        val k = m.labels.size
        val sums = LongArray(k)
        for (idx in hashed(features(cls, ret, name, params), m.dims)) {
            val base = idx * k
            for (c in 0 until k) sums[c] += m.weights[base + c].toLong()
        }
        val z = DoubleArray(k) { c -> sums[c].toDouble() * m.scale[c] + m.bias[c] }
        val max = z.max()
        var total = 0.0
        for (c in 0 until k) { z[c] = Math.exp(z[c] - max); total += z[c] }
        var best = 0
        for (c in 1 until k) if (z[c] > z[best]) best = c
        return Prediction(m.labels[best], z[best] / total)
    }

    /** [predict] for a DEX method reference (`Landroid/location/LocationManager;->getLastKnownLocation(Ljava/lang/String;)Landroid/location/Location;`). */
    fun predict(ref: String): Prediction? {
        val owner = ref.substringBefore("->")
        val sig = ref.substringAfter("->")
        val name = sig.substringBefore('(')
        val params = splitDescriptors(sig.substringAfter('(').substringBefore(')')).map(::javaName)
        return predict(javaName(owner), javaName(sig.substringAfter(')')), name, params)
    }

    // ---- features (mirror of the training script) ----------------------------------------------------

    private val CAMEL = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+""")
    private val PRIMITIVES = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")

    /** Splits a camelCase or PascalCase identifier into lowercase words. */
    internal fun camel(s: String): List<String> = CAMEL.findAll(s).map { it.value.lowercase() }.toList()

    /** The unqualified, unnested class name, array brackets dropped. */
    private fun simple(t: String) = t.substringAfterLast('.').substringAfterLast('$').replace("[]", "")

    /** The hashed feature tokens for a method signature, mirroring the training script exactly. */
    internal fun features(cls: String, ret: String, name: String, params: List<String>): List<String> {
        val out = sortedSetOf<String>()
        val pkg = if ('.' in cls) cls.substringBeforeLast('.') else ""
        val cs = simple(cls).lowercase()
        pkg.split('.').take(4).forEach { out += "p:$it" }
        out += "P:$pkg"
        out += "C:$cs"
        camel(simple(cls)).forEach { out += "c:$it" }
        val words = camel(name).ifEmpty { listOf(name.lowercase()) }
        val verb = words[0]
        out += "M:${name.lowercase()}"
        out += "v:$verb"
        words.forEach { out += "m:$it" }
        val rs = simple(ret).lowercase()
        out += "r:$rs"
        camel(simple(ret)).forEach { out += "rt:$it" }
        val kind = when {
            ret == "void" -> "void"
            ret.endsWith("[]") -> "array"
            ret in PRIMITIVES -> "prim"
            ret == "java.lang.String" -> "string"
            else -> "object"
        }
        out += "R:$kind"
        params.forEach { out += "a:${simple(it).lowercase()}" }
        out += "n:${minOf(params.size, 4)}"
        out += "vC:$verb|$cs"
        out += "vr:$verb|$rs"
        out += "Cm:$cs|${name.lowercase()}"
        out += "vk:$verb|$kind"
        return out.toList()
    }

    /** FNV-1a hash of [s], as used by the training script to bucket feature tokens. */
    internal fun fnv1a(s: String): Long {
        var h = 0x811C9DC5L
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }

    /** Feature tokens hashed into `dims` buckets, deduplicated and sorted. */
    private fun hashed(tokens: List<String>, dims: Int): IntArray = tokens.map { (fnv1a(it) % dims).toInt() }.distinct().sorted().toIntArray()

    /** `Ljava/lang/String;` -> java.lang.String, `[B` -> byte[], `I` -> int. */
    internal fun javaName(d: String): String {
        var dims = 0
        var t = d
        while (t.startsWith("[")) { dims++; t = t.substring(1) }
        val base = when (t) {
            "Z" -> "boolean"; "B" -> "byte"; "C" -> "char"; "S" -> "short"; "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"; "V" -> "void"
            else -> t.removePrefix("L").removeSuffix(";").replace('/', '.')
        }
        return base + "[]".repeat(dims)
    }

    /** Splits a JVM parameter descriptor string (`Ljava/lang/String;I[B`) into its individual types. */
    private fun splitDescriptors(params: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < params.length) {
            var j = i
            while (params[j] == '[') j++
            if (params[j] == 'L') j = params.indexOf(';', j)
            out += params.substring(i, j + 1)
            i = j + 1
        }
        return out
    }
}
