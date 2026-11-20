// SPDX-License-Identifier: Apache-2.0
package jps

import jps.Fixtures.AnnSpec
import jps.Fixtures.MethodSpec
import jps.Stubs.DESCRIPTOR
import jps.Stubs.STEP_DESC
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class ClassifyTest {
    private fun classify(classes: Map<String, ByteArray>, vararg keys: String): List<Record> {
        val target = Container("WEB-INF/lib/t.jar", classes.mapKeys { "${it.key}.class" })
        val classifier = Classifier(ClassStore(listOf(target), listOf(Stubs.core())), "t")
        return keys.map { classifier.classify(ExtensionRef(it, setOf("hudson.Extension"), target.path)) }
    }

    private fun one(classes: Map<String, ByteArray>, key: String): Record = classify(classes, key).single()

    private fun stepDescriptor(name: String, outer: String?, vararg methods: MethodSpec): ByteArray =
        Fixtures.classBytes(name, superName = STEP_DESC, methods = listOf(Stubs.ctor(STEP_DESC)) + methods, innerOf = outer)

    private val sym = Stubs.SYMBOL

    @Test
    fun literalFunctionNameBlockFlagAndContext() {
        val classes =
            mapOf(
                "t/RetryStep" to Fixtures.classBytes("t/RetryStep"),
                "t/RetryStep\$DescriptorImpl" to
                    stepDescriptor(
                        "t/RetryStep\$DescriptorImpl",
                        "t/RetryStep",
                        Stubs.returnString("getFunctionName", "retry"),
                        Stubs.returnBool("takesImplicitBlockArgument", true),
                        Stubs.contextSingleton("hudson/model/Run"),
                    ),
            )
        val r = one(classes, "t.RetryStep\$DescriptorImpl")
        assertEquals(RecordKind.STEP, r.kind)
        assertEquals("retry", r.name)
        assertEquals("t/RetryStep", r.describableClass?.internal)
        assertEquals(true, r.step?.takesBlock)
        assertEquals(false, r.step?.advanced)
        assertEquals(false, r.step?.metastep)
        assertEquals(listOf("hudson/model/Run"), r.step?.requiredContext?.map { it.internal })
        assertEquals(Confidence.EXACT, r.confidence)
        assertEquals("step:t.RetryStep\$DescriptorImpl", r.id)
        assertTrue(r.links.any { it.rel == LinkRel.PLUGIN })
    }

    @Test
    fun constructorArgumentTrackingResolvesComputedNames() {
        val hash =
            Fixtures.classBytes(
                "t/HashDesc",
                superName = STEP_DESC,
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                fields = listOf(Fixtures.FieldSpec("algo", "Ljava/lang/String;")),
                methods =
                    listOf(
                        MethodSpec("<init>", "(Ljava/lang/String;)V", body = {
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitMethodInsn(Opcodes.INVOKESPECIAL, STEP_DESC, "<init>", "()V", false)
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitVarInsn(Opcodes.ALOAD, 1)
                            it.visitFieldInsn(Opcodes.PUTFIELD, "t/HashDesc", "algo", "Ljava/lang/String;")
                            it.visitInsn(Opcodes.RETURN)
                        }),
                        MethodSpec("getFunctionName", "()Ljava/lang/String;", body = {
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitFieldInsn(Opcodes.GETFIELD, "t/HashDesc", "algo", "Ljava/lang/String;")
                            it.visitFieldInsn(Opcodes.GETSTATIC, "java/util/Locale", "ENGLISH", "Ljava/util/Locale;")
                            it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;", false)
                            it.visitInsn(Opcodes.ARETURN)
                        }),
                    ),
            )
        fun sub(name: String, algo: String) =
            Fixtures.classBytes(
                name,
                superName = "t/HashDesc",
                methods = listOf(
                    MethodSpec("<init>", "()V", body = {
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                        it.visitLdcInsn(algo)
                        it.visitMethodInsn(Opcodes.INVOKESPECIAL, "t/HashDesc", "<init>", "(Ljava/lang/String;)V", false)
                        it.visitInsn(Opcodes.RETURN)
                    }),
                ),
            )
        val verify =
            Fixtures.classBytes(
                "t/VerifyDesc",
                superName = "t/HashDesc",
                methods = listOf(
                    MethodSpec("<init>", "()V", body = {
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                        it.visitLdcInsn("SHA256")
                        it.visitMethodInsn(Opcodes.INVOKESPECIAL, "t/HashDesc", "<init>", "(Ljava/lang/String;)V", false)
                        it.visitInsn(Opcodes.RETURN)
                    }),
                    Stubs.concatName("verify", "algo"),
                ),
            )
        val records = classify(mapOf("t/HashDesc" to hash, "t/Sha1Desc" to sub("t/Sha1Desc", "SHA1"), "t/VerifyDesc" to verify), "t.Sha1Desc", "t.VerifyDesc")
        assertEquals(listOf("sha1", "verifySHA256"), records.map { it.name })
    }

    @Test
    fun nonConstantFunctionNameBecomesAnUnknownRecord() {
        val r = one(mapOf("t/D" to stepDescriptor("t/D", null, Stubs.branching("getFunctionName", "()Ljava/lang/String;"))), "t.D")
        assertEquals(RecordKind.UNKNOWN, r.kind)
        assertEquals("function-name-not-constant", r.reason)
        assertEquals(Confidence.UNKNOWN, r.confidence)
    }

    @Test
    fun requiredContextIdioms() {
        fun ctx(m: MethodSpec): List<String>? =
            one(mapOf("t/D" to stepDescriptor("t/D", null, Stubs.returnString("getFunctionName", "x"), m)), "t.D").step?.requiredContext?.map { it.internal }
        assertEquals(listOf("a/A"), ctx(Stubs.contextSingleton("a/A")))
        assertEquals(emptyList<String>(), ctx(Stubs.contextEmpty()))
        assertEquals(listOf("a/A", "b/B"), ctx(Stubs.contextSetOf("a/A", "b/B"))?.sorted())
        assertEquals(listOf("a/A", "b/B", "c/C"), ctx(Stubs.contextHashSetAddAll("a/A", "b/B", "c/C"))?.sorted())
    }

    @Test
    fun unsupportedContextShapeIsAGapNotAGuess() {
        val r = one(mapOf("t/D" to stepDescriptor("t/D", null, Stubs.returnString("getFunctionName", "x"), Stubs.branching("getRequiredContext", "()Ljava/util/Set;"))), "t.D")
        assertEquals(RecordKind.STEP, r.kind)
        assertNull(r.step?.requiredContext)
        assertTrue(r.gaps.any { it.field == "step.required_context" })
        assertEquals(Confidence.INFERRED, r.confidence)
    }

    @Test
    fun missingRequiredContextIsAGap() {
        val r = one(mapOf("t/D" to stepDescriptor("t/D", null, Stubs.returnString("getFunctionName", "x"))), "t.D")
        assertTrue(r.gaps.any { it.field == "step.required_context" })
    }

    @Test
    fun globalVariableNameFromConstant() {
        val global = Fixtures.classBytes("t/DockerDSL", superName = Stubs.GLOBAL, methods = listOf(Stubs.ctor(Stubs.GLOBAL), Stubs.returnString("getName", "docker")))
        val r = one(mapOf("t/DockerDSL" to global), "t.DockerDSL")
        assertEquals(RecordKind.GLOBAL_VARIABLE, r.kind)
        assertEquals("docker", r.name)
    }

    private fun describable(name: String, vararg interfaces: String): ByteArray = Fixtures.classBytes(name, interfaces = interfaces.toList())

    private fun plainDescriptor(name: String, outer: String, symbol: String?): ByteArray =
        Fixtures.classBytes(
            name,
            superName = DESCRIPTOR,
            methods = listOf(Stubs.ctor(DESCRIPTOR)),
            innerOf = outer,
            annotationSpecs = if (symbol == null) emptyList() else listOf(AnnSpec(sym, mapOf("value" to listOf(symbol)))),
        )

    @Test
    fun describableKindsFollowTheImplementedInterface() {
        val classes =
            mapOf(
                "t/Pub" to describable("t/Pub", Stubs.SBS),
                "t/Pub\$DescriptorImpl" to plainDescriptor("t/Pub\$DescriptorImpl", "t/Pub", "pub"),
                "t/Nested" to describable("t/Nested"),
                "t/Nested\$DescriptorImpl" to plainDescriptor("t/Nested\$DescriptorImpl", "t/Nested", "nested"),
                "t/Bare" to describable("t/Bare"),
                "t/Bare\$DescriptorImpl" to plainDescriptor("t/Bare\$DescriptorImpl", "t/Bare", null),
            )
        val records = classify(classes, "t.Pub\$DescriptorImpl", "t.Nested\$DescriptorImpl", "t.Bare\$DescriptorImpl")
        assertEquals(
            listOf(RecordKind.STEP_WRAPPED_DESCRIBABLE, RecordKind.NESTED_ONLY_SYMBOL, RecordKind.NOT_PIPELINE_FACING),
            records.map { it.kind },
        )
        assertEquals(listOf("pub"), records[0].symbols)
        assertEquals("descriptor-without-symbol", records[2].reason)
    }

    @Test
    fun classLiteralPassedToSuperWinsOverEnclosingClass() {
        val withLiteral =
            Fixtures.classBytes(
                "t/Other\$DescriptorImpl",
                superName = DESCRIPTOR,
                methods = listOf(Stubs.ctorWithLiteral(DESCRIPTOR, "t/Target")),
                innerOf = "t/Other",
                annotationSpecs = listOf(AnnSpec(sym, mapOf("value" to listOf("lit")))),
            )
        val descriptorBase = Fixtures.classBytes(DESCRIPTOR, methods = listOf(MethodSpec("<init>", "(Ljava/lang/Class;)V", body = { it.visitInsn(Opcodes.RETURN) })))
        val target = Container("WEB-INF/lib/t.jar", mapOf("t/Other\$DescriptorImpl.class" to withLiteral, "t/Target.class" to describable("t/Target", Stubs.SBS), "t/Other.class" to describable("t/Other")))
        val core = Stubs.core()
        val coreWithCtor = Container("core.jar", core.entries + ("$DESCRIPTOR.class" to descriptorBase))
        val r = Classifier(ClassStore(listOf(target), listOf(coreWithCtor)), "t").classify(ExtensionRef("t.Other\$DescriptorImpl", setOf("hudson.Extension"), target.path))
        assertEquals("t/Target", r.describableClass?.internal)
        assertEquals(RecordKind.STEP_WRAPPED_DESCRIBABLE, r.kind)
    }

    @Test
    fun negativeClaimsRequireAResolvedHierarchy() {
        val resolved = Fixtures.classBytes("t/Plain")
        val dangling = Fixtures.classBytes("t/Dangling", superName = "missing/Base")
        val records = classify(mapOf("t/Plain" to resolved, "t/Dangling" to dangling), "t.Plain", "t.Dangling")
        assertEquals(RecordKind.NOT_PIPELINE_FACING, records[0].kind)
        assertEquals(RecordKind.UNKNOWN, records[1].kind)
        assertEquals("unresolvable-type", records[1].reason)
        assertTrue(records[1].gaps.any { it.detail == "missing/Base" })
    }

    @Test
    fun anUnwalkableHierarchyIsNeverGuessedAsAStepButADirectSubclassIs() {
        val d = Fixtures.classBytes("t/D", superName = "elsewhere/MidDescriptorThatExtendsStepDescriptor")
        // Hierarchy cannot be walked to StepDescriptor, so this must NOT be guessed as a step.
        assertEquals(RecordKind.UNKNOWN, one(mapOf("t/D" to d), "t.D").kind)
        val direct = Fixtures.classBytes("t/E", superName = STEP_DESC, methods = listOf(Stubs.ctor(STEP_DESC), Stubs.returnString("getFunctionName", "e")))
        assertEquals(RecordKind.STEP, one(mapOf("t/E" to direct), "t.E").kind)
    }

    @Test
    fun trueFlagsAreReadFromBytecode() {
        val d =
            stepDescriptor(
                "t/D",
                "t",
                Stubs.returnString("getFunctionName", "d"),
                Stubs.returnBool("isAdvanced", true),
                Stubs.returnBool("isMetaStep", true),
                Stubs.returnBool("takesImplicitBlockArgument", true),
                Stubs.contextEmpty(),
            )
        val r = one(mapOf("t/D" to d), "t.D")
        assertEquals(true, r.step?.advanced)
        assertEquals(true, r.step?.metastep)
        assertEquals(true, r.step?.takesBlock)
    }

    @Test
    fun staleConstructorStateIsDiscardedWhenTheConstructorCannotBeModelled() {
        val base =
            Fixtures.classBytes(
                "t/Base",
                superName = STEP_DESC,
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                fields = listOf(Fixtures.FieldSpec("n", "Ljava/lang/String;")),
                methods =
                    listOf(
                        MethodSpec("<init>", "()V", body = {
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitMethodInsn(Opcodes.INVOKESPECIAL, STEP_DESC, "<init>", "()V", false)
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitLdcInsn("a")
                            it.visitFieldInsn(Opcodes.PUTFIELD, "t/Base", "n", "Ljava/lang/String;")
                            val skip = Label()
                            it.visitInsn(Opcodes.ICONST_1)
                            it.visitJumpInsn(Opcodes.IFEQ, skip)
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitLdcInsn("b")
                            it.visitFieldInsn(Opcodes.PUTFIELD, "t/Base", "n", "Ljava/lang/String;")
                            it.visitLabel(skip)
                            it.visitInsn(Opcodes.RETURN)
                        }),
                        MethodSpec("getFunctionName", "()Ljava/lang/String;", body = {
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitFieldInsn(Opcodes.GETFIELD, "t/Base", "n", "Ljava/lang/String;")
                            it.visitInsn(Opcodes.ARETURN)
                        }),
                    ),
            )
        val sub = Fixtures.classBytes("t/Sub", superName = "t/Base", methods = listOf(Stubs.ctor("t/Base")))
        val r = one(mapOf("t/Base" to base, "t/Sub" to sub), "t.Sub")
        assertEquals(RecordKind.UNKNOWN, r.kind)
    }

    @Test
    fun aCollectionTouchedByAnUnmodelledOperationIsNotReportedAsExact() {
        val tainted =
            MethodSpec("getRequiredContext", "()Ljava/util/Set;", body = {
                it.visitTypeInsn(Opcodes.NEW, "java/util/HashSet")
                it.visitInsn(Opcodes.DUP)
                it.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false)
                it.visitVarInsn(Opcodes.ASTORE, 1)
                it.visitVarInsn(Opcodes.ALOAD, 1)
                it.visitLdcInsn(org.objectweb.asm.Type.getObjectType("a/A"))
                it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashSet", "add", "(Ljava/lang/Object;)Z", false)
                it.visitInsn(Opcodes.POP)
                it.visitVarInsn(Opcodes.ALOAD, 1)
                it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashSet", "clear", "()V", false)
                it.visitVarInsn(Opcodes.ALOAD, 1)
                it.visitInsn(Opcodes.ARETURN)
            })
        val r = one(mapOf("t/D" to stepDescriptor("t/D", "t", Stubs.returnString("getFunctionName", "d"), tainted)), "t.D")
        assertNull(r.step?.requiredContext)
        assertTrue(r.gaps.any { it.field == "step.required_context" })
    }

    @Test
    fun anUnrelatedClassLiteralArgumentIsNotTakenAsTheDescribable() {
        val ctor =
            MethodSpec("<init>", "()V", body = {
                it.visitVarInsn(Opcodes.ALOAD, 0)
                it.visitMethodInsn(Opcodes.INVOKESPECIAL, DESCRIPTOR, "<init>", "()V", false)
                it.visitTypeInsn(Opcodes.NEW, "t/Helper")
                it.visitInsn(Opcodes.DUP)
                it.visitLdcInsn(org.objectweb.asm.Type.getObjectType("t/Unrelated"))
                it.visitMethodInsn(Opcodes.INVOKESPECIAL, "t/Helper", "<init>", "(Ljava/lang/Class;)V", false)
                it.visitInsn(Opcodes.POP)
                it.visitInsn(Opcodes.RETURN)
            })
        val d = Fixtures.classBytes("t/Owner\$DescriptorImpl", superName = DESCRIPTOR, methods = listOf(ctor), innerOf = "t/Owner", annotationSpecs = listOf(AnnSpec(sym, mapOf("value" to listOf("o")))))
        val r = one(mapOf("t/Owner\$DescriptorImpl" to d, "t/Owner" to describable("t/Owner"), "t/Unrelated" to describable("t/Unrelated")), "t.Owner\$DescriptorImpl")
        assertEquals("t/Owner", r.describableClass?.internal)
    }

    @Test
    fun wrapperGlobalSetDeclarativeAndPostConditionKinds() {
        val classes =
            mapOf(
                "t/W" to Fixtures.classBytes("t/W", superName = "jenkins/tasks/SimpleBuildWrapper", methods = listOf(Stubs.ctor("jenkins/tasks/SimpleBuildWrapper"))),
                "t/W\$DescriptorImpl" to plainDescriptor("t/W\$DescriptorImpl", "t/W", "w"),
                "t/Vars" to Fixtures.classBytes("t/Vars", superName = Types.GLOBAL_VARIABLE_SET, methods = listOf(Stubs.ctor(Types.GLOBAL_VARIABLE_SET))),
                "t/A\$DescriptorImpl" to Fixtures.classBytes("t/A\$DescriptorImpl", superName = Types.AGENT_DESCRIPTOR, methods = listOf(Stubs.ctor(Types.AGENT_DESCRIPTOR)), innerOf = "t/A", annotationSpecs = listOf(AnnSpec(sym, mapOf("value" to listOf("any"))))),
                "t/A" to describable("t/A"),
                "t/Always" to Fixtures.classBytes("t/Always", superName = Types.BUILD_CONDITION, methods = listOf(Stubs.ctor(Types.BUILD_CONDITION)), annotationSpecs = listOf(AnnSpec(sym, mapOf("value" to listOf("always"))))),
            )
        val r = classify(classes, "t.W\$DescriptorImpl", "t.Vars", "t.A\$DescriptorImpl", "t.Always")
        assertEquals(listOf(RecordKind.WRAP_WRAPPED_DESCRIBABLE, RecordKind.GLOBAL_VARIABLE, RecordKind.DECLARATIVE_ELEMENT, RecordKind.DECLARATIVE_ELEMENT), r.map { it.kind })
        assertTrue(r[1].gaps.any { it.reason == "dynamic-global-set" })
        assertEquals(listOf("agent"), r[2].declarativeDirectives)
        assertEquals(listOf("post"), r[3].declarativeDirectives)
        assertEquals(listOf("always"), r[3].symbols)
        assertTrue(r[2].links.any { it.rel == LinkRel.SYNTAX && it.url.endsWith("#agent") })
    }

    @Test
    fun aStepWithoutADescribableTypeCarriesAGap() {
        val r = one(mapOf("t/D" to Fixtures.classBytes("t/D", superName = STEP_DESC, methods = listOf(Stubs.ctor(STEP_DESC), Stubs.returnString("getFunctionName", "d"), Stubs.contextEmpty()))), "t.D")
        assertTrue(r.gaps.any { it.field == "describable_class" })
        assertEquals(Confidence.INFERRED, r.confidence)
    }

    @Test
    fun flagsFromAnnotationsAndInterfaces() {
        val ext = AnnSpec(Types.EXTENSION_DESC, mapOf("ordinal" to -1.0, "optional" to true))
        val d =
            Fixtures.classBytes(
                "t/D",
                superName = STEP_DESC,
                interfaces = listOf("org/jenkinsci/plugins/structs/describable/CustomDescribableModel"),
                annotationSpecs = listOf(ext),
                methods = listOf(Stubs.ctor(STEP_DESC), Stubs.returnString("getFunctionName", "d"), MethodSpec("invokeMethod", "()V")),
            )
        val r = one(mapOf("t/D" to d), "t.D")
        assertTrue(r.argsRewritten)
        assertTrue(r.dynamicDispatch)
        assertEquals(-1.0, r.extension?.ordinal)
        assertEquals(true, r.extension?.optional)
        assertFalse(one(mapOf("t/E" to Fixtures.classBytes("t/E", superName = STEP_DESC, methods = listOf(Stubs.ctor(STEP_DESC), Stubs.returnString("getFunctionName", "e")))), "t.E").argsRewritten)
    }

    @Test
    fun memberExtensionsAndMissingClassesAreUnknownWithReasons() {
        val classifier = Classifier(ClassStore(listOf(Container("WEB-INF/lib/t.jar", emptyMap())), listOf(Stubs.core())), "t")
        assertEquals("member-extension", classifier.classify(ExtensionRef("t.F#make()", setOf("hudson.Extension"), "x")).reason)
        assertEquals("class-not-found", classifier.classify(ExtensionRef("t.Gone", setOf("hudson.Extension"), "x")).reason)
    }

    @Test
    fun endToEndGateFailsOnUnknownUnlessAllowed() {
        val dir = java.nio.file.Files.createTempDirectory("jps-classify")
        try {
            val jar =
                mapOf(
                    "t/D.class" to Fixtures.classBytes("t/D", superName = STEP_DESC, annotationSpecs = listOf(AnnSpec(Types.EXTENSION_DESC)), methods = listOf(Stubs.ctor(STEP_DESC), Stubs.returnString("getFunctionName", "d"), Stubs.contextEmpty())),
                    "t/Lost.class" to Fixtures.classBytes("t/Lost", superName = "missing/Base", annotationSpecs = listOf(AnnSpec(Types.EXTENSION_DESC))),
                ) + Fixtures.extensionIndex("t.D", "t.Lost")
            val core = Fixtures.write(dir, "core.jar", Fixtures.zip(Stubs.core().entries + ("META-INF/MANIFEST.MF" to Fixtures.manifest("Implementation-Version" to "2.400"))))
            val hpi = Fixtures.write(dir, "p.hpi", Fixtures.hpi("demo", "1", jar))
            val base = listOf("extract", "--hpi", hpi.toString(), "--core", core.toString(), "--out", dir.resolve("o").toString())
            val sink = java.io.PrintStream(java.io.ByteArrayOutputStream())
            assertEquals(ExitCode.GATE_FAILED, runCli(base, sink, sink, StaticExtractor()))
            val text = String(java.nio.file.Files.readAllBytes(dir.resolve("o/demo-1.json")), Charsets.UTF_8)
            assertTrue(text.contains("\"unknown\": 1") && text.contains("\"classified\": 1"))
            assertEquals(ExitCode.OK, runCli(base + "--allow-unknown", sink, sink, StaticExtractor()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
