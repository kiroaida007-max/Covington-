package dev.apkproject.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KJson

/** Shared JSON settings for every versioned record. */
object Json {
    val pretty: KJson = KJson {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /** Single-line form for newline-delimited journals and worker envelopes. */
    val line: KJson = KJson {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    inline fun <reified T> encode(value: T): String = pretty.encodeToString(value)

    inline fun <reified T> encodeLine(value: T): String = line.encodeToString(value)

    inline fun <reified T> decode(text: String): T = pretty.decodeFromString(text)
}
