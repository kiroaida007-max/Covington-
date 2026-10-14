package dev.apkproject.desktop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class ReportLimitException(message: String) : java.io.IOException(message)

/** Bounds actual reads, including a file which grows after metadata was checked. */
internal class BoundedReportStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L
    private fun check(n: Int): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        if (n > 0) count += n
        if (count > maxBytes) throw ReportLimitException("report exceeds the loading limit")
        return n
    }
    override fun read(): Int = super.read().also { check(if (it < 0) 0 else 1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = check(`in`.read(b, off, minOf(len.toLong(), maxBytes - count + 1).toInt()))
}

/** Reject huge individual strings and recursive JSON before the streaming decoder allocates them. */
internal class JsonReportStream(input: InputStream) : FilterInputStream(input) {
    private var depth = 0
    private var quoted = false
    private var escaped = false
    private var stringBytes = 0
    private fun scan(value: Int): Int {
        if (value < 0) return value
        if (quoted) {
            if (++stringBytes > 1024 * 1024) throw ReportLimitException("JSON string limit")
            if (escaped) escaped = false
            else if (value == 92) escaped = true
            else if (value == 34) quoted = false
        } else when (value) {
            34 -> { quoted = true; stringBytes = 0 }
            91, 123 -> if (++depth > 128) throw ReportLimitException("JSON nesting limit")
            93, 125 -> depth--
        }
        return value
    }
    override fun read(): Int = scan(`in`.read())
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = `in`.read(b, off, len)
        for (i in off until off + n.coerceAtLeast(0)) scan(b[i].toInt() and 255)
        return n
    }
}

/** Missing is distinct from invalid, unreadable and withheld by a resource limit. */
internal class ReportReader(private val root: Path, private val totalBytes: Int = 32 * 1024 * 1024,
                            files: ProjectFiles? = null) {
    private val fileBoundary by lazy { files ?: ProjectFiles(root) }
    private val readRoot by lazy { if (files == null) fileBoundary.root else root.toAbsolutePath().normalize() }
    enum class State { LOADED, MISSING, INVALID, UNREADABLE, LIMIT_REACHED, STALE, BLOCKED }
    data class Status(val path: String, val state: State, val detail: String)
    val statuses = LinkedHashMap<String, Status>()
    var expectedHashes: Map<String, String> = emptyMap()
    private var admitted = 0
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun status(path: String, state: State, detail: String) { statuses[path] = Status(path, state, detail) }

    fun text(path: String, maxBytes: Int = 8 * 1024 * 1024): String? {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return try {
            val file = readRoot.resolve(path)
            val limit = minOf(maxBytes, totalBytes - admitted).coerceAtLeast(0)
            val bytes = fileBoundary.input(file).use { it.readNBytes(limit + 1) }
            if (bytes.size > limit) throw ReportLimitException("exceeds the report or combined loading limit")
            admitted += bytes.size
            val expected = expectedHashes[path]
            if (expected != null && dev.apkproject.contracts.Hashing.sha256(bytes) != expected) {
                status(path, State.STALE, "Changed since recovery; recorded analysis is unavailable")
                return null
            }
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            status(path, State.LOADED, "Loaded")
            text
        } catch (error: InterruptedIOException) { throw error }
        catch (error: java.nio.file.NoSuchFileException) { status(path, State.MISSING, "Not produced or unavailable"); null }
        catch (error: ProjectAccessException) { status(path, State.BLOCKED, error.message ?: "Project location blocked"); null }
        catch (error: ReportLimitException) { status(path, State.LIMIT_REACHED, "Too large to load; the exported file remains available"); null }
        catch (error: java.nio.charset.CharacterCodingException) { status(path, State.INVALID, "Invalid UTF-8"); null }
        catch (error: java.io.IOException) { status(path, State.UNREADABLE, "Cannot read (${error.javaClass.simpleName})"); null }
    }

    fun <T> read(path: String, serializer: KSerializer<T>): T? {
        val text = text(path) ?: return null
        return try {
            // Limit nesting before the decoder sees an input-controlled recursive structure.
            var depth = 0; var quoted = false; var escaped = false
            for (c in text) {
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> { if (++depth > 128) throw ReportLimitException("nesting limit") }; '}', ']' -> depth-- }
            }
            json.decodeFromString(serializer, text)
        } catch (error: ReportLimitException) { status(path, State.LIMIT_REACHED, "JSON nesting exceeds the loading limit"); null }
        catch (error: Exception) { status(path, State.INVALID, "Malformed or incompatible report (${error.javaClass.simpleName})"); null }
    }
}
