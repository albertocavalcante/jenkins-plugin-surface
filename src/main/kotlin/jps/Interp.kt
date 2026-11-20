// SPDX-License-Identifier: Apache-2.0
package jps

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/** Abstract values of the tiny symbolic machine. Everything it cannot model becomes [Unknown]. */
internal sealed interface V {
    data class Str(val value: String) : V

    data class Int(val value: kotlin.Int) : V

    data class Cls(val internalName: String) : V

    data object This : V

    data object Locale : V

    data object Unknown : V

    /** A mutable collection or array, shared by reference like the object it models. */
    class Coll(val items: MutableList<V> = mutableListOf(), var tainted: Boolean = false) : V
}

internal sealed interface Eval {
    data class Found(val value: V) : Eval

    /** The method does not exist on the resolved chain (callers apply the documented API default). */
    data object NotFound : Eval

    data class Failed(val reason: String) : Eval
}

private class Unsupported(val reason: String) : RuntimeException(reason)

private val COLLECTION_TYPES: Set<String> =
    setOf("java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/ArrayList", "java/util/LinkedList")

private val SET_FACTORIES: Set<String> = setOf("java/util/Set", "java/util/List", "com/google/common/collect/ImmutableSet", "com/google/common/collect/ImmutableList")

private const val MAX_DEPTH: Int = 12

/**
 * Evaluates linear (branch-free) methods over constants. It models just the shapes Jenkins steps use for
 * `getFunctionName`, `getRequiredContext` and boolean flags; anything else fails with a reason, never a guess.
 */
internal class Interp(private val store: ClassStore) {
    /** Evaluates the no-argument method [name] on [start] using the most specific implementation on the superclass chain. */
    fun evalNoArg(start: String, name: String, desc: String, stopAt: String? = null): Eval {
        var current: String? = start
        while (current != null) {
            if (current == stopAt) return Eval.NotFound
            val cls = store.load(current)
            if (cls == null) {
                return if (current == Types.OBJECT) Eval.NotFound else Eval.Failed("unresolvable-type:$current")
            }
            val method = cls.node.methods.firstOrNull { it.name == name && it.desc == desc && (it.access and Opcodes.ACC_ABSTRACT) == 0 }
            if (method != null) {
                val fields = hashMapOf<String, V>()
                try {
                    runConstructors(start, fields)
                } catch (_: Unsupported) {
                    // A half-run constructor may have left stale values behind: forget everything it set.
                    fields.clear()
                }
                return try {
                    Eval.Found(run(cls.node, method, fields, emptyList(), 0))
                } catch (e: Unsupported) {
                    Eval.Failed(e.reason)
                }
            }
            current = cls.node.superName
        }
        return Eval.NotFound
    }

    private fun runConstructors(className: String, fields: MutableMap<String, V>) {
        val node = store.load(className)?.node ?: return
        val ctors = node.methods.filter { it.name == "<init>" }
        val ctor = ctors.singleOrNull() ?: ctors.firstOrNull { it.desc == "()V" } ?: return
        val args = Type.getArgumentTypes(ctor.desc).map { V.Unknown }
        run(node, ctor, fields, args, 0)
    }

    private fun run(owner: ClassNode, method: MethodNode, fields: MutableMap<String, V>, args: List<V>, depth: Int): V {
        if (depth > MAX_DEPTH) throw Unsupported("call-depth")
        val locals = hashMapOf<Int, V>()
        var slot = 0
        if ((method.access and Opcodes.ACC_STATIC) == 0) locals[slot++] = V.This
        Type.getArgumentTypes(method.desc).forEachIndexed { i, t ->
            locals[slot] = args.getOrElse(i) { V.Unknown }
            slot += t.size
        }
        val stack = ArrayDeque<V>()
        fun pop(): V = stack.removeLastOrNull() ?: throw Unsupported("stack-underflow")
        fun popArgs(desc: String): List<V> = List(Type.getArgumentTypes(desc).size) { pop() }.reversed()
        var insn: AbstractInsnNode? = method.instructions.first
        while (insn != null) {
            when (insn) {
                is LabelNode, is LineNumberNode, is FrameNode -> Unit
                is InsnNode -> {
                    when (insn.opcode) {
                        Opcodes.ACONST_NULL -> stack.addLast(V.Unknown)
                        in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> stack.addLast(V.Int(insn.opcode - Opcodes.ICONST_0))
                        Opcodes.DUP -> stack.addLast(stack.lastOrNull() ?: throw Unsupported("stack-underflow"))
                        Opcodes.POP -> pop()
                        Opcodes.AASTORE -> {
                            val value = pop()
                            val index = pop()
                            val array = pop()
                            if (array is V.Coll && index is V.Int) {
                                while (array.items.size <= index.value) array.items.add(V.Unknown)
                                array.items[index.value] = value
                            } else if (array is V.Coll) {
                                array.tainted = true
                            }
                        }
                        Opcodes.ARETURN, Opcodes.IRETURN -> return pop()
                        Opcodes.RETURN -> return V.Unknown
                        else -> throw Unsupported("opcode-${insn.opcode}")
                    }
                }
                is IntInsnNode -> {
                    when (insn.opcode) {
                        Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.addLast(V.Int(insn.operand))
                        Opcodes.NEWARRAY -> {
                            pop()
                            stack.addLast(V.Coll())
                        }
                        else -> throw Unsupported("opcode-${insn.opcode}")
                    }
                }
                is LdcInsnNode -> {
                    val cst = insn.cst
                    stack.addLast(
                        when {
                            cst is String -> V.Str(cst)
                            cst is Int -> V.Int(cst)
                            cst is Type && cst.sort == Type.OBJECT -> V.Cls(cst.internalName)
                            else -> V.Unknown
                        },
                    )
                }
                is VarInsnNode -> {
                    when (insn.opcode) {
                        Opcodes.ALOAD, Opcodes.ILOAD -> stack.addLast(locals[insn.`var`] ?: V.Unknown)
                        Opcodes.ASTORE, Opcodes.ISTORE -> locals[insn.`var`] = pop()
                        else -> throw Unsupported("opcode-${insn.opcode}")
                    }
                }
                is IincInsnNode -> throw Unsupported("iinc")
                is TypeInsnNode -> {
                    when (insn.opcode) {
                        Opcodes.NEW -> stack.addLast(if (insn.desc in COLLECTION_TYPES) V.Coll() else V.Unknown)
                        Opcodes.ANEWARRAY -> {
                            pop()
                            stack.addLast(V.Coll())
                        }
                        Opcodes.CHECKCAST -> Unit
                        else -> throw Unsupported("opcode-${insn.opcode}")
                    }
                }
                is FieldInsnNode -> field(insn, stack, fields) { pop() }
                is MethodInsnNode -> {
                    val callArgs = popArgs(insn.desc)
                    val result = invoke(insn, callArgs, stack, fields, depth) { pop() }
                    if (Type.getReturnType(insn.desc).sort != Type.VOID) stack.addLast(result)
                }
                is InvokeDynamicInsnNode -> {
                    val callArgs = popArgs(insn.desc)
                    stack.addLast(concat(insn, callArgs))
                }
                else -> throw Unsupported("insn-${insn.opcode}")
            }
            insn = insn.next
        }
        throw Unsupported("fell-off-method")
    }

    private fun field(insn: FieldInsnNode, stack: ArrayDeque<V>, fields: MutableMap<String, V>, pop: () -> V) {
        when (insn.opcode) {
            Opcodes.GETSTATIC -> {
                if (insn.owner == "java/util/Locale" && insn.name == "ENGLISH") {
                    stack.addLast(V.Locale)
                } else {
                    val constant = store.load(insn.owner)?.node?.fields?.firstOrNull { it.name == insn.name && it.desc == insn.desc }?.value
                    stack.addLast(
                        when (constant) {
                            is String -> V.Str(constant)
                            is Int -> V.Int(constant)
                            else -> V.Unknown
                        },
                    )
                }
            }
            Opcodes.GETFIELD -> {
                val target = pop()
                stack.addLast(if (target is V.This) fields["${insn.owner}.${insn.name}"] ?: V.Unknown else V.Unknown)
            }
            Opcodes.PUTFIELD -> {
                val value = pop()
                val target = pop()
                if (target is V.This) fields["${insn.owner}.${insn.name}"] = value
            }
            Opcodes.PUTSTATIC -> pop()
            else -> throw Unsupported("opcode-${insn.opcode}")
        }
    }

    private fun flatten(desc: String, args: List<V>): List<V> =
        Type.getArgumentTypes(desc).zip(args).flatMap { (type, value) ->
            if (type.sort == Type.ARRAY && value is V.Coll) value.items else listOf(value)
        }

    private fun invoke(
        insn: MethodInsnNode,
        args: List<V>,
        stack: ArrayDeque<V>,
        fields: MutableMap<String, V>,
        depth: Int,
        pop: () -> V,
    ): V {
        val owner = insn.owner
        val name = insn.name
        if (insn.opcode == Opcodes.INVOKESTATIC) {
            return when {
                owner == "java/util/Collections" && name == "singleton" -> V.Coll(mutableListOf(args[0]))
                owner == "java/util/Collections" && name == "emptySet" -> V.Coll()
                owner == "java/util/Collections" && name.startsWith("unmodifiable") -> args[0]
                owner == "java/util/Collections" && name == "addAll" -> {
                    val target = args[0]
                    if (target is V.Coll) {
                        target.items.addAll(flatten(insn.desc, args).drop(1))
                        if (args.drop(1).any { it !is V.Coll || it.tainted }) target.tainted = true
                    }
                    V.Int(1)
                }
                owner == "java/util/Arrays" && name == "asList" -> V.Coll(flatten(insn.desc, args).toMutableList(), tainted = anyTainted(args))
                owner in SET_FACTORIES && name == "of" -> V.Coll(flatten(insn.desc, args).toMutableList(), tainted = anyTainted(args))
                owner == "java/lang/String" && name == "valueOf" -> args[0]
                else -> {
                    taint(args)
                    V.Unknown
                }
            }
        }
        val receiver = pop()
        if (name == "<init>") {
            if (receiver is V.Coll) {
                val source = args.firstOrNull { it is V.Coll } as? V.Coll
                if (source != null) {
                    receiver.items.addAll(source.items)
                    if (source.tainted) receiver.tainted = true
                } else if (args.any { it !is V.Int }) {
                    receiver.tainted = true
                }
            } else if (receiver is V.This) {
                val target = store.load(owner)
                if (target != null) {
                    val ctor = target.node.methods.firstOrNull { it.name == "<init>" && it.desc == insn.desc }
                    if (ctor != null) {
                        try {
                            run(target.node, ctor, fields, args, depth + 1)
                        } catch (_: Unsupported) {
                            // Unmodelled constructor: fields set before the failure are no longer trustworthy.
                            fields.clear()
                        }
                    }
                }
            }
            return V.Unknown
        }
        return when {
            owner == "java/lang/String" && (name == "toLowerCase" || name == "toUpperCase") && receiver is V.Str && args.singleOrNull() == V.Locale ->
                V.Str(if (name == "toLowerCase") receiver.value.lowercase() else receiver.value.uppercase())
            (name == "add") && receiver is V.Coll && args.size == 1 -> {
                receiver.items.add(args[0])
                V.Int(1)
            }
            (name == "addAll") && receiver is V.Coll && args.size == 1 -> {
                val source = args[0] as? V.Coll
                if (source == null || source.tainted) receiver.tainted = true
                source?.let { receiver.items.addAll(it.items) }
                V.Int(1)
            }
            else -> {
                taint(args + receiver)
                V.Unknown
            }
        }
    }

    private fun anyTainted(values: List<V>): Boolean = values.any { it is V.Coll && it.tainted }

    /** An unmodelled operation touched these collections: whatever they hold can no longer be trusted. */
    private fun taint(values: List<V>) {
        values.forEach { if (it is V.Coll) it.tainted = true }
    }

    private fun concat(insn: InvokeDynamicInsnNode, args: List<V>): V {
        if (insn.bsm.owner != "java/lang/invoke/StringConcatFactory" || insn.bsm.name != "makeConcatWithConstants") return V.Unknown
        val recipe = insn.bsmArgs.firstOrNull() as? String ?: return V.Unknown
        val out = StringBuilder()
        var argIndex = 0
        var constIndex = 1
        for (ch in recipe) {
            when (ch) {
                '\u0001' -> {
                    when (val a = args.getOrNull(argIndex++)) {
                        is V.Str -> out.append(a.value)
                        is V.Int -> out.append(a.value)
                        else -> return V.Unknown
                    }
                }
                '\u0002' -> out.append(insn.bsmArgs.getOrNull(constIndex++)?.toString() ?: return V.Unknown)
                else -> out.append(ch)
            }
        }
        return V.Str(out.toString())
    }
}

internal fun Eval.string(): Pair<String?, String?> =
    when (this) {
        is Eval.Found -> (value as? V.Str)?.let { it.value to null } ?: (null to "not-constant")
        Eval.NotFound -> null to "method-not-found"
        is Eval.Failed -> null to reason
    }

internal fun Eval.bool(default: Boolean?): Pair<Boolean?, String?> =
    when (this) {
        is Eval.Found -> (value as? V.Int)?.let { (it.value != 0) to null } ?: (null to "not-constant")
        Eval.NotFound -> default to (if (default == null) "method-not-found" else null)
        is Eval.Failed -> null to reason
    }

internal fun Eval.classSet(): Pair<List<ClassName>?, String?> =
    when (this) {
        is Eval.Found -> {
            val coll = (value as? V.Coll)?.takeUnless { it.tainted }
            val names = coll?.items?.map { (it as? V.Cls)?.internalName }
            if (names == null || names.any { it == null }) null to "not-constant" else names.filterNotNull().map(::ClassName).distinct() to null
        }
        Eval.NotFound -> null to "method-not-found"
        is Eval.Failed -> null to reason
    }
