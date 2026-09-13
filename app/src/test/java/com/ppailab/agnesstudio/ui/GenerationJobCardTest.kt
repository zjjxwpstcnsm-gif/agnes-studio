package com.ppailab.agnesstudio.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
                GenerationJobCard(job, onRetry = {}, onCancel = {}, onDelete = {}, onDuplicate = {
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

    @Test
    fun `deletion requires confirmation and is hidden for active tasks`() {
        var deletions = 0
        val job = mutableStateOf(GenerationJob("history", Modality.VIDEO, JobStatus.FAILED, "prompt", "{}",
            0, 0, 0, 1, 0, null, null, null, null, null, null))
        compose.setContent {
            MaterialTheme {
                GenerationJobCard(job.value, onRetry = {}, onCancel = {}, onDuplicate = { true },
                    onDelete = { deletions++ })
            }
        }
        compose.onNodeWithText("删除记录").performClick()
        compose.onNodeWithText("删除 1 条历史记录？").assertExists()
        assertEquals(0, deletions)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, deletions)
        compose.onNodeWithText("删除记录").performClick()
        compose.onNodeWithText("确认删除").performClick()
        assertEquals(1, deletions)
        compose.runOnIdle { job.value = job.value.copy(status = JobStatus.QUEUED) }
        compose.onNodeWithText("删除记录").assertDoesNotExist()
        compose.onNodeWithText("取消任务").assertExists()
    }
}
