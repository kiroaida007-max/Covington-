package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.AccessFlags
import dev.apkproject.dex.DexFile
import dev.apkproject.dex.DexMethod
import dev.apkproject.dex.IndexKind
import dev.apkproject.dex.InstructionDecoder

/** Keeps an inferred method name consistent across its original virtual dispatch family. */
internal object VirtualMethodNames {
    fun expand(dex: List<DexFile>, seeds: Map<String, Map<String, String>>): Map<String, Map<String, String>> {
        if (seeds.isEmpty()) return emptyMap()
        val declarations = dex.flatMap { it.methods() }.associateBy { key(it.owner, it.name + it.protoDescriptor) }
        val parents = dex.flatMap { d -> d.classDefs.map { c ->
            d.type(c.classIdx) to (listOfNotNull(c.superclassIdx.takeIf { it in d.typeIds.indices }?.let { d.type(it) }) + c.interfaces.map { d.type(it) })
        } }.toMap()
        fun virtual(m: DexMethod) = m.accessFlags and (AccessFlags.STATIC or AccessFlags.PRIVATE) == 0 && !m.name.startsWith('<')
        fun accessible(m: DexMethod, owner: String) = m.owner == owner || m.accessFlags and (AccessFlags.PUBLIC or AccessFlags.PROTECTED) != 0 || packageOf(m.owner) == packageOf(owner)
        val virtuals = declarations.filterValues(::virtual)
        val byOwner = virtuals.values.groupBy { it.owner }
        val ancestors = HashMap<String, Set<String>>()
        fun ancestry(owner: String): Set<String> = ancestors.getOrPut(owner) {
            val seen = linkedSetOf<String>()
            val pending = ArrayDeque<String>()
            pending.add(owner)
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (seen.add(next)) pending.addAll(parents[next].orEmpty())
            }
            seen
        }
        val roots = virtuals.keys.associateWith { it }.toMutableMap()
        fun root(k: String): String {
            var r = k
            while (roots.getValue(r) != r) r = roots.getValue(r)
            var p = k
            while (p != r) { val next = roots.getValue(p); roots[p] = r; p = next }
            return r
        }
        fun join(a: String, b: String) { val ar = root(a); val br = root(b); if (ar != br) roots[ar] = br }
        // A class can inherit the same method from two interfaces even without declaring it itself.
        val visible = parents.keys.associateWith { owner -> ancestry(owner).flatMap { byOwner[it].orEmpty() }.filter { accessible(it, owner) } }
        for (ms in visible.values) for (family in ms.groupBy { it.name + it.protoDescriptor }.values) {
            val first = family.first()
            family.drop(1).forEach { join(key(first.owner, first.name + first.protoDescriptor), key(it.owner, it.name + it.protoDescriptor)) }
        }
        // javac bridges connect generic/covariant overrides whose DEX prototypes differ.
        for ((k, m) in virtuals) {
            if (m.accessFlags and AccessFlags.BRIDGE == 0) continue
            val code = m.code ?: continue
            val insns = (InstructionDecoder.decode(m.dex, code) as? Outcome.Ok)?.value?.insns ?: continue
            val calls = insns.filter { it.op.index == IndexKind.METHOD && (it.op.mnemonic.startsWith("invoke-virtual") || it.op.mnemonic.startsWith("invoke-interface")) }
                .map { m.dex.methodDescriptor(it.index) }.distinct()
            val target = calls.singleOrNull() ?: continue
            if (target in virtuals && declarations.getValue(target).owner == m.owner) join(k, target)
        }
        val requested = seeds.flatMap { (owner, methods) -> methods.map { (sig, name) -> key(owner, sig) to name } }.toMap().filterKeys { it in declarations }
        val desired = requested.filterKeys { it in virtuals }.entries.groupBy { root(it.key) }.mapValues { (_, entries) -> entries.map { it.value }.distinct() }
        val proposals = requested.filterKeys { it !in virtuals }.toMutableMap()
        for ((k, m) in virtuals) desired[root(k)]?.singleOrNull()?.takeIf { it != m.name }?.let { proposals[k] = it }

        // Reject the whole family on a conflicting inference or a newly introduced overload/override.
        // Dropping only the subclass's mapping would silently change virtual dispatch.
        fun reject(keys: List<String>): Boolean {
            val blocked = keys.filter { it in proposals }.map { if (it in virtuals) root(it) else it }.toSet()
            if (blocked.isEmpty()) return false
            proposals.keys.removeAll { (if (it in virtuals) root(it) else it) in blocked }
            return true
        }
        var changed: Boolean
        fun javaSignature(m: DexMethod, name: String = m.name) = name + m.protoDescriptor.substringBefore(')') + ")"
        fun family(k: String) = if (k in virtuals) root(k) else k
        do {
            changed = false
            for (ms in declarations.values.groupBy { it.owner }.values) {
                val collisions = ms.groupBy { javaSignature(it, proposals[key(it.owner, it.name + it.protoDescriptor)] ?: it.name) }.values
                for (collision in collisions) {
                    val keys = collision.map { key(it.owner, it.name + it.protoDescriptor) }
                    if (keys.map(::family).distinct().size > 1 && reject(keys)) changed = true
                }
            }
            for (ms in visible.values) {
                // Return-only overloads can be R8's reuse of a name for unrelated methods. Without a
                // bridge connecting them we cannot infer one Java override family safely.
                val collisions = ms.groupBy { javaSignature(it) }.values +
                    ms.groupBy { javaSignature(it, proposals[key(it.owner, it.name + it.protoDescriptor)] ?: it.name) }.values
                for (collision in collisions) {
                    val keys = collision.map { key(it.owner, it.name + it.protoDescriptor) }
                    if (keys.map(::root).distinct().size > 1 && reject(keys)) changed = true
                }
            }
        } while (changed)

        val out = linkedMapOf<String, MutableMap<String, String>>()
        for ((k, name) in proposals) out.getOrPut(k.substringBefore("->")) { linkedMapOf() }[k.substringAfter("->")] = name
        // DEX invokes may use an inheriting receiver as owner. Keep aliases for those references too,
        // so the round trip can undo calls as well as declarations (including across DEX files).
        for ((owner, ms) in visible) for ((sig, inherited) in ms.groupBy { it.name + it.protoDescriptor }) {
            if (key(owner, sig) in declarations) continue
            val names = inherited.map { proposals[key(it.owner, sig)] ?: it.name }.distinct()
            names.singleOrNull()?.takeIf { it != inherited.first().name }?.let { out.getOrPut(owner) { linkedMapOf() }[sig] = it }
        }
        return out
    }

    private fun key(owner: String, signature: String) = "$owner->$signature"
    private fun packageOf(owner: String) = owner.substringBeforeLast('/', "")
}
