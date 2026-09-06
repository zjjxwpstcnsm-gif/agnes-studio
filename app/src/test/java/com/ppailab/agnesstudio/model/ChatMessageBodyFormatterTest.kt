package com.ppailab.agnesstudio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsibleTextUnicodeTest {
    @Test
    fun `preview does not split a surrogate pair`() {
        val full = "A😀B"

        val collapsed = CollapsibleTextFormatter.collapsedText(full, 2, 12)

        assertEquals("A…", collapsed)
        assertTrue(collapsed.endsWith("…"))
    }
}
