package dev.apkproject.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer

/**
 * Typed parse failure (section 08): every parser reports the artifact hash, byte offset,
 * format section and reason instead of returning null.
 */
@Serializable
data class FormatFailure(
    val artifactSha256: String,
    @Serializable(with = LongAsStringSerializer::class) val offset: Long,
    val section: String,
    val reason: String,
    /** True when the input is well formed but uses a construct we do not support. */
    val unsupported: Boolean = false,
) {
    override fun toString(): String =
        (if (unsupported) "unsupported" else "malformed") + " $section @0x${offset.toString(16)}: $reason"
}

class FormatException(val failure: FormatFailure) : Exception(failure.toString())

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val failure: FormatFailure) : Outcome<Nothing>

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw FormatException(failure)
    }

    companion object {
        inline fun <T> of(block: () -> T): Outcome<T> = try {
            Ok(block())
        } catch (e: FormatException) {
            Err(e.failure)
        }
    }
}
