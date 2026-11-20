// SPDX-License-Identifier: Apache-2.0
package jps

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Hand-built class, jar and plugin-archive fixtures; generated in-test with ASM, no network, no class loading. */
internal object Fixtures {
    class MethodSpec(
        val name: String,
        val descriptor: String,
        val annotations: List<String> = emptyList(),
        val body: (MethodVisitor) -> Unit = { it.visitInsn(Opcodes.RETURN) },
        val access: Int = Opcodes.ACC_PUBLIC,
        val signature: String? = null,
        val paramNames: List<String> = emptyList(),
    )

    /** An annotation with simple values: String, Boolean, Double or List of String. */
    class AnnSpec(val descriptor: String, val values: Map<String, Any> = emptyMap())

    class FieldSpec(
        val name: String,
        val descriptor: String,
        val annotations: List<String> = emptyList(),
        val signature: String? = null,
        val access: Int = Opcodes.ACC_PRIVATE,
    )

    fun classBytes(
        name: String,
        superName: String = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        annotations: List<String> = emptyList(),
        methods: List<MethodSpec> = emptyList(),
        fields: List<FieldSpec> = emptyList(),
        access: Int = Opcodes.ACC_PUBLIC,
        annotationSpecs: List<AnnSpec> = emptyList(),
        innerOf: String? = null,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, access or Opcodes.ACC_SUPER, name, null, superName, interfaces.toTypedArray())
        if (innerOf != null) writer.visitInnerClass(name, innerOf, name.substringAfterLast('$'), Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        annotations.forEach { writer.visitAnnotation(it, true).visitEnd() }
        for (spec in annotationSpecs) {
            val av = writer.visitAnnotation(spec.descriptor, true)
            for ((key, value) in spec.values) {
                when (value) {
                    is List<*> -> {
                        val arr = av.visitArray(key)
                        value.forEach { arr.visit(null, it) }
                        arr.visitEnd()
                    }
                    else -> av.visit(key, value)
                }
            }
            av.visitEnd()
        }
        for (f in fields) {
            val fv = writer.visitField(f.access, f.name, f.descriptor, f.signature, null)
            f.annotations.forEach { fv.visitAnnotation(it, true).visitEnd() }
            fv.visitEnd()
        }
        for (m in methods) {
            val mv = writer.visitMethod(m.access, m.name, m.descriptor, m.signature, null)
            m.paramNames.forEach { mv.visitParameter(it, 0) }
            m.annotations.forEach { mv.visitAnnotation(it, true).visitEnd() }
            mv.visitCode()
            m.body(mv)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in entries.keys.sorted()) {
                val entry = ZipEntry(name)
                entry.time = 0
                zip.putNextEntry(entry)
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun manifest(vararg pairs: Pair<String, String>): ByteArray =
        (listOf("Manifest-Version: 1.0") + pairs.map { "${it.first}: ${it.second}" }).joinToString("\r\n", postfix = "\r\n\r\n").toByteArray()

    /** A plugin archive with one jar under WEB-INF/lib. */
    fun hpi(
        shortName: String,
        version: String,
        jarEntries: Map<String, ByteArray>,
        extra: Array<Pair<String, String>> = emptyArray(),
    ): ByteArray =
        zip(
            mapOf(
                "META-INF/MANIFEST.MF" to manifest("Short-Name" to shortName, "Plugin-Version" to version, "Jenkins-Version" to "2.400", *extra),
                "WEB-INF/lib/$shortName.jar" to zip(jarEntries),
            ),
        )

    fun extensionIndex(vararg classes: String, annotation: String = "hudson.Extension"): Map<String, ByteArray> =
        mapOf(
            "META-INF/annotations/$annotation" to byteArrayOf(0),
            "META-INF/annotations/$annotation.txt" to ("# informational\n" + classes.joinToString("\n") { "$it{}" } + "\n").toByteArray(),
        )

    fun write(dir: Path, name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }

    const val EXT: String = "Lhudson/Extension;"
}
