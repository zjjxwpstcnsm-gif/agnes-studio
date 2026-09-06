package com.ppailab.agnesstudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class CollapsibleMessageBodyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandAndCollapseSwitchesTheRenderedTextBranch() {
        val tailMarker = "正文末尾唯一标记"
        val content = "这是一段很长的对话正文。".repeat(80) + tailMarker

        composeRule.setContent {
            MaterialTheme {
                CollapsibleMessageBody(
                    messageId = "message-1",
                    content = content,
                    onCollapsed = {},
                )
            }
        }

        composeRule.onNodeWithText("展开全文").assertExists().performClick()
        composeRule.onNodeWithText("收起正文").assertExists()
        composeRule.onNodeWithText(tailMarker, substring = true).assertExists()

        composeRule.onNodeWithText("收起正文").performClick()
        composeRule.onNodeWithText("展开全文").assertExists()
        composeRule.onNodeWithText(tailMarker, substring = true).assertDoesNotExist()
    }
}
