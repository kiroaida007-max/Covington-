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

    fun content(path: Path, files: ProjectFiles = ProjectFiles(path.toAbsolutePath().parent), maxBytes: Int = MAX_BYTES): Content {
        require(maxBytes in 1..MAX_BYTES)
        val size = files.attributes(path).also {
            if (!it.isRegularFile) throw ProjectAccessException("Only regular project files can be previewed")
        }.size()
        if (path.fileName.toString().substringAfterLast('.', "").lowercase() in binary)
            return Content("(binary file, ${Ui.human(size)}; no text preview)")
        if (size > maxBytes) return Content("(file too large to preview, ${Ui.human(size)}; inspect the exported file separately)")
        // Bound the actual read too: files can grow after the folder's metadata was gathered.
        val bytes = files.input(path).use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) return Content("(file too large to preview; inspect the exported file separately)")
        return Content(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(), dev.apkproject.contracts.Hashing.sha256(bytes))
    }
}
