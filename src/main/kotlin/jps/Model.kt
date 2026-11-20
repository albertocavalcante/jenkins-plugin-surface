// SPDX-License-Identifier: Apache-2.0
package jps

/** Schema identifier written into every document. */
internal const val SCHEMA_ID: String = "jenkins-plugin-surface/v1"

/** A JVM internal class name such as `hudson/model/Descriptor`. */
@JvmInline
internal value class ClassName(val internal: String) : Comparable<ClassName> {
    val dotted: String get() = internal.replace('/', '.')

    override fun compareTo(other: ClassName): Int = internal.compareTo(other.internal)
}

@JvmInline
internal value class PluginId(val value: String)

/** Lower-case hex SHA-256 computed by this tool from the archive bytes. */
@JvmInline
internal value class Sha256(val hex: String)

internal enum class Confidence(val wire: String) {
    EXACT("exact"),
    INFERRED("inferred"),
    UNKNOWN("unknown"),
}

internal enum class RecordKind(val wire: String) {
    STEP("step"),
    STEP_WRAPPED_DESCRIBABLE("step-wrapped-describable"),
    WRAP_WRAPPED_DESCRIBABLE("wrap-wrapped-describable"),
    NESTED_ONLY_SYMBOL("nested-only-symbol"),
    GLOBAL_VARIABLE("global-variable"),
    DECLARATIVE_ELEMENT("declarative-element"),
    NOT_PIPELINE_FACING("not-pipeline-facing"),
    UNKNOWN("unknown"),
}

/** A fact that could not be established statically; never silently dropped. */
internal data class Gap(val field: String, val reason: String, val detail: String? = null)

internal data class Dependency(val id: PluginId, val version: String, val optional: Boolean)

internal data class PluginIdentity(
    val id: PluginId,
    val version: String,
    val sha256: Sha256,
    val requiredCore: String?,
    val dependencies: List<Dependency>,
)

internal data class InputIdentity(val id: String, val version: String?, val sha256: Sha256)

internal data class GeneratorInfo(
    val toolVersion: String,
    val engines: List<String>,
    val core: InputIdentity,
    val dependencyInputs: List<InputIdentity>,
)

internal enum class TypeCategory(val wire: String) {
    ATOMIC("atomic"),
    ENUM("enum"),
    ARRAY("array"),
    OBJECT("object"),
    UNSUPPORTED("unsupported"),
}

/** A parameter type: [java] is a readable generic type, [nested] names the erased class of an object type. */
internal data class TypeNode(
    val category: TypeCategory,
    val java: String,
    val element: TypeNode? = null,
    val enumValues: List<String>? = null,
    val nested: ClassName? = null,
)

internal enum class UsageHint(val wire: String) {
    UNKNOWN("unknown"),
    NULLABLE("nullable"),
}

internal data class Parameter(
    val name: String,
    val type: TypeNode,
    /** Exact: Jenkins's model defines required as "has no setter". */
    val modelRequired: Boolean,
    val usageHint: UsageHint = UsageHint.UNKNOWN,
    val usageHintEvidence: String? = null,
    val deprecated: Boolean = false,
    val origin: String,
    val namesSource: String? = null,
    val docs: Docs? = null,
)

internal data class StepFacts(
    val takesBlock: Boolean?,
    val requiredContext: List<ClassName>?,
    val advanced: Boolean?,
    val metastep: Boolean?,
    val metastepArgumentType: ClassName? = null,
)

internal enum class DocsStatus(val wire: String) {
    PRESENT("present"),
    DYNAMIC("dynamic"),
    ABSENT("absent"),
}

internal data class DocsSource(val kind: String, val path: String)

internal data class Docs(
    val status: DocsStatus,
    val summary: String? = null,
    val bodyHtml: String? = null,
    val bodyMarkdown: String? = null,
    val locale: String? = null,
    val source: DocsSource? = null,
)

internal data class Link(val rel: String, val url: String)

internal data class ExtensionFacts(
    val annotation: String,
    val ordinal: Double?,
    val optional: Boolean,
    val requirements: List<String>,
    val dynamicLoadable: String?,
)

internal data class Record(
    val id: String,
    val kind: RecordKind,
    val extensionClass: ClassName,
    val describableClass: ClassName?,
    val name: String?,
    val symbols: List<String>,
    val confidence: Confidence,
    val evidence: List<String>,
    val reason: String?,
    val gaps: List<Gap>,
    val step: StepFacts?,
    val argsRewritten: Boolean,
    val dynamicDispatch: Boolean,
    val optionalExtension: Boolean,
    val declarativeDirectives: List<String>,
    val extension: ExtensionFacts?,
    val parameters: List<Parameter>,
    val docs: Docs?,
    val links: List<Link>,
)

internal data class IndexFileCensus(
    val file: String,
    val annotation: String,
    val source: String,
    val indexCount: Int,
    val scanCount: Int,
    val onlyInIndex: List<String>,
    val onlyInScan: List<String>,
) {
    val agree: Boolean get() = onlyInIndex.isEmpty() && onlyInScan.isEmpty()
}

internal data class JarCensus(val path: String, val files: List<IndexFileCensus>) {
    val agree: Boolean get() = files.all { it.agree }
}

internal data class Census(
    val extensionsSeen: Int,
    val classified: Int,
    val unknown: Int,
    val notPipelineFacing: Int,
    val jars: List<JarCensus>,
) {
    val indexScanAgree: Boolean get() = jars.all { it.agree }
}

internal data class PluginSurface(
    val plugin: PluginIdentity,
    val generator: GeneratorInfo,
    val records: List<Record>,
    val census: Census,
)

// ---- JSON mapping -------------------------------------------------------------------------------

private fun Confidence.json(): Json = jstr(wire)

private fun InputIdentity.json(): Json = jobj("id" to jstr(id), "version" to version?.let { jstr(it) }, "sha256" to jstr(sha256.hex))

private fun Gap.json(): Json = jobj("field" to jstr(field), "reason" to jstr(reason), "detail" to detail?.let { jstr(it) })

private fun TypeNode.json(): Json =
    jobj(
        "category" to jstr(category.wire),
        "java" to jstr(java),
        "element" to element?.json(),
        "enum_values" to enumValues?.let { jstrs(it) },
        "nested_type" to nested?.let { jstr(it.dotted) },
    )

private fun Docs.json(): Json =
    jobj(
        "status" to jstr(status.wire),
        "summary" to summary?.let { jstr(it) },
        "body_html" to bodyHtml?.let { jstr(it) },
        "body_markdown" to bodyMarkdown?.let { jstr(it) },
        "locale" to locale?.let { jstr(it) },
        "source" to source?.let { jobj("kind" to jstr(it.kind), "path" to jstr(it.path)) },
    )

private fun Parameter.json(): Json =
    jobj(
        "name" to jstr(name),
        "type" to type.json(),
        "model_required" to jbool(modelRequired),
        "usage_hint" to jstr(usageHint.wire),
        "usage_hint_evidence" to usageHintEvidence?.let { jstr(it) },
        "deprecated" to jbool(deprecated),
        "origin" to jstr(origin),
        "names_source" to namesSource?.let { jstr(it) },
        "docs" to docs?.json(),
    )

private fun StepFacts.json(): Json =
    jobj(
        "takes_block" to takesBlock?.let { jbool(it) },
        "required_context" to requiredContext?.let { list -> jstrs(list.map { it.dotted }.sorted()) },
        "advanced" to advanced?.let { jbool(it) },
        "metastep" to metastep?.let { jbool(it) },
        "metastep_argument_type" to metastepArgumentType?.let { jstr(it.dotted) },
    )

private fun ExtensionFacts.json(): Json =
    jobj(
        "annotation" to jstr(annotation),
        "ordinal" to ordinal?.let { Json.Num(it.toString()) },
        "optional" to jbool(optional),
        "requirements" to jstrs(requirements.sorted()),
        "dynamic_loadable" to dynamicLoadable?.let { jstr(it) },
    )

internal fun Record.json(): Json =
    jobj(
        "id" to jstr(id),
        "kind" to jstr(kind.wire),
        "extension_class" to jstr(extensionClass.dotted),
        "describable_class" to describableClass?.let { jstr(it.dotted) },
        "name" to name?.let { jstr(it) },
        "symbols" to jstrs(symbols.sorted()),
        "confidence" to confidence.json(),
        "evidence" to jstrs(evidence.sorted()),
        "reason" to reason?.let { jstr(it) },
        "gaps" to jarr(gaps.sortedWith(compareBy({ it.field }, { it.reason }, { it.detail ?: "" })).map { it.json() }),
        "step" to step?.json(),
        "args_rewritten" to jbool(argsRewritten),
        "dynamic_dispatch" to jbool(dynamicDispatch),
        "optional_extension" to jbool(optionalExtension),
        "declarative_directives" to jstrs(declarativeDirectives.sorted()),
        "extension" to extension?.json(),
        // Parameter order is the model order (constructor first, then setters alphabetically) and is meaningful.
        "parameters" to jarr(parameters.map { it.json() }),
        "docs" to docs?.json(),
        "links" to jarr(links.sortedWith(compareBy({ it.rel }, { it.url })).map { jobj("rel" to jstr(it.rel), "url" to jstr(it.url)) }),
    )

private fun IndexFileCensus.json(): Json =
    jobj(
        "file" to jstr(file),
        "annotation" to jstr(annotation),
        "source" to jstr(source),
        "index_count" to jint(indexCount),
        "scan_count" to jint(scanCount),
        "agree" to jbool(agree),
        "only_in_index" to jstrs(onlyInIndex.sorted()),
        "only_in_scan" to jstrs(onlyInScan.sorted()),
    )

private fun Census.json(): Json =
    jobj(
        "extensions_seen" to jint(extensionsSeen),
        "classified" to jint(classified),
        "unknown" to jint(unknown),
        "not_pipeline_facing" to jint(notPipelineFacing),
        "index_scan_agree" to jbool(indexScanAgree),
        "jars" to
            jarr(
                jars.sortedBy { it.path }.map { jar ->
                    jobj(
                        "path" to jstr(jar.path),
                        "agree" to jbool(jar.agree),
                        "files" to jarr(jar.files.sortedWith(compareBy({ it.file }, { it.source })).map { it.json() }),
                    )
                },
            ),
    )

internal fun PluginSurface.json(): Json =
    jobj(
        "schema" to jstr(SCHEMA_ID),
        "plugin" to
            jobj(
                "id" to jstr(plugin.id.value),
                "version" to jstr(plugin.version),
                "sha256" to jstr(plugin.sha256.hex),
                "required_core" to plugin.requiredCore?.let { jstr(it) },
                "dependencies" to
                    jarr(
                        plugin.dependencies.sortedBy { it.id.value }.map {
                            jobj("id" to jstr(it.id.value), "version" to jstr(it.version), "optional" to jbool(it.optional))
                        },
                    ),
            ),
        "generator" to
            jobj(
                "tool" to jstr("jenkins-plugin-surface"),
                "version" to jstr(generator.toolVersion),
                "engines" to jstrs(generator.engines.sorted()),
                "core" to generator.core.json(),
                "dependency_inputs" to jarr(generator.dependencyInputs.sortedBy { it.id }.map { it.json() }),
            ),
        "records" to jarr(records.sortedBy { it.id }.map { it.json() }),
        "census" to census.json(),
    )

internal fun PluginSurface.render(): String = json().render()
