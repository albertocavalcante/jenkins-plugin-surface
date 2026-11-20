// SPDX-License-Identifier: Apache-2.0
package jps

import jps.Fixtures.MethodSpec
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Minimal stand-ins for the Jenkins types the classifier recognises, plus method-body helpers. */
internal object Stubs {
    const val STEP_DESC: String = "org/jenkinsci/plugins/workflow/steps/StepDescriptor"
    const val STEP: String = "org/jenkinsci/plugins/workflow/steps/Step"
    const val DESCRIPTOR: String = "hudson/model/Descriptor"
    const val SBS: String = "jenkins/tasks/SimpleBuildStep"
    const val GLOBAL: String = "org/jenkinsci/plugins/workflow/cps/GlobalVariable"
    const val SYMBOL: String = "Lorg/jenkinsci/Symbol;"

    private fun superCtor(superName: String, desc: String = "()V"): MethodSpec =
        MethodSpec("<init>", "()V", body = {
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", desc, false)
            it.visitInsn(Opcodes.RETURN)
        })

    fun ctor(superName: String): MethodSpec = superCtor(superName)

    fun core(): Container {
        val entries = mutableMapOf<String, ByteArray>()
        fun add(name: String, bytes: ByteArray) {
            entries["$name.class"] = bytes
        }
        add("hudson/model/Describable", Fixtures.classBytes("hudson/model/Describable", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT))
        add(DESCRIPTOR, Fixtures.classBytes(DESCRIPTOR, methods = listOf(superCtor("java/lang/Object"))))
        add(SBS, Fixtures.classBytes(SBS, access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT))
        add(
            STEP_DESC,
            Fixtures.classBytes(
                STEP_DESC,
                superName = DESCRIPTOR,
                methods =
                    listOf(superCtor(DESCRIPTOR)) +
                        listOf("takesImplicitBlockArgument", "isAdvanced", "isMetaStep").map { n -> returnBool(n, false) },
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            ),
        )
        add(GLOBAL, Fixtures.classBytes(GLOBAL, methods = listOf(superCtor("java/lang/Object")), access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT))
        add("org/jenkinsci/plugins/structs/describable/CustomDescribableModel", Fixtures.classBytes("org/jenkinsci/plugins/structs/describable/CustomDescribableModel", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT))
        return Container("core.jar", entries)
    }

    fun returnBool(name: String, value: Boolean): MethodSpec =
        MethodSpec(name, "()Z", body = {
            it.visitInsn(if (value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
            it.visitInsn(Opcodes.IRETURN)
        })

    fun returnString(name: String, value: String): MethodSpec =
        MethodSpec(name, "()Ljava/lang/String;", body = {
            it.visitLdcInsn(value)
            it.visitInsn(Opcodes.ARETURN)
        })

    /** A getter that branches, which the interpreter must refuse. */
    fun branching(name: String, desc: String): MethodSpec =
        MethodSpec(name, desc, body = {
            val label = Label()
            it.visitInsn(Opcodes.ICONST_1)
            it.visitJumpInsn(Opcodes.IFEQ, label)
            it.visitLdcInsn("x")
            it.visitInsn(Opcodes.ARETURN)
            it.visitLabel(label)
            it.visitLdcInsn("y")
            it.visitInsn(Opcodes.ARETURN)
        })

    private fun pushClass(mv: MethodVisitor, internal: String) = mv.visitLdcInsn(Type.getObjectType(internal))

    fun contextSingleton(vararg classes: String): MethodSpec =
        MethodSpec("getRequiredContext", "()Ljava/util/Set;", body = {
            pushClass(it, classes[0])
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
            it.visitInsn(Opcodes.ARETURN)
        })

    fun contextEmpty(): MethodSpec =
        MethodSpec("getRequiredContext", "()Ljava/util/Set;", body = {
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptySet", "()Ljava/util/Set;", false)
            it.visitInsn(Opcodes.ARETURN)
        })

    fun contextSetOf(vararg classes: String): MethodSpec =
        MethodSpec("getRequiredContext", "()Ljava/util/Set;", body = {
            classes.forEach { c -> pushClass(it, c) }
            val desc = "(" + "Ljava/lang/Object;".repeat(classes.size) + ")Ljava/util/Set;"
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Set", "of", desc, true)
            it.visitInsn(Opcodes.ARETURN)
        })

    fun contextHashSetAddAll(vararg classes: String): MethodSpec =
        MethodSpec("getRequiredContext", "()Ljava/util/Set;", body = {
            it.visitTypeInsn(Opcodes.NEW, "java/util/HashSet")
            it.visitInsn(Opcodes.DUP)
            it.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false)
            it.visitVarInsn(Opcodes.ASTORE, 1)
            it.visitVarInsn(Opcodes.ALOAD, 1)
            it.visitIntInsn(Opcodes.BIPUSH, classes.size)
            it.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class")
            classes.forEachIndexed { i, c ->
                it.visitInsn(Opcodes.DUP)
                it.visitIntInsn(Opcodes.BIPUSH, i)
                pushClass(it, c)
                it.visitInsn(Opcodes.AASTORE)
            }
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "addAll", "(Ljava/util/Collection;[Ljava/lang/Object;)Z", false)
            it.visitInsn(Opcodes.POP)
            it.visitVarInsn(Opcodes.ALOAD, 1)
            it.visitInsn(Opcodes.ARETURN)
        })

    /** `Descriptor(Class)` style constructor passing a class literal to the superclass. */
    fun ctorWithLiteral(superName: String, literal: String): MethodSpec =
        MethodSpec("<init>", "()V", body = {
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitLdcInsn(Type.getObjectType(literal))
            it.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "(Ljava/lang/Class;)V", false)
            it.visitInsn(Opcodes.RETURN)
        })

    fun concatName(prefix: String, field: String): MethodSpec =
        MethodSpec("getFunctionName", "()Ljava/lang/String;", body = {
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitFieldInsn(Opcodes.GETFIELD, "t/HashDesc", field, "Ljava/lang/String;")
            val bsm =
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcatWithConstants",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false,
                )
            it.visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", bsm, prefix + "\u0001")
            it.visitInsn(Opcodes.ARETURN)
        })
}
