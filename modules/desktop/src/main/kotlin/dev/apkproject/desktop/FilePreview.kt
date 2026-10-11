package dev.apkproject.desktop

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal object FilePreview {
    data class Content(val text: String, val sha256: String? = null)
    const val MAX_BYTES = 4 * 1024 * 1024
    private val binary = setOf("png", "webp", "jpg", "jpeg", "gif", "ttf", "otf", "so", "dex", "jar")

    fun read(path: Path): String = content(path).text

    fun content(path: Path): Content {
        val size = Files.size(path)
        if (path.fileName.toString().substringAfterLast('.', "").lowercase() in binary)
            return Content("(binary file, ${Ui.human(size)}; use Open to view it)")
        if (size > MAX_BYTES) return Content("(file too large to preview, ${Ui.human(size)}; use Open to view it)")
        // Bound the actual read too: files can grow after the folder's metadata was gathered.
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) return Content("(file too large to preview; use Open to view it)")
        return Content(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(), dev.apkproject.contracts.Hashing.sha256(bytes))
    }
}
