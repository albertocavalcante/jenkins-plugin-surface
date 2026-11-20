// SPDX-License-Identifier: Apache-2.0
package jps

import jps.Fixtures.MethodSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelpFinderTest {
    private fun finder(resources: Map<String, String>, includeBodies: Boolean = true, vararg classes: Pair<String, ByteArray>): HelpFinder {
        val entries = classes.associate { "${it.first}.class" to it.second } + resources.mapValues { it.value.toByteArray() }
        return HelpFinder(ClassStore(listOf(Container("WEB-INF/lib/t.jar", entries)), listOf(Stubs.core())), includeBodies)
    }

    private val step = "t/Step" to Fixtures.classBytes("t/Step")

    @Test
    fun classAndParameterHelpAreFound() {
        val f = finder(mapOf("t/Step/help.html" to "<p>Does a thing. More.</p>", "t/Step/help-count.html" to "<div>Count</div>"), true, step)
        val cls = f.docsFor("t/Step", null)
        assertEquals(DocsStatus.PRESENT, cls.status)
        assertEquals("Does a thing.", cls.summary)
        assertEquals("<p>Does a thing. More.</p>", cls.bodyHtml)
        assertEquals("Does a thing. More.", cls.bodyMarkdown)
        assertEquals("WEB-INF/lib/t.jar!/t/Step/help.html", cls.source?.path)
        assertEquals("en", cls.locale)
        assertEquals("Count", f.docsFor("t/Step", "count").summary)
    }

    @Test
    fun noHelpTextKeepsSummaryAndDropsBodies() {
        val f = finder(mapOf("t/Step/help.html" to "<p>Hello there.</p>"), false, step)
        val d = f.docsFor("t/Step", null)
        assertEquals("Hello there.", d.summary)
        assertNull(d.bodyHtml)
        assertNull(d.bodyMarkdown)
    }

    @Test
    fun jellyHelpIsFlaggedDynamicAndMissingHelpIsAbsent() {
        val f = finder(mapOf("t/Step/help.jelly" to "<j:jelly/>"), true, step)
        val d = f.docsFor("t/Step", null)
        assertEquals(DocsStatus.DYNAMIC, d.status)
        assertNull(d.bodyHtml)
        assertEquals(DocsStatus.ABSENT, f.docsFor("t/Step", "nothing").status)
    }

    @Test
    fun helpIsInheritedFromTheSuperclassChain() {
        val child = "t/Child" to Fixtures.classBytes("t/Child", superName = "t/Step")
        val f = finder(mapOf("t/Step/help-x.html" to "<p>Parent x.</p>"), true, step, child)
        assertEquals("Parent x.", f.docsFor("t/Child", "x").summary)
        assertEquals(DocsStatus.ABSENT, f.docsFor("t/Child", "y").status)
    }

    @Test
    fun recordsCarryClassAndParameterDocs() {
        val desc = "t/S\$DescriptorImpl"
        val classes =
            mapOf(
                "$desc.class" to Fixtures.classBytes(desc, superName = Stubs.STEP_DESC, innerOf = "t/S", methods = listOf(Stubs.ctor(Stubs.STEP_DESC), Stubs.returnString("getFunctionName", "s"), Stubs.contextEmpty())),
                "t/S.class" to Fixtures.classBytes("t/S", methods = listOf(MethodSpec("<init>", "(Ljava/lang/String;)V", annotations = listOf("Lorg/kohsuke/stapler/DataBoundConstructor;"), paramNames = listOf("text")))),
                "t/S/help.html" to "<p>Runs it. Slowly.</p>".toByteArray(),
                "t/S/help-text.html" to "<p>The text.</p>".toByteArray(),
            )
        val container = Container("WEB-INF/lib/t.jar", classes)
        val store = ClassStore(listOf(container), listOf(Stubs.core()))
        val withBodies = Classifier(store, "t").classify(ExtensionRef(desc.replace('/', '.'), setOf("hudson.Extension"), container.path))
        assertEquals("Runs it.", withBodies.docs?.summary)
        assertEquals("<p>Runs it. Slowly.</p>", withBodies.docs?.bodyHtml)
        assertEquals("The text.", withBodies.parameters.single().docs?.summary)
        val without = Classifier(store, "t", noHelpText = true).classify(ExtensionRef(desc.replace('/', '.'), setOf("hudson.Extension"), container.path))
        assertNull(without.docs?.bodyHtml)
        assertEquals("Runs it.", without.docs?.summary)
        assertTrue(without.links.isNotEmpty())
    }

    @Test
    fun cyclicSuperclassChainsTerminate() {
        val a = "t/A" to Fixtures.classBytes("t/A", superName = "t/B")
        val b = "t/B" to Fixtures.classBytes("t/B", superName = "t/A")
        assertEquals(DocsStatus.ABSENT, finder(emptyMap(), true, a, b).docsFor("t/A", null).status)
    }

    @Test
    fun nearestClassWinsAndGroovyHelpIsDynamic() {
        val child = "t/Child" to Fixtures.classBytes("t/Child", superName = "t/Step")
        val f = finder(mapOf("t/Step/help.html" to "<p>Parent.</p>", "t/Child/help.groovy" to "x"), true, step, child)
        assertEquals(DocsStatus.DYNAMIC, f.docsFor("t/Child", null).status)
        assertEquals("help-groovy", f.docsFor("t/Child", null).source?.kind)
        assertEquals("Parent.", f.docsFor("t/Step", null).summary)
    }

    @Test
    fun oversizedHelpIsNotParsed() {
        val f = finder(mapOf("t/Step/help.html" to "<p>" + "x".repeat(2 * 1024 * 1024) + "</p>"), true, step)
        val d = f.docsFor("t/Step", null)
        assertEquals(DocsStatus.ABSENT, d.status)
        assertEquals("help-html-too-large", d.source?.kind)
    }

    @Test
    fun nestedClassUsesSlashDirectories() {
        val nested = "t/Outer\$Inner" to Fixtures.classBytes("t/Outer\$Inner")
        val f = finder(mapOf("t/Outer/Inner/help.html" to "<p>Nested.</p>"), true, nested)
        assertEquals("Nested.", f.docsFor("t/Outer\$Inner", null).summary)
    }
}
