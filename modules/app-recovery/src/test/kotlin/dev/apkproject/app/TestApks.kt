package dev.apkproject.app

import dev.apkproject.contracts.Outcome
import dev.apkproject.dex.DexFile
import dev.apkproject.dexasm.Assembler
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small archives built in memory, so input handling is tested without real APKs. */
object TestApks {
    fun dex(className: String = "Lcom/example/Main;", body: String = "add-int v0, v2, v3\nreturn v0"): ByteArray = Assembler.assemble(
        ".class public $className\n.super Ljava/lang/Object;\n.method public static f(II)I\n.registers 4\n$body\n.end method\n",
    )

    fun dexFile(source: String): DexFile = (DexFile.parse(Assembler.assemble(source)) as Outcome.Ok).value

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name).apply { time = 315_532_800_000L })
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun write(dir: Path, name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }
}
