// SPDX-License-Identifier: Apache-2.0
package jps

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

internal object Types {
    const val OBJECT: String = "java/lang/Object"
    const val DESCRIPTOR: String = "hudson/model/Descriptor"
    const val STEP_DESCRIPTOR: String = "org/jenkinsci/plugins/workflow/steps/StepDescriptor"
    const val GLOBAL_VARIABLE: String = "org/jenkinsci/plugins/workflow/cps/GlobalVariable"
    const val GLOBAL_VARIABLE_SET: String = "org/jenkinsci/plugins/workflow/cps/global/GlobalVariableSet"
    const val SIMPLE_BUILD_STEP: String = "jenkins/tasks/SimpleBuildStep"
    const val SIMPLE_BUILD_WRAPPER: String = "jenkins/tasks/SimpleBuildWrapper"
    const val CUSTOM_DESCRIBABLE_MODEL: String = "org/jenkinsci/plugins/structs/describable/CustomDescribableModel"
    const val AGENT_DESCRIPTOR: String = "org/jenkinsci/plugins/pipeline/modeldefinition/agent/DeclarativeAgentDescriptor"
    const val OPTION_DESCRIPTOR: String = "org/jenkinsci/plugins/pipeline/modeldefinition/options/DeclarativeOptionDescriptor"
    const val WHEN_DESCRIPTOR: String = "org/jenkinsci/plugins/pipeline/modeldefinition/when/DeclarativeStageConditionalDescriptor"
    const val BUILD_CONDITION: String = "org/jenkinsci/plugins/pipeline/modeldefinition/model/BuildCondition"
    const val TRIGGER_DESCRIPTOR: String = "hudson/triggers/TriggerDescriptor"
    const val PARAMETER_DESCRIPTOR: String = "hudson/model/ParameterDefinition\$ParameterDescriptor"
    const val TOOL_DESCRIPTOR: String = "hudson/tools/ToolDescriptor"
    const val JOB_PROPERTY_DESCRIPTOR: String = "hudson/model/JobPropertyDescriptor"
    const val SYMBOL_DESC: String = "Lorg/jenkinsci/Symbol;"
    const val EXTENSION_DESC: String = "Lhudson/Extension;"
    const val OPTIONAL_EXTENSION_DESC: String = "Lorg/jenkinsci/plugins/variant/OptionalExtension;"
}

/** A parsed class and the container it came from. [target] is true for the analysed plugin's own jars. */
internal class LoadedClass(val node: ClassNode, val container: Container, val target: Boolean)

internal class Closure(val visited: Set<String>, val unresolved: Set<String>) {
    operator fun contains(name: String): Boolean = name in visited
}

/** Resolves classes across the plugin, supplied dependencies and the core jar, from bytes only. */
internal class ClassStore(targets: List<Container>, others: List<Container>) {
    private class Entry(val bytes: ByteArray, val container: Container, val target: Boolean)

    private val index: Map<String, Entry>
    private val loaded: MutableMap<String, LoadedClass?> = hashMapOf()
    private val closures: MutableMap<String, Closure> = hashMapOf()

    init {
        val map = hashMapOf<String, Entry>()
        for ((containers, isTarget) in listOf(targets to true, others to false)) {
            for (container in containers) {
                for ((entry, bytes) in container.entries) {
                    if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) continue
                    map.putIfAbsent(entry.removeSuffix(".class"), Entry(bytes, container, isTarget))
                }
            }
        }
        index = map
    }

    fun load(name: String): LoadedClass? =
        loaded.getOrPut(name) {
            val entry = index[name] ?: return@getOrPut null
            val node = ClassNode()
            try {
                ClassReader(entry.bytes).accept(node, ClassReader.SKIP_FRAMES)
            } catch (e: RuntimeException) {
                throw InputException("unreadable class $name: ${e.message}", e)
            }
            LoadedClass(node, entry.container, entry.target)
        }

    fun resource(container: Container, path: String): ByteArray? = container.entries[path]

    /** All supertypes reachable from [name] (classes and interfaces). JDK `java/` types are leaves; others absent from every input are unresolved. */
    fun closure(name: String): Closure =
        closures.getOrPut(name) {
            val visited = sortedSetOf<String>()
            val unresolved = sortedSetOf<String>()
            val pending = ArrayDeque<String>()
            pending.add(name)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!visited.add(current)) continue
                if (current.startsWith("java/")) continue
                val cls = load(current)
                if (cls == null) {
                    unresolved.add(current)
                    continue
                }
                cls.node.superName?.let { pending.add(it) }
                cls.node.interfaces.forEach { pending.add(it) }
            }
            Closure(visited, unresolved)
        }
}

private fun List<AnnotationNode>?.orEmptyList(): List<AnnotationNode> = this ?: emptyList()

internal fun annotationsOf(node: ClassNode): List<AnnotationNode> = node.visibleAnnotations.orEmptyList() + node.invisibleAnnotations.orEmptyList()

internal fun AnnotationNode.value(name: String): Any? {
    val pairs = values ?: return null
    var i = 0
    while (i + 1 < pairs.size) {
        if (pairs[i] == name) return pairs[i + 1]
        i += 2
    }
    return null
}

internal fun AnnotationNode.stringList(name: String): List<String> =
    when (val v = value(name)) {
        is List<*> -> v.filterIsInstance<String>()
        is String -> listOf(v)
        else -> emptyList()
    }
