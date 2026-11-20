// SPDX-License-Identifier: Apache-2.0
package jps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** One jar (or the plugin's `WEB-INF/classes`) with all entries held in memory; nothing is ever class-loaded. */
internal class Container(val path: String, val entries: Map<String, ByteArray>)

internal class LoadedArchive(
    val fileName: String,
    val sha256: Sha256,
    val manifest: Map<String, String>,
    val containers: List<Container>,
)

internal const val CLASSES_DIR: String = "WEB-INF/classes"
private const val LIB_PREFIX: String = "WEB-INF/lib/"
private const val CLASSES_PREFIX: String = "WEB-INF/classes/"
private const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024
private const val MAX_TOTAL_BYTES: Long = 400L * 1024 * 1024
private const val MAX_ENTRIES: Int = 200_000

internal fun sha256Hex(bytes: ByteArray): Sha256 =
    Sha256(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

/** Reads one entry with a hard size cap (the declared size is unreliable for streamed entries). */
private fun readBounded(zip: ZipInputStream, name: String, what: String, budget: LongArray): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var size = 0L
    while (true) {
        val n = zip.read(buffer)
        if (n < 0) break
        size += n
        budget[0] += n.toLong()
        if (size > MAX_ENTRY_BYTES || budget[0] > MAX_TOTAL_BYTES) throw InputException("archive too large: $name in $what")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Reads all file entries; with [only] set, stops after that entry (identity-only reads). */
private fun readZip(bytes: ByteArray, what: String, only: String? = null): Map<String, ByteArray> {
    val out = sortedMapOf<String, ByteArray>()
    val budget = longArrayOf(0L)
    var count = 0
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > MAX_ENTRIES) throw InputException("too many entries in $what")
                if (out.containsKey(entry.name)) throw InputException("duplicate entry ${entry.name} in $what")
                if (only != null && entry.name != only) continue
                out[entry.name] = readBounded(zip, entry.name, what, budget)
                if (only != null) break
            }
        }
    } catch (e: ZipException) {
        throw InputException("malformed archive $what: ${e.message}", e)
    } catch (e: IOException) {
        throw InputException("unreadable archive $what: ${e.message}", e)
    }
    return out
}

/**
 * Reads a plugin archive (WEB-INF/lib jar layout) or a plain jar. Containers are sorted by path so every
 * downstream traversal is deterministic. With [loadContainers] false only identity (hash, manifest) is read.
 */
internal fun readArchive(path: Path, loadContainers: Boolean = true): LoadedArchive {
    val fileName = path.fileName.toString()
    val bytes =
        try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw InputException("cannot read $fileName: ${e.message}", e)
        }
    val top = readZip(bytes, fileName, only = if (loadContainers) null else "META-INF/MANIFEST.MF")
    if (top.isEmpty() && loadContainers) throw InputException("$fileName is not a zip archive or is empty")
    val manifest = parseManifest(top["META-INF/MANIFEST.MF"])
    val isPlugin = top.keys.any { it.startsWith(LIB_PREFIX) || it.startsWith(CLASSES_PREFIX) }
    val containers = mutableListOf<Container>()
    if (loadContainers) {
        if (isPlugin) {
            for ((name, data) in top) {
                if (name.startsWith(LIB_PREFIX) && name.endsWith(".jar")) containers.add(Container(name, readZip(data, "$fileName!$name")))
            }
            val classes = top.filterKeys { it.startsWith(CLASSES_PREFIX) }.mapKeys { it.key.removePrefix(CLASSES_PREFIX) }
            if (classes.isNotEmpty()) containers.add(Container(CLASSES_DIR, classes))
        } else {
            containers.add(Container(fileName, top))
        }
    }
    if (!loadContainers && manifest.isEmpty()) throw InputException("$fileName has no readable manifest (not a jar or plugin archive)")
    return LoadedArchive(fileName, sha256Hex(bytes), manifest, containers.sortedBy { it.path })
}

/** Main-section manifest attributes with 72-column continuation lines unfolded. */
internal fun parseManifest(bytes: ByteArray?): Map<String, String> {
    if (bytes == null) return emptyMap()
    val lines = String(bytes, Charsets.UTF_8).split("\r\n", "\n", "\r")
    val unfolded = mutableListOf<String>()
    for (line in lines) {
        if (line.isEmpty()) {
            if (unfolded.isNotEmpty()) break
            continue
        }
        if (line.startsWith(" ") && unfolded.isNotEmpty()) {
            unfolded[unfolded.size - 1] = unfolded.last() + line.substring(1)
        } else {
            unfolded.add(line)
        }
    }
    val out = linkedMapOf<String, String>()
    for (line in unfolded) {
        val idx = line.indexOf(": ")
        if (idx > 0) out[line.substring(0, idx)] = line.substring(idx + 2).trim()
    }
    return out
}

internal fun parseDependencies(value: String?): List<Dependency> =
    value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
        val parts = entry.split(';')
        val nameVersion = parts[0].split(':', limit = 2)
        Dependency(
            PluginId(nameVersion[0]),
            nameVersion.getOrElse(1) { "" },
            parts.drop(1).any { it.trim() == "resolution:=optional" },
        )
    }
