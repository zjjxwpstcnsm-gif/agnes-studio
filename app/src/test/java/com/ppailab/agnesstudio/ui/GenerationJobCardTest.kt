package com.ppailab.agnesstudio.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ppailab.agnesstudio.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GenerationJobCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `copy action submits immediately with feedback and prevents double taps`() {
        var submissions = 0
        val job = GenerationJob("history", Modality.VIDEO, JobStatus.FAILED, "original prompt", "{}",
            0, 0, 0, 2, 0, "old-video", null, null, null, null, null)
        compose.setContent {
            MaterialTheme {
                GenerationJobCard(job, onRetry = {}, onCancel = {}, onDuplicate = {
                    submissions++
                    true
                })
            }
        }
        compose.onNodeWithText("复制并生成").assertIsEnabled().performClick()
        compose.onNodeWithText("已加入队列").assertIsNotEnabled().performClick()
        assertEquals(1, submissions)
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("复制并生成").assertIsEnabled().performClick()
        assertEquals(2, submissions)
        compose.onNodeWithText("复制提示词").assertExists()
        compose.onNodeWithText("重新排队").assertExists()
    }
}
