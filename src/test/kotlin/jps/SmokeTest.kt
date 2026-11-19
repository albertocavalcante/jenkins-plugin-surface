package jps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun versionIsSemver() {
        assertEquals(3, TOOL_VERSION.split('.').size)
    }
}
