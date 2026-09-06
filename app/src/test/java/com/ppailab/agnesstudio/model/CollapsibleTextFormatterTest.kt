package com.ppailab.agnesstudio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsibleTextFormatterTest {
    private val maxCharacters = 480
    private val maxLines = 12

    @Test
    fun `collapsed branch renders a genuinely shorter body`() {
        val full = "长正文".repeat(300)

        val collapsed = CollapsibleTextFormatter.collapsedText(full, maxCharacters, maxLines)

        assertTrue(CollapsibleTextFormatter.isDeterministicallyCollapsible(full, maxCharacters, maxLines))
        assertTrue(collapsed.endsWith("…"))
        assertTrue(collapsed.length < full.length)
    }

    @Test
    fun `short body stays byte for byte unchanged`() {
        val full = "这是一条短消息"

        assertFalse(CollapsibleTextFormatter.isDeterministicallyCollapsible(full, maxCharacters, maxLines))
        assertEquals(full, CollapsibleTextFormatter.collapsedText(full, maxCharacters, maxLines))
    }

    @Test
    fun `many short lines are still collapsible`() {
        val full = List(maxLines + 1) { "行 $it" }.joinToString("\n")

        assertTrue(CollapsibleTextFormatter.isDeterministicallyCollapsible(full, maxCharacters, maxLines))
        assertTrue(CollapsibleTextFormatter.collapsedText(full, maxCharacters, maxLines).length < full.length)
    }

    @Test
    fun `chat compact preview removes the full text tail`() {
        val tailMarker = "正文末尾唯一标记"
        val full = "这是一段很长的对话正文。".repeat(80) + tailMarker

        val collapsed = CollapsibleTextFormatter.collapsedText(full, 260, 6)

        assertFalse(collapsed.contains(tailMarker))
        assertTrue(collapsed.endsWith("…"))
    }
}
