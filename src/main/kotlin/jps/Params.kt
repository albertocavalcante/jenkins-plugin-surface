// SPDX-License-Identifier: Apache-2.0
package jps

import java.io.ByteArrayInputStream
import java.util.Properties
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

private const val DATA_BOUND_CONSTRUCTOR: String = "Lorg/kohsuke/stapler/DataBoundConstructor;"
private const val DATA_BOUND_SETTER: String = "Lorg/kohsuke/stapler/DataBoundSetter;"
private const val DEPRECATED: String = "Ljava/lang/Deprecated;"

private val ATOMIC_CLASSES: Set<String> =
    setOf(
        "java/lang/String", "java/lang/Integer", "java/lang/Long", "java/lang/Boolean", "java/lang/Double", "java/lang/Float",
        "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/net/URL", "hudson/model/Result",
    )

/** JDK collection types are leaves of the hierarchy walk, so the assignable-to-Collection set is a fixed JDK fact. */
private val COLLECTION_CLASSES: Set<String> =
    setOf(
        "java/util/Collection", "java/util/List", "java/util/Set", "java/util/SortedSet", "java/util/NavigableSet", "java/util/Queue",
        "java/util/Deque", "java/util/ArrayList", "java/util/LinkedList", "java/util/HashSet", "java/util/LinkedHashSet",
        "java/util/TreeSet", "java/util/ArrayDeque",
    )

internal class ParamResult(val parameters: List<Parameter>, val gaps: List<Gap>, val firstConstructorType: ClassName?)

/**
 * Reproduces Structs' `DescribableModel` parameter list statically: `@DataBoundConstructor` parameters first (names in
 * Stapler's lookup order), then `@DataBoundSetter` fields and methods alphabetically over the superclass chain.
 */
internal class ParamExtractor(private val store: ClassStore) {
    fun parametersOf(describable: ClassName): ParamResult {
        val cls = store.load(describable.internal) ?: return ParamResult(emptyList(), listOf(Gap("parameters", "unresolvable-type", describable.internal)), null)
        val gaps = mutableListOf<Gap>()
        val out = mutableListOf<Parameter>()
        val ctorNames = mutableSetOf<String>()
        var firstType: ClassName? = null

        val ctors = cls.node.methods.filter { it.name == "<init>" && hasAnnotation(it.visibleAnnotations, it.invisibleAnnotations, DATA_BOUND_CONSTRUCTOR) }
        if (ctors.size > 1) gaps.add(Gap("parameters.constructor", "multiple-databound-constructors"))
        if (cls.node.methods.any { it.name != "<init>" && hasAnnotation(it.visibleAnnotations, it.invisibleAnnotations, DATA_BOUND_CONSTRUCTOR) }) {
            gaps.add(Gap("parameters.constructor", "static-factory-constructor-not-modelled"))
        }
        val ctor = ctors.singleOrNull()
        if (ctor != null) {
            val sigs = methodParamSigs(ctor.signature, ctor.desc)
            firstType = sigs.firstOrNull()?.erasedClass()
            val resolved = constructorNames(cls, ctor, gaps)
            if (resolved == null) {
                if (sigs.isNotEmpty()) gaps.add(Gap("parameters.constructor", "parameter-names-unavailable", describable.dotted))
            } else {
                resolved.names.forEachIndexed { i, name ->
                    ctorNames.add(name)
                    val (hint, evidence) = nullableHint(cls.node, name, ctor, i)
                    out.add(
                        Parameter(
                            name,
                            typeOf(sigs[i], name, gaps),
                            modelRequired = true,
                            usageHint = hint,
                            usageHintEvidence = evidence,
                            origin = ParamOrigin.CONSTRUCTOR,
                            namesSource = resolved.source,
                        ),
                    )
                }
            }
        }

        val setters = sortedMapOf<String, Parameter>()
        var current: LoadedClass? = cls
        var currentName: String? = describable.internal
        while (currentName != null && currentName != Types.OBJECT) {
            val c = current ?: store.load(currentName)
            if (c == null) {
                gaps.add(Gap("parameters", "unresolvable-type", currentName))
                break
            }
            for (field in c.node.fields) {
                if (!hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, DATA_BOUND_SETTER)) continue
                if (field.name in ctorNames) continue
                setters.putIfAbsent(field.name, setterField(field, gaps))
            }
            for (method in c.node.methods) {
                if (!hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, DATA_BOUND_SETTER)) continue
                if ((method.access and (Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_STATIC)) != 0) continue
                if (!method.name.startsWith("set") || method.name.length < 4 || Type.getArgumentTypes(method.desc).size != 1) continue
                val name = decapitalize(method.name.substring(3))
                if (name in ctorNames) continue
                setters.putIfAbsent(name, setterMethod(name, method, gaps))
            }
            currentName = c.node.superName
            current = null
        }
        out.addAll(setters.values)
        return ParamResult(out, gaps, firstType)
    }

    private class Names(val names: List<String>, val source: String)

    private fun constructorNames(cls: LoadedClass, ctor: MethodNode, gaps: MutableList<Gap>): Names? {
        val arity = Type.getArgumentTypes(ctor.desc).size
        val candidates = linkedMapOf<String, List<String>>()
        staplerNames(cls)?.let { candidates["stapler"] = it }
        ctor.parameters?.map { it.name }?.takeIf { it.size == arity && it.none { n -> n == null } }?.let { candidates["method-parameters"] = it }
        localVariableNames(ctor, arity)?.let { candidates["local-variable-table"] = it }
        val (primarySource, primary) = candidates.entries.firstOrNull()?.let { it.key to it.value } ?: return null
        if (primary.size != arity) {
            gaps.add(Gap("parameters.constructor", "names-arity-mismatch", primarySource))
            return null
        }
        val others = candidates.filterKeys { it != primarySource }
        val disagreeing = others.filterValues { it != primary }.keys
        if (disagreeing.isNotEmpty()) gaps.add(Gap("parameters.constructor", "name-sources-disagree", disagreeing.sorted().joinToString(",")))
        return Names(primary, (listOf(primarySource) + others.filterValues { it == primary }.keys).joinToString("+"))
    }

    private fun staplerNames(cls: LoadedClass): List<String>? {
        val path = cls.node.name.replace('$', '/') + ".stapler"
        val bytes = store.resource(cls.container, path) ?: return null
        val props = Properties()
        props.load(ByteArrayInputStream(bytes))
        val value = props.getProperty("constructor") ?: return null
        return if (value.isBlank()) emptyList() else value.split(',').map { it.trim() }.dropLastWhile { it.isEmpty() }
    }

    private fun localVariableNames(ctor: MethodNode, arity: Int): List<String>? {
        val locals = ctor.localVariables ?: return null
        val byIndex = locals.associateBy { it.index }
        var slot = 1
        val names = mutableListOf<String>()
        for (type in Type.getArgumentTypes(ctor.desc)) {
            names.add(byIndex[slot]?.name ?: return null)
            slot += type.size
        }
        return names.takeIf { it.size == arity }
    }

    private fun nullableHint(node: ClassNode, name: String, ctor: MethodNode, index: Int): Pair<UsageHint, String?> {
        fun nullable(annotations: List<AnnotationNode>?) = annotations.orEmpty().any { it.desc.endsWith("/CheckForNull;") || it.desc.endsWith("/Nullable;") }
        val parameterAnnotations = (ctor.visibleParameterAnnotations?.getOrNull(index)).orEmpty() + (ctor.invisibleParameterAnnotations?.getOrNull(index)).orEmpty()
        if (nullable(parameterAnnotations)) return UsageHint.NULLABLE to "nullability annotation on constructor parameter $name"
        val field = node.fields.firstOrNull { it.name == name }
        if (field != null && nullable(field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty())) return UsageHint.NULLABLE to "nullability annotation on field $name"
        val getters = setOf("get" + name.replaceFirstChar { it.uppercase() }, "is" + name.replaceFirstChar { it.uppercase() })
        val getter = node.methods.firstOrNull { it.name in getters && Type.getArgumentTypes(it.desc).isEmpty() }
        if (getter != null && nullable(getter.visibleAnnotations.orEmpty() + getter.invisibleAnnotations.orEmpty())) return UsageHint.NULLABLE to "nullability annotation on getter ${getter.name}"
        return UsageHint.UNKNOWN to null
    }

    private fun setterField(field: FieldNode, gaps: MutableList<Gap>): Parameter =
        Parameter(
            field.name,
            typeOf(fieldSig(field.signature, field.desc), field.name, gaps),
            modelRequired = false,
            deprecated = hasAnnotation(field.visibleAnnotations, null, DEPRECATED) || (field.access and Opcodes.ACC_DEPRECATED) != 0,
            origin = ParamOrigin.SETTER_FIELD,
        )

    private fun setterMethod(name: String, method: MethodNode, gaps: MutableList<Gap>): Parameter =
        Parameter(
            name,
            typeOf(methodParamSigs(method.signature, method.desc).first(), name, gaps),
            modelRequired = false,
            deprecated = hasAnnotation(method.visibleAnnotations, null, DEPRECATED) || (method.access and Opcodes.ACC_DEPRECATED) != 0,
            origin = ParamOrigin.SETTER_METHOD,
        )

    private fun typeOf(sig: Sig, param: String, gaps: MutableList<Gap>): TypeNode {
        val java = sig.render()
        return when (sig) {
            is Sig.Prim -> TypeNode(TypeCategory.ATOMIC, java)
            is Sig.Arr -> TypeNode(TypeCategory.ARRAY, java, element = typeOf(sig.element, param, gaps))
            is Sig.TVar, Sig.Wild -> TypeNode(TypeCategory.UNSUPPORTED, java)
            is Sig.Cls -> classType(sig, java, param, gaps)
        }
    }

    private fun classType(sig: Sig.Cls, java: String, param: String, gaps: MutableList<Gap>): TypeNode {
        val name = sig.internalName
        if (name in ATOMIC_CLASSES) return TypeNode(TypeCategory.ATOMIC, java)
        if (name in COLLECTION_CLASSES) {
            val element = sig.args.singleOrNull()
            return if (element == null || element == Sig.Wild) {
                TypeNode(TypeCategory.ARRAY, java, element = TypeNode(TypeCategory.UNSUPPORTED, "?"))
            } else {
                TypeNode(TypeCategory.ARRAY, java, element = typeOf(element, param, gaps))
            }
        }
        if (name.startsWith("java/")) return TypeNode(TypeCategory.UNSUPPORTED, java)
        val loaded = store.load(name)
        if (loaded == null) {
            gaps.add(Gap("parameters.$param.type", "unresolvable-type", name))
            return TypeNode(TypeCategory.OBJECT, java, nested = ClassName(name))
        }
        if ((loaded.node.access and Opcodes.ACC_ENUM) != 0) {
            val values = loaded.node.fields.filter { (it.access and Opcodes.ACC_ENUM) != 0 }.map { it.name }
            return TypeNode(TypeCategory.ENUM, java, enumValues = values)
        }
        return TypeNode(TypeCategory.OBJECT, java, nested = ClassName(name))
    }

    private fun hasAnnotation(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?, desc: String): Boolean =
        visible.orEmpty().any { it.desc == desc } || invisible.orEmpty().any { it.desc == desc }

    /** `java.beans.Introspector.decapitalize`: keep names whose first two characters are upper case. */
    private fun decapitalize(name: String): String =
        if (name.length > 1 && name[0].isUpperCase() && name[1].isUpperCase()) name else name.replaceFirstChar { it.lowercase() }
}
