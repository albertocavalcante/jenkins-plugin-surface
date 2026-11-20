// SPDX-License-Identifier: Apache-2.0
package jps

import java.io.ByteArrayInputStream
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
private const val MAX_ENTRY_BYTES: Int = 512 * 1024 * 1024

internal fun sha256Hex(bytes: ByteArray): Sha256 =
    Sha256(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

private fun readZip(bytes: ByteArray, what: String): Map<String, ByteArray> {
    val out = sortedMapOf<String, ByteArray>()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.size > MAX_ENTRY_BYTES) throw InputException("entry too large in $what: ${entry.name}")
                out[entry.name] = zip.readBytes()
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
    val top = readZip(bytes, fileName)
    val manifest = parseManifest(top["META-INF/MANIFEST.MF"])
    val isPlugin = top.keys.any { it.startsWith(LIB_PREFIX) }
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
