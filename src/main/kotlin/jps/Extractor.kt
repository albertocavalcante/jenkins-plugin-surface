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
        val core = readArchive(args.core)
        val deps = args.deps.map { readArchive(it) }
        val store = ClassStore(hpi.containers, deps.flatMap { it.containers } + core.containers)
        val census = runCensus(hpi.containers) { className, annotation -> ancestorHasAnnotation(store, className, annotation) }
        val classifier = Classifier(store, id, args.noHelpText)
        val records = census.extensions.map(classifier::classify)
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

/** True when a strict ancestor of [className] (dotted) carries [annotation] (dotted), which explains an inherited index entry. */
private fun ancestorHasAnnotation(store: ClassStore, className: String, annotation: String): Boolean {
    val self = className.replace('.', '/')
    val descriptor = "L" + annotation.replace('.', '/') + ";"
    return store.closure(self).visited.any { ancestor ->
        ancestor != self && store.load(ancestor)?.node?.let { node -> annotationsOf(node).any { it.desc == descriptor } } == true
    }
}
