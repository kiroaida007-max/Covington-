package dev.apkproject.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

/** Reproduce the packaging artwork from the window icon. ICO entries contain PNG images. */
object DesktopIcons {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: DesktopIcons PACKAGING_DIR" }
        val folder = Files.createDirectories(Paths.get(args[0]))
        ImageIO.write(AppIcon.image(512), "png", folder.resolve("icon.png").toFile())
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val images = sizes.map { size -> ByteArrayOutputStream().also { ImageIO.write(AppIcon.image(size), "png", it) }.toByteArray() }
        var offset = 6 + 16 * sizes.size
        val header = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(sizes.size.toShort())
        sizes.zip(images).forEach { (size, bytes) ->
            header.put(if (size == 256) 0 else size.toByte()).put(if (size == 256) 0 else size.toByte()).put(0).put(0)
            header.putShort(1).putShort(32).putInt(bytes.size).putInt(offset)
            offset += bytes.size
        }
        Files.newOutputStream(folder.resolve("icon.ico")).use { stream -> stream.write(header.array()); images.forEach { stream.write(it) } }
    }
}
