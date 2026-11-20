// SPDX-License-Identifier: Apache-2.0
package jps

/** Identity of a hierarchy-only input (core jar or dependency archive). */
private fun inputIdentity(archive: LoadedArchive, defaultId: String): InputIdentity {
    val id = archive.manifest["Short-Name"] ?: defaultId
    val version = archive.manifest["Plugin-Version"] ?: archive.manifest["Implementation-Version"]
    return InputIdentity(id, version, archive.sha256)
}

private fun stem(fileName: String): String = fileName.substringBeforeLast('.')

/** Static extractor: reads archives as bytes, never loads a class. */
internal class StaticExtractor : Extractor {
    override fun extract(args: ExtractArgs): PluginSurface {
        val hpi = readArchive(args.hpi)
        val id = hpi.manifest["Short-Name"] ?: throw InputException("${hpi.fileName} is not a Jenkins plugin (no Short-Name in manifest)")
        val version = hpi.manifest["Plugin-Version"] ?: throw InputException("${hpi.fileName} has no Plugin-Version in manifest")
        val core = readArchive(args.core, loadContainers = false)
        val deps = args.deps.map { readArchive(it, loadContainers = false) }
        val census = runCensus(hpi.containers)
        val records = census.extensions.map(::pendingRecord)
        val unknown = records.count { it.kind == RecordKind.UNKNOWN }
        return PluginSurface(
            plugin =
                PluginIdentity(
                    PluginId(id),
                    version,
                    hpi.sha256,
                    hpi.manifest["Jenkins-Version"] ?: hpi.manifest["Hudson-Version"],
                    parseDependencies(hpi.manifest["Plugin-Dependencies"]),
                ),
            generator =
                GeneratorInfo(
                    TOOL_VERSION,
                    listOf("bytecode-scan", "index-reader"),
                    inputIdentity(core, stem(core.fileName)),
                    deps.map { inputIdentity(it, stem(it.fileName)) },
                ),
            records = records,
            census =
                Census(
                    extensionsSeen = records.size,
                    classified = records.size - unknown,
                    unknown = unknown,
                    notPipelineFacing = records.count { it.kind == RecordKind.NOT_PIPELINE_FACING },
                    jars = census.jars,
                ),
        )
    }
}

/** Until classification lands, every enumerated extension is reported as unknown (counted, never dropped). */
private fun pendingRecord(ref: ExtensionRef): Record =
    Record(
        id = "unknown:${ref.key}",
        kind = RecordKind.UNKNOWN,
        extensionClass = ClassName(ref.className.replace('.', '/')),
        describableClass = null,
        name = null,
        symbols = emptyList(),
        confidence = Confidence.UNKNOWN,
        evidence = ref.annotations.map { (if (ref.indexed) "index:" else "scan:") + it },
        reason = "classification-not-implemented",
        gaps = emptyList(),
        step = null,
        argsRewritten = false,
        dynamicDispatch = false,
        optionalExtension = ref.annotations.contains(OPTIONAL_EXTENSION_ANNOTATION),
        declarativeDirectives = emptyList(),
        extension = null,
        parameters = emptyList(),
        docs = null,
        links = emptyList(),
    )
