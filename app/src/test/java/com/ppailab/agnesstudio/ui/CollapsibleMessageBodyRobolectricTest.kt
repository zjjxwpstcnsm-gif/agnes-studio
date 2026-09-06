package com.ppailab.agnesstudio.ui

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CollapsibleMessageBodyRobolectricTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun collapseInsideLazyColumnShrinksBodyAndSurvivesStreamingUpdate() {
        val tailMarker = "正文末尾唯一标记"
        val content = List(80) { index -> "这是第 ${index + 1} 行很长的对话正文。" }
            .joinToString("\n") + "\n" + tailMarker
        val streamedContent = mutableStateOf(content)
        var collapsedCallbacks = 0

        composeRule.setContent {
            MaterialTheme {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(640.dp),
                ) {
                    item(key = "message-robolectric") {
                        CollapsibleMessageBody(
                            messageId = "message-robolectric",
                            content = streamedContent.value,
                            onCollapsed = {
                                collapsedCallbacks += 1
                                listState.requestScrollToItem(0)
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("展开全文").assertExists().performClick()
        composeRule.onNodeWithText("收起正文").assertExists()
        composeRule.onNodeWithText(tailMarker, substring = true).assertExists()
        val expandedBounds = composeRule
            .onNodeWithTag("chat-message-body:message-robolectric")
            .getUnclippedBoundsInRoot()
        val expandedHeight = expandedBounds.bottom.value - expandedBounds.top.value

        composeRule.onNodeWithText("收起正文").performScrollTo().performClick()
        composeRule.onNodeWithText("展开全文").assertExists()
        composeRule.onNodeWithText("已收起 · 仅显示前 6 行").assertExists()
        composeRule.onNodeWithText(tailMarker, substring = true).assertDoesNotExist()
        val collapsedBounds = composeRule
            .onNodeWithTag("chat-message-body:message-robolectric")
            .getUnclippedBoundsInRoot()
        val collapsedHeight = collapsedBounds.bottom.value - collapsedBounds.top.value

        composeRule.runOnIdle {
            streamedContent.value += "流式追加后也不能偷偷展开"
        }
        composeRule.onNodeWithText("收起正文").assertDoesNotExist()
        composeRule.onNodeWithText("展开全文").assertExists()
        composeRule.onNodeWithText("流式追加后也不能偷偷展开", substring = true).assertDoesNotExist()

        assertTrue(
            "collapsed body must be shorter: expanded=$expandedHeight collapsed=$collapsedHeight",
            collapsedHeight < expandedHeight,
        )
        assertEquals(1, collapsedCallbacks)
    }
}
