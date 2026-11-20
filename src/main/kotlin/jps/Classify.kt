// SPDX-License-Identifier: Apache-2.0
package jps

import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

private val PARAMETER_KINDS: Set<RecordKind> =
    setOf(
        RecordKind.STEP,
        RecordKind.STEP_WRAPPED_DESCRIBABLE,
        RecordKind.WRAP_WRAPPED_DESCRIBABLE,
        RecordKind.NESTED_ONLY_SYMBOL,
        RecordKind.DECLARATIVE_ELEMENT,
    )

private val DYNAMIC_METHODS: Set<String> = setOf("invokeMethod", "methodMissing", "propertyMissing")

private val SHARED_DIRECTIVES: List<Pair<String, String>> =
    listOf(
        Types.TRIGGER_DESCRIPTOR to "triggers",
        Types.PARAMETER_DESCRIPTOR to "parameters",
        Types.TOOL_DESCRIPTOR to "tools",
        Types.JOB_PROPERTY_DESCRIPTOR to "options",
    )

private val DECLARATIVE_BASES: List<Pair<String, String>> =
    listOf(
        Types.AGENT_DESCRIPTOR to "agent",
        Types.OPTION_DESCRIPTOR to "options",
        Types.WHEN_DESCRIPTOR to "when",
    )

/**
 * Turns every enumerated extension into exactly one record. Positive facts come from supertype names found while
 * walking the hierarchy; a negative claim ("not pipeline facing") requires the whole hierarchy to be resolved.
 */
internal class Classifier(private val store: ClassStore, private val pluginId: String) {
    private val interp = Interp(store)
    private val params = ParamExtractor(store)

    fun classify(ref: ExtensionRef): Record {
        val skeleton = skeleton(ref)
        if (ref.isMember) return unknown(skeleton, "member-extension", ref.key)
        val internalName = ref.className.replace('.', '/')
        val cls = store.load(internalName) ?: return unknown(skeleton, "class-not-found", ref.className)
        val closure = store.closure(internalName)
        val hierarchyGap = closure.unresolved.firstOrNull()?.let { listOf(Gap("flags", "unresolvable-type", it)) } ?: emptyList()
        val withFacts =
            skeleton.copy(
                extension = extensionFacts(cls.node),
                dynamicDispatch = declaresDynamic(cls.node),
                argsRewritten = Types.CUSTOM_DESCRIBABLE_MODEL in closure,
                gaps = hierarchyGap,
            )
        val isDescriptor = Types.DESCRIPTOR in closure
        return when {
            Types.BUILD_CONDITION in closure && !isDescriptor ->
                declarative(withFacts, cls.node, listOf("post"), symbolsOf(cls.node), "class:${ref.className}")
            Types.GLOBAL_VARIABLE in closure -> global(withFacts, internalName)
            Types.GLOBAL_VARIABLE_SET in closure ->
                finish(withFacts.copy(kind = RecordKind.GLOBAL_VARIABLE, gaps = listOf(Gap("name", "dynamic-global-set", "globals are computed at run time"))), emptyList())
            isDescriptor && Types.STEP_DESCRIPTOR in closure -> step(withFacts, cls, internalName)
            isDescriptor -> descriptor(withFacts, cls, closure)
            closure.unresolved.isNotEmpty() -> unknown(withFacts, "unresolvable-type", closure.unresolved.first())
            else -> finish(withFacts.copy(kind = RecordKind.NOT_PIPELINE_FACING, reason = "no-pipeline-base-type"), listOf("hierarchy:resolved"))
        }
    }

    // ---- kinds --------------------------------------------------------------------------------

    private fun step(base: Record, cls: LoadedClass, internalName: String): Record {
        val (name, nameFailure) = interp.evalNoArg(internalName, "getFunctionName", "()Ljava/lang/String;", Types.STEP_DESCRIPTOR).string()
        if (name == null) return unknown(base, "function-name-not-constant", nameFailure ?: "")
        val gaps = mutableListOf<Gap>()
        fun flag(method: String): Boolean? {
            val (value, failure) = interp.evalNoArg(internalName, method, "()Z", Types.STEP_DESCRIPTOR).bool(default = false)
            if (value == null) gaps.add(Gap("step.$method", failure ?: "unknown"))
            return value
        }
        val block = flag("takesImplicitBlockArgument")
        val advanced = flag("isAdvanced")
        val meta = flag("isMetaStep")
        val (context, contextFailure) = interp.evalNoArg(internalName, "getRequiredContext", "()Ljava/util/Set;", Types.STEP_DESCRIPTOR).classSet()
        if (context == null) gaps.add(Gap("step.required_context", contextFailure ?: "unknown"))
        val describable = describableOf(cls)
        if (describable == null) gaps.add(Gap("describable_class", "describable-type-unresolved", cls.node.name))
        val symbols = symbolsOf(cls.node) + (describable?.let { store.load(it.internal)?.node?.let(::symbolsOf) } ?: emptyList())
        val directives = SHARED_DIRECTIVES.filter { it.first in store.closure(cls.node.name) }.map { it.second }
        return finish(
            base.copy(
                kind = RecordKind.STEP,
                describableClass = describable,
                name = name,
                symbols = symbols.distinct(),
                step = StepFacts(block, context, advanced, meta),
                gaps = base.gaps + gaps,
                declarativeDirectives = directives,
            ),
            listOf("bytecode:getFunctionName", "class:${cls.node.name.replace('/', '.')}"),
        )
    }

    private fun global(base: Record, internalName: String): Record {
        val (name, failure) = interp.evalNoArg(internalName, "getName", "()Ljava/lang/String;", Types.GLOBAL_VARIABLE).string()
        if (name == null) return unknown(base, "global-name-not-constant", failure ?: "")
        return finish(base.copy(kind = RecordKind.GLOBAL_VARIABLE, name = name), listOf("bytecode:getName"))
    }

    private fun descriptor(base: Record, cls: LoadedClass, closure: Closure): Record {
        val describable = describableOf(cls) ?: return unknown(base, "describable-type-unresolved", cls.node.name)
        val withDescribable = base.copy(describableClass = describable)
        val describableNode = store.load(describable.internal)
        val symbols = (symbolsOf(cls.node) + (describableNode?.node?.let(::symbolsOf) ?: emptyList())).distinct()
        val declarativeDirective = DECLARATIVE_BASES.firstOrNull { it.first in closure }?.second
        if (declarativeDirective != null) return declarative(withDescribable, cls.node, listOf(declarativeDirective), symbols, "class:${cls.node.name.replace('/', '.')}")
        val describableClosure = store.closure(describable.internal)
        val directives = SHARED_DIRECTIVES.filter { it.first in closure }.map { it.second }
        val shared = withDescribable.copy(symbols = symbols, declarativeDirectives = directives, argsRewritten = withDescribable.argsRewritten || Types.CUSTOM_DESCRIBABLE_MODEL in describableClosure)
        val evidence = listOf("class:${cls.node.name.replace('/', '.')}", "describable:${describable.dotted}")
        return when {
            Types.SIMPLE_BUILD_STEP in describableClosure -> finish(shared.copy(kind = RecordKind.STEP_WRAPPED_DESCRIBABLE), evidence)
            Types.SIMPLE_BUILD_WRAPPER in describableClosure -> finish(shared.copy(kind = RecordKind.WRAP_WRAPPED_DESCRIBABLE), evidence)
            describableClosure.unresolved.isNotEmpty() -> unknown(shared, "unresolvable-type", describableClosure.unresolved.first())
            symbols.isNotEmpty() -> finish(shared.copy(kind = RecordKind.NESTED_ONLY_SYMBOL), evidence)
            else -> finish(shared.copy(kind = RecordKind.NOT_PIPELINE_FACING, reason = "descriptor-without-symbol"), evidence)
        }
    }

    private fun declarative(base: Record, node: ClassNode, directives: List<String>, symbols: List<String>, evidence: String): Record =
        finish(base.copy(kind = RecordKind.DECLARATIVE_ELEMENT, symbols = symbols, declarativeDirectives = directives), listOf(evidence, "node:${node.name}"))

    // ---- helpers ------------------------------------------------------------------------------

    private fun skeleton(ref: ExtensionRef): Record =
        Record(
            id = "pending:${ref.key}",
            kind = RecordKind.UNKNOWN,
            extensionClass = ClassName(ref.className.replace('.', '/')),
            describableClass = null,
            name = null,
            symbols = emptyList(),
            confidence = Confidence.UNKNOWN,
            evidence = ref.annotations.map { (if (ref.indexed) "index:" else "scan:") + it },
            reason = null,
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
        ).let { it.copy(id = "pending:${ref.key}") }

    private fun unknown(base: Record, reason: String, detail: String): Record =
        base.copy(
            id = "${RecordKind.UNKNOWN.wire}:${base.id.removePrefix("pending:")}",
            kind = RecordKind.UNKNOWN,
            confidence = Confidence.UNKNOWN,
            reason = reason,
            gaps = base.gaps + Gap("kind", reason, detail),
            step = null,
        )

    /** Fixes the id, confidence and links once the kind is known. */
    private fun finish(input: Record, evidence: List<String>): Record {
        val record = withParameters(input)
        val key = record.id.removePrefix("pending:")
        val confidence = if (record.gaps.isEmpty()) Confidence.EXACT else Confidence.INFERRED
        return record.copy(
            id = "${record.kind.wire}:$key",
            confidence = confidence,
            evidence = (record.evidence + evidence).distinct(),
            links = linksFor(record),
        )
    }

    private fun withParameters(record: Record): Record {
        if (record.kind !in PARAMETER_KINDS) return record
        val describable = record.describableClass ?: return record.copy(gaps = record.gaps + Gap("parameters", "describable-type-unresolved"))
        val result = params.parametersOf(describable)
        val step = record.step?.let { facts -> if (facts.metastep == true) facts.copy(metastepArgumentType = result.firstConstructorType) else facts }
        val metaGap = if (step?.metastep == true && step.metastepArgumentType == null) listOf(Gap("step.metastep_argument_type", "first-constructor-parameter-not-a-class")) else emptyList()
        return record.copy(parameters = result.parameters, gaps = record.gaps + result.gaps + metaGap, step = step)
    }

    private fun linksFor(record: Record): List<Link> {
        val links = mutableListOf(Link(LinkRel.PLUGIN, "https://plugins.jenkins.io/$pluginId/"))
        val javadocClass = (record.describableClass ?: record.extensionClass).internal
        if (store.load(javadocClass)?.target == true) {
            links.add(Link(LinkRel.JAVADOC, "https://javadoc.jenkins.io/plugin/$pluginId/${javadocClass.replace('$', '.')}.html"))
        }
        if (record.kind == RecordKind.STEP) {
            links.add(Link(LinkRel.REFERENCE, "https://www.jenkins.io/doc/pipeline/steps/$pluginId/"))
            links.add(Link(LinkRel.CONTROLLER, "{controller}/pipeline-syntax/"))
        }
        if (record.kind == RecordKind.DECLARATIVE_ELEMENT) {
            record.declarativeDirectives.forEach { links.add(Link(LinkRel.SYNTAX, "https://www.jenkins.io/doc/book/pipeline/syntax/#$it")) }
        }
        return links
    }

    private fun symbolsOf(node: ClassNode): List<String> =
        annotationsOf(node).filter { it.desc == Types.SYMBOL_DESC }.flatMap { it.stringList("value") }

    private fun declaresDynamic(node: ClassNode): Boolean = node.methods.any { it.name in DYNAMIC_METHODS }

    private fun extensionFacts(node: ClassNode): ExtensionFacts? {
        val annotations = annotationsOf(node)
        annotations.firstOrNull { it.desc == Types.EXTENSION_DESC }?.let { a ->
            val dynamic = (a.value("dynamicLoadable") as? Array<*>)?.getOrNull(1) as? String
            return ExtensionFacts(EXTENSION_ANNOTATION, a.value("ordinal") as? Double, a.value("optional") as? Boolean ?: false, emptyList(), dynamic)
        }
        annotations.firstOrNull { it.desc == Types.OPTIONAL_EXTENSION_DESC }?.let { a ->
            val requirements =
                a.stringList("requirePlugins").map { "plugin:$it" } + a.stringList("requireClasses").map { "class:$it" } + a.stringList("requireVariants").map { "variant:$it" }
            return ExtensionFacts(OPTIONAL_EXTENSION_ANNOTATION, a.value("ordinal") as? Double, true, requirements, null)
        }
        return null
    }

    /**
     * The describable type a descriptor describes: the class literal handed to `Descriptor(Class)` by the first
     * class on the chain that does so, else the enclosing class (Jenkins's default for the no-argument constructor).
     */
    private fun describableOf(cls: LoadedClass): ClassName? {
        var current: LoadedClass? = cls
        while (current != null && current.node.name != Types.DESCRIPTOR) {
            val literal = classLiteralPassedToSuper(current.node)
            if (literal != null) return ClassName(literal)
            current = current.node.superName?.let { store.load(it) }
        }
        val outer = cls.node.innerClasses.firstOrNull { it.name == cls.node.name }?.outerName
        return outer?.let { ClassName(it) }
    }

    /** A class literal is accepted only as the sole argument of `super(Class)`, i.e. the instruction right before the call. */
    private fun classLiteralPassedToSuper(node: ClassNode): String? {
        val found = sortedSetOf<String>()
        for (method in node.methods) {
            if (method.name != "<init>") continue
            var previous: org.objectweb.asm.tree.AbstractInsnNode? = null
            var insn = method.instructions.first
            while (insn != null) {
                if (insn.opcode >= 0) {
                    if (insn is MethodInsnNode && insn.opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL && insn.name == "<init>" &&
                        insn.desc == "(Ljava/lang/Class;)V" && insn.owner == node.superName
                    ) {
                        val cst = (previous as? LdcInsnNode)?.cst
                        if (cst is Type && cst.sort == Type.OBJECT) found.add(cst.internalName)
                    }
                    previous = insn
                }
                insn = insn.next
            }
        }
        return found.singleOrNull()
    }
}
