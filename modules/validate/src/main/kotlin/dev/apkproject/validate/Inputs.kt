package dev.apkproject.validate

import dev.apkproject.ir.ConstBits
import dev.apkproject.ir.MethodIr
import java.util.Random

/**
 * Deterministic test inputs: signed boundaries, shift distances, constants that appear in the
 * method (and their neighbours), then seeded random values.
 */
object Inputs {
    val EDGES = intArrayOf(
        0, 1, -1, 2, -2, 3, 7, 8, 15, 16, 31, 32, 33, 63, 64, 127, 128, -128, -129, 255, 256,
        32767, 32768, -32768, -32769, 65535, 65536, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE - 1, Int.MIN_VALUE + 1,
    )

    fun generate(ir: MethodIr, randomCount: Int, seed: Long = 20261009L, extra: List<IntArray> = emptyList()): List<IntArray> {
        val n = ir.signature.params.size
        if (n == 0) return listOf(IntArray(0))
        val constants = ir.blocks.flatMap { it.ops }.filterIsInstance<ConstBits>().map { it.int }
            .flatMap { listOf(it - 1, it, it + 1) }
        val pool = (EDGES.toList() + constants).distinct()
        val out = LinkedHashSet<List<Int>>()
        extra.forEach { out += it.toList() }
        when (n) {
            1 -> pool.forEach { out += listOf(it) }
            2 -> {
                val small = pool.take(24)
                for (a in small) for (b in small) out += listOf(a, b)
            }
            else -> {
                val rnd = Random(seed xor 0x5eed)
                repeat(400) { out += List(n) { pool[rnd.nextInt(pool.size)] } }
            }
        }
        val rnd = Random(seed)
        repeat(randomCount) { out += List(n) { rnd.nextInt() } }
        return out.map { it.toIntArray() }
    }
}
