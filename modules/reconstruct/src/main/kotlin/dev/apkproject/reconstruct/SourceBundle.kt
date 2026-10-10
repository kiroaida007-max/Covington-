package dev.apkproject.reconstruct

import dev.apkproject.source.JClass
import dev.apkproject.source.JMethod
import dev.apkproject.source.JavaNames

/** Java-side naming and modifiers derived from observed DEX declarations. */
object JavaDeclarations {
    private const val PUBLIC = 0x1
    private const val PRIVATE = 0x2
    private const val PROTECTED = 0x4
    private const val STATIC = 0x8
    private const val FINAL = 0x10
    private const val STRICT = 0x800
    private const val ABSTRACT = 0x400

    fun methodModifiers(flags: Int): List<String> = buildList {
        if (flags and PUBLIC != 0) add("public")
        if (flags and PRIVATE != 0) add("private")
        if (flags and PROTECTED != 0) add("protected")
        if (flags and STATIC != 0) add("static")
        if (flags and FINAL != 0) add("final")
        if (flags and STRICT != 0) add("strictfp")
    }

    fun classModifiers(flags: Int): List<String> = buildList {
        if (flags and PUBLIC != 0) add("public")
        if (flags and FINAL != 0) add("final")
        if (flags and ABSTRACT != 0) add("abstract")
    }

    /** `Lcom/example/Foo;` -> ("com.example", "Foo"); null when the names are not legal Java. */
    fun splitClass(descriptor: String): Pair<String?, String>? {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null
        val parts = descriptor.substring(1, descriptor.length - 1).split('/')
        if (parts.any { !JavaNames.isLegalIdentifier(it) }) return null
        val pkg = parts.dropLast(1).joinToString(".").ifEmpty { null }
        return pkg to parts.last()
    }

    fun binaryName(descriptor: String): String = descriptor.substring(1, descriptor.length - 1).replace('/', '.')
}

object SourceBundle {
    /** One Java compilation unit per DEX class holding the given recovered methods. */
    fun classFor(descriptor: String, classFlags: Int, methods: List<JMethod>, header: List<String>): JClass {
        val (pkg, simple) = JavaDeclarations.splitClass(descriptor) ?: error("illegal class name $descriptor")
        return JClass(pkg, simple, JavaDeclarations.classModifiers(classFlags), methods, header)
    }
}
