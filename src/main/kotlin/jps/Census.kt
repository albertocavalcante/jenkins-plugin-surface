// SPDX-License-Identifier: Apache-2.0
package jps

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal const val EXTENSION_ANNOTATION: String = "hudson.Extension"
internal const val OPTIONAL_EXTENSION_ANNOTATION: String = "org.jenkinsci.plugins.variant.OptionalExtension"
private val EXTENSION_ANNOTATIONS: List<String> = listOf(EXTENSION_ANNOTATION, OPTIONAL_EXTENSION_ANNOTATION)

private const val SEZPOZ_PREFIX: String = "META-INF/annotations/"
private const val INDEXER_PREFIX: String = "META-INF/services/annotations/"

/** An indexed extension: a dotted class name, optionally `Class#member` for factory methods and fields. */
internal data class ExtensionRef(val key: String, val annotations: Set<String>, val container: String, val indexed: Boolean = true) {
    val className: String get() = key.substringBefore('#')
    val isMember: Boolean get() = '#' in key
}

internal class CensusResult(val jars: List<JarCensus>, val extensions: List<ExtensionRef>)

private fun descriptorOf(annotation: String): String = "L" + annotation.replace('.', '/') + ";"

/** Annotated element keys found by the bytecode scan, per annotation descriptor. */
private class ScanResult {
    /** SezPoz shaped keys: `Class`, `Class#method()`, `Class#field`. */
    val elements: MutableMap<String, MutableSet<String>> = hashMapOf()

    /** Annotation-indexer shaped keys: the class, for an annotation on the type or on any member. */
    val classes: MutableMap<String, MutableSet<String>> = hashMapOf()

    fun add(descriptor: String, className: String, memberKey: String?) {
        elements.getOrPut(descriptor) { sortedSetOf<String>() }.add(if (memberKey == null) className else "$className#$memberKey")
        classes.getOrPut(descriptor) { sortedSetOf<String>() }.add(className)
    }
}

private class ScanVisitor(private val result: ScanResult) : ClassVisitor(Opcodes.ASM9) {
    private var current: String = ""

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        current = name.replace('/', '.')
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        result.add(descriptor, current, null)
        return null
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val owner = current
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                result.add(annotation, owner, "$name()")
                return null
            }
        }
    }

    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
        val owner = current
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                result.add(annotation, owner, name)
                return null
            }
        }
    }
}

private fun scanContainer(container: Container): ScanResult {
    val result = ScanResult()
    for ((name, bytes) in container.entries) {
        if (!name.endsWith(".class") || name.startsWith("META-INF/versions/") || name == "module-info.class") continue
        try {
            ClassReader(bytes).accept(ScanVisitor(result), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } catch (e: RuntimeException) {
            if (e is InputException) throw e
            throw InputException("unreadable class $name in ${container.path}: ${e.message}", e)
        }
    }
    return result
}

/** Parses a SezPoz `.txt` sibling: one `Class` or `Class#member` per line, optionally followed by `{values}`. */
internal fun parseSezpozText(text: String): Set<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringBefore('{').trim() }.filter { SEZPOZ_KEY.matches(it) }.toSortedSet()

private val SEZPOZ_KEY: Regex = Regex("[\\w.$]+(#[\\w$<>]+(\\(\\))?)?")

internal fun parseIndexerText(text: String): Set<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSortedSet()

private fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

private fun compare(file: String, annotation: String, source: IndexSource, index: Set<String>, scan: Set<String>): IndexFileCensus =
    IndexFileCensus(file, annotation, source, index.size, scan.size, (index - scan).sorted(), (scan - index).sorted())

/**
 * Enumerates extensions twice per container: from the index files under META-INF/annotations and
 * META-INF/services/annotations, and from an annotation scan of the class files.
 */
internal fun runCensus(containers: List<Container>, inheritedCheck: (className: String, annotation: String) -> Boolean = { _, _ -> false }): CensusResult {
    val jars = mutableListOf<JarCensus>()
    val found = linkedMapOf<String, ExtensionRef>()
    for (container in containers) {
        val scan = scanContainer(container)
        val files = mutableListOf<IndexFileCensus>()
        val indexedAnnotations = mutableSetOf<String>()
        val sezpozNames =
            container.entries.keys.filter { it.startsWith(SEZPOZ_PREFIX) }
                .map { it.removePrefix(SEZPOZ_PREFIX).removeSuffix(".txt") }.filter { it.isNotEmpty() && '/' !in it }.toSortedSet()
        for (annotation in sezpozNames) {
            val file = SEZPOZ_PREFIX + annotation
            val txt = container.entries["$file.txt"]
            val scanned = scan.elements[descriptorOf(annotation)].orEmpty()
            if (txt == null) {
                indexedAnnotations.add(annotation)
                files.add(IndexFileCensus(file, annotation, IndexSource.SEZPOZ_BIN_UNREADABLE, 0, scanned.size, listOf("<binary index without text sibling>"), emptyList()))
                if (annotation in EXTENSION_ANNOTATIONS) record(found, scanned, annotation, container.path, indexed = false)
                continue
            }
            val index = parseSezpozText(text(txt))
            indexedAnnotations.add(annotation)
            files.add(compare("$file.txt", annotation, IndexSource.SEZPOZ_TXT, index, scanned))
            if (annotation in EXTENSION_ANNOTATIONS) {
                record(found, index, annotation, container.path, indexed = true)
                record(found, scanned - index, annotation, container.path, indexed = false)
            }
        }
        for ((name, bytes) in container.entries) {
            if (!name.startsWith(INDEXER_PREFIX)) continue
            val annotation = name.removePrefix(INDEXER_PREFIX)
            if (annotation.isEmpty() || '/' in annotation) continue
            val raw = compare(name, annotation, IndexSource.ANNOTATION_INDEXER, parseIndexerText(text(bytes)), scan.classes[descriptorOf(annotation)].orEmpty())
            // Annotation-indexer also lists subclasses of classes carrying an @Inherited annotation; verify each such claim.
            val explained = raw.onlyInIndex.filter { inheritedCheck(it, annotation) }
            files.add(raw.copy(onlyInIndex = raw.onlyInIndex - explained.toSet(), explainedByInheritance = explained))
        }
        for (annotation in EXTENSION_ANNOTATIONS) {
            if (annotation in indexedAnnotations) continue
            val scanned = scan.elements[descriptorOf(annotation)].orEmpty()
            if (scanned.isEmpty()) continue
            files.add(IndexFileCensus("(no index file)", annotation, IndexSource.BYTECODE_SCAN, 0, scanned.size, emptyList(), emptyList()))
            record(found, scanned, annotation, container.path, indexed = false)
        }
        if (files.isNotEmpty()) jars.add(JarCensus(container.path, files))
    }
    return CensusResult(jars, found.values.sortedBy { it.key })
}

private fun record(found: MutableMap<String, ExtensionRef>, keys: Set<String>, annotation: String, container: String, indexed: Boolean) {
    for (key in keys) {
        val existing = found[key]
        found[key] =
            if (existing == null) {
                ExtensionRef(key, setOf(annotation), container, indexed)
            } else {
                existing.copy(annotations = existing.annotations + annotation, indexed = existing.indexed || indexed)
            }
    }
}
