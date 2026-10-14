package dev.apkproject.desktop

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal class ProjectAccessException(message: String) : IOException(message)

/** Read boundary for an exported project. Links below the selected root are never followed. */
internal class ProjectFiles(selectedRoot: Path) {
    val root: Path = selectedRoot.toRealPath()
    private val rootKey = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    fun checked(path: Path): Path {
        val candidate = path.toAbsolutePath().normalize()
        if (!candidate.startsWith(root)) throw ProjectAccessException("Location is outside this project")
        val currentRoot = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!currentRoot.isDirectory || root.toRealPath() != root ||
            rootKey != null && currentRoot.fileKey() != rootKey)
            throw ProjectAccessException("Project folder was replaced; reopen it before reading")
        var current = root
        for (part in root.relativize(candidate)) {
            if (part.toString().isEmpty()) continue
            current = current.resolve(part)
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || current.toRealPath() != current)
                throw ProjectAccessException("Linked project locations are blocked")
        }
        return candidate
    }

    fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(checked(path), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    fun directory(path: Path): Path = checked(path).also {
        if (!attributes(it).isDirectory) throw ProjectAccessException("Location is not a project folder")
    }

    /** Secure directory handles also prevent parent-link replacement during reads on supporting OSes. */
    fun input(path: Path): InputStream {
        val file = checked(path)
        if (!attributes(file).isRegularFile) throw ProjectAccessException("Only regular project files can be read")
        val opened = ArrayList<java.nio.file.DirectoryStream<Path>>()
        try {
            val first = Files.newDirectoryStream(root).also { opened.add(it) }
            if (first !is SecureDirectoryStream<Path>) {
                first.close(); opened.clear()
                // Windows has no SecureDirectoryStream: validate reparse targets and open without following a final link.
                checked(file)
                return Channels.newInputStream(Files.newByteChannel(file, setOf(READ, NOFOLLOW_LINKS)))
            }
            if (rootKey != null && first.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey() != rootKey)
                throw ProjectAccessException("Project folder was replaced during the read")
            var parent: SecureDirectoryStream<Path> = first
            val parts = root.relativize(file).toList()
            for (part in parts.dropLast(1)) {
                parent = parent.newDirectoryStream(part, NOFOLLOW_LINKS).also { opened.add(it) }
            }
            val name = parts.last()
            val attributes = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            if (!attributes.isRegularFile) throw ProjectAccessException("Only regular project files can be read")
            val stream = Channels.newInputStream(parent.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)))
            return object : FilterInputStream(stream) {
                override fun close() {
                    try { super.close() } finally { opened.asReversed().forEach { runCatching { it.close() } } }
                }
            }
        } catch (error: Exception) {
            opened.asReversed().forEach { runCatching { it.close() } }
            throw error
        }
    }

    /** Runtime output is explicit work, but existing parent links are still outside the boundary. */
    fun outputDirectory(path: Path): Path {
        val candidate = path.toAbsolutePath().normalize()
        if (!candidate.startsWith(root)) throw ProjectAccessException("Output is outside this project")
        var parent = candidate
        while (!Files.exists(parent, NOFOLLOW_LINKS) && parent != root) parent = parent.parent
        directory(parent)
        Files.createDirectories(candidate)
        return directory(candidate)
    }
}
