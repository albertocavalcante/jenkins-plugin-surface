// SPDX-License-Identifier: Apache-2.0
package jps

import jps.Fixtures.AnnSpec
import jps.Fixtures.FieldSpec
import jps.Fixtures.MethodSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class ParamsTest {
    private val dbc = Fixtures.AnnSpec("Lorg/kohsuke/stapler/DataBoundConstructor;")
    private val dbs = "Lorg/kohsuke/stapler/DataBoundSetter;"

    private fun ctor(desc: String, signature: String? = null, paramNames: List<String> = emptyList(), body: (org.objectweb.asm.MethodVisitor) -> Unit = { it.visitInsn(Opcodes.RETURN) }) =
        MethodSpec("<init>", desc, body = body, signature = signature, paramNames = paramNames)

    private fun extract(classes: Map<String, ByteArray>, resources: Map<String, ByteArray> = emptyMap(), target: String = "t/Step"): ParamResult {
        val container = Container("WEB-INF/lib/t.jar", classes.mapKeys { "${it.key}.class" } + resources)
        return ParamExtractor(ClassStore(listOf(container), listOf(Stubs.core()))).parametersOf(ClassName(target))
    }

    private fun dataBoundCtor(desc: String, signature: String? = null, paramNames: List<String> = emptyList()): MethodSpec {
        // The annotation is attached through the classBytes method annotation list below.
        return MethodSpec("<init>", desc, annotations = listOf("Lorg/kohsuke/stapler/DataBoundConstructor;"), signature = signature, paramNames = paramNames)
    }

    private fun stapler(vararg names: String): Map<String, ByteArray> = mapOf("t/Step.stapler" to "constructor=${names.joinToString(",")}\n".toByteArray())

    private val mode = Fixtures.classBytes(
        "t/Mode",
        superName = "java/lang/Enum",
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ENUM or Opcodes.ACC_FINAL,
        fields = listOf("FAST", "SLOW").map { FieldSpec(it, "Lt/Mode;", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM) },
    )

    @Test
    fun constructorParametersComeFirstThenSettersAlphabetically() {
        val step =
            Fixtures.classBytes(
                "t/Step",
                methods =
                    listOf(
                        dataBoundCtor("(Ljava/lang/String;I)V"),
                        MethodSpec("setZeta", "(Z)V", annotations = listOf(dbs)),
                        MethodSpec("setAlphaBeta", "(Ljava/lang/String;)V", annotations = listOf(dbs, "Ljava/lang/Deprecated;")),
                    ),
                fields = listOf(FieldSpec("mode", "Lt/Mode;", annotations = listOf(dbs)), FieldSpec("ignored", "I")),
            )
        val result = extract(mapOf("t/Step" to step, "t/Mode" to mode), stapler("name", "count"))
        assertEquals(listOf("name", "count", "alphaBeta", "mode", "zeta"), result.parameters.map { it.name })
        assertEquals(listOf(true, true, false, false, false), result.parameters.map { it.modelRequired })
        assertEquals("stapler", result.parameters[0].namesSource)
        assertEquals(listOf(false, false, true, false, false), result.parameters.map { it.deprecated })
        assertEquals(ParamOrigin.SETTER_METHOD, result.parameters[2].origin)
        assertEquals(ParamOrigin.SETTER_FIELD, result.parameters[3].origin)
        val modeType = result.parameters[3].type
        assertEquals(TypeCategory.ENUM, modeType.category)
        assertEquals(listOf("FAST", "SLOW"), modeType.enumValues)
        assertEquals(emptyList<Gap>(), result.gaps)
    }

    @Test
    fun methodParametersAreUsedWhenNoStaplerFileExists() {
        val step = Fixtures.classBytes("t/Step", methods = listOf(dataBoundCtor("(Ljava/lang/String;)V", paramNames = listOf("url"))))
        val result = extract(mapOf("t/Step" to step))
        assertEquals(listOf("url"), result.parameters.map { it.name })
        assertEquals("method-parameters", result.parameters[0].namesSource)
    }

    @Test
    fun localVariableTableIsTheLastResort() {
        val body: (org.objectweb.asm.MethodVisitor) -> Unit = {
            val start = Label()
            val end = Label()
            it.visitLabel(start)
            it.visitInsn(Opcodes.RETURN)
            it.visitLabel(end)
            it.visitLocalVariable("this", "Lt/Step;", null, start, end, 0)
            it.visitLocalVariable("first", "Ljava/lang/String;", null, start, end, 1)
            it.visitLocalVariable("second", "J", null, start, end, 2)
        }
        val step = Fixtures.classBytes("t/Step", methods = listOf(MethodSpec("<init>", "(Ljava/lang/String;J)V", annotations = listOf("Lorg/kohsuke/stapler/DataBoundConstructor;"), body = body)))
        val result = extract(mapOf("t/Step" to step))
        assertEquals(listOf("first", "second"), result.parameters.map { it.name })
        assertEquals("local-variable-table", result.parameters[0].namesSource)
    }

    @Test
    fun noNameSourceIsAGapNotAGuess() {
        val step = Fixtures.classBytes("t/Step", methods = listOf(dataBoundCtor("(Ljava/lang/String;)V")))
        val result = extract(mapOf("t/Step" to step))
        assertTrue(result.parameters.isEmpty())
        assertTrue(result.gaps.any { it.reason == "parameter-names-unavailable" })
    }

    @Test
    fun disagreeingNameSourcesAreReported() {
        val step = Fixtures.classBytes("t/Step", methods = listOf(dataBoundCtor("(Ljava/lang/String;)V", paramNames = listOf("other"))))
        val result = extract(mapOf("t/Step" to step), stapler("name"))
        assertEquals(listOf("name"), result.parameters.map { it.name })
        assertTrue(result.gaps.any { it.reason == "name-sources-disagree" && it.detail == "method-parameters" })
    }

    @Test
    fun genericAndNestedTypes() {
        val step =
            Fixtures.classBytes(
                "t/Step",
                methods = listOf(dataBoundCtor("(Ljava/util/List;[ILjava/util/Map;Lt/Other;)V", signature = "(Ljava/util/List<Lt/Other;>;[ILjava/util/Map<Ljava/lang/String;Ljava/lang/String;>;Lt/Other;)V")),
            )
        val result = extract(mapOf("t/Step" to step, "t/Other" to Fixtures.classBytes("t/Other")), stapler("items", "ints", "map", "other"))
        val byName = result.parameters.associateBy { it.name }
        assertEquals(TypeCategory.ARRAY, byName.getValue("items").type.category)
        assertEquals("t/Other", byName.getValue("items").type.element?.nested?.internal)
        assertEquals("java.util.List<t.Other>", byName.getValue("items").type.java)
        assertEquals(TypeCategory.ARRAY, byName.getValue("ints").type.category)
        assertEquals(TypeCategory.ATOMIC, byName.getValue("ints").type.element?.category)
        assertEquals(TypeCategory.UNSUPPORTED, byName.getValue("map").type.category)
        assertEquals(TypeCategory.OBJECT, byName.getValue("other").type.category)
    }

    @Test
    fun superclassSettersAreIncludedAndUnresolvableOnesAreAGap() {
        val parent = Fixtures.classBytes("t/Parent", methods = listOf(MethodSpec("setInherited", "(Ljava/lang/String;)V", annotations = listOf(dbs))))
        val step = Fixtures.classBytes("t/Step", superName = "t/Parent", methods = listOf(dataBoundCtor("()V")))
        val ok = extract(mapOf("t/Step" to step, "t/Parent" to parent), stapler())
        assertEquals(listOf("inherited"), ok.parameters.map { it.name })
        val dangling = extract(mapOf("t/Step" to Fixtures.classBytes("t/Step", superName = "gone/Base", methods = listOf(dataBoundCtor("()V")))), stapler())
        assertTrue(dangling.gaps.any { it.reason == "unresolvable-type" && it.detail == "gone/Base" })
    }

    @Test
    fun constructorParameterWinsOverSetterOfTheSameName() {
        val step =
            Fixtures.classBytes(
                "t/Step",
                methods = listOf(dataBoundCtor("(Ljava/lang/String;)V"), MethodSpec("setName", "(Ljava/lang/String;)V", annotations = listOf(dbs))),
            )
        val result = extract(mapOf("t/Step" to step), stapler("name"))
        assertEquals(listOf(true), result.parameters.map { it.modelRequired })
    }

    @Test
    fun nullableFieldMarksTheUsageHintButNotModelRequiredness() {
        val step =
            Fixtures.classBytes(
                "t/Step",
                fields = listOf(FieldSpec("label", "Ljava/lang/String;", annotations = listOf("Ledu/umd/cs/findbugs/annotations/CheckForNull;"))),
                methods = listOf(dataBoundCtor("(Ljava/lang/String;)V")),
            )
        val p = extract(mapOf("t/Step" to step), stapler("label")).parameters.single()
        assertEquals(true, p.modelRequired)
        assertEquals(UsageHint.NULLABLE, p.usageHint)
    }

    @Test
    fun metastepRecordsItsArgumentType() {
        val desc = "t/Meta\$DescriptorImpl"
        val descriptor =
            Fixtures.classBytes(
                desc,
                superName = Stubs.STEP_DESC,
                innerOf = "t/Meta",
                methods = listOf(Stubs.ctor(Stubs.STEP_DESC), Stubs.returnString("getFunctionName", "meta"), Stubs.returnBool("isMetaStep", true), Stubs.contextEmpty()),
            )
        val meta = Fixtures.classBytes("t/Meta", methods = listOf(dataBoundCtor("(Ljenkins/tasks/SimpleBuildStep;)V")))
        val container = Container("WEB-INF/lib/t.jar", mapOf("$desc.class" to descriptor, "t/Meta.class" to meta, "t/Meta.stapler" to "constructor=delegate\n".toByteArray()))
        val record = Classifier(ClassStore(listOf(container), listOf(Stubs.core())), "t").classify(ExtensionRef(desc.replace('/', '.'), setOf("hudson.Extension"), container.path))
        assertEquals(true, record.step?.metastep)
        assertEquals("jenkins/tasks/SimpleBuildStep", record.step?.metastepArgumentType?.internal)
        assertEquals(listOf("delegate"), record.parameters.map { it.name })
    }

    @Test
    fun sampleAnnotationHelperCompiles() {
        assertEquals("Lorg/kohsuke/stapler/DataBoundConstructor;", dbc.descriptor)
        assertEquals(AnnSpec::class, dbc::class)
    }
}
