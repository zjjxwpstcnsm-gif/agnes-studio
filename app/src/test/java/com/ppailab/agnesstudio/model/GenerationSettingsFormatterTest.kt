package com.ppailab.agnesstudio.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSettingsFormatterTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `image record preserves the exact settings used at enqueue time`() {
        val spec = ImageTaskSpec(
            prompt = "portrait",
            parameters = ImageParameters(size = "4K", ratio = "3:4", extraJson = "{\"seed\":7}"),
        )
        val snapshot = GenerationSettingsFormatter.snapshot(job(Modality.IMAGE, json.encodeToString(spec)))

        assertEquals("4K", snapshot.fields.toMap().getValue("分辨率"))
        assertEquals("3:4", snapshot.fields.toMap().getValue("画幅比例"))
        assertEquals("{\"seed\":7}", snapshot.fields.toMap().getValue("高级参数 JSON"))
        assertEquals("portrait", snapshot.prompt)
        assertTrue(snapshot.rawJson.contains("\"extraJson\""))
        assertTrue(snapshot.rawJson.contains("seed"))
    }

    @Test
    fun `video record includes mode duration seed and attachment source`() {
        val media = MediaAttachment(
            id = "frame-1",
            displayName = "first.png",
            mimeType = "image/png",
            remoteUrl = "https://example.com/first.png",
            role = AttachmentRole.FIRST_FRAME,
        )
        val spec = VideoTaskSpec(
            prompt = "camera push",
            parameters = VideoParameters(mode = VideoMode.KEYFRAME, seconds = 8, seed = 42),
            attachments = listOf(media),
        )
        val snapshot = GenerationSettingsFormatter.snapshot(job(Modality.VIDEO, json.encodeToString(spec)))

        assertEquals("首尾帧（keyframe）", snapshot.fields.toMap().getValue("生成模式"))
        assertEquals("8 秒", snapshot.fields.toMap().getValue("时长"))
        assertEquals("42", snapshot.fields.toMap().getValue("Seed"))
        assertTrue(snapshot.attachments.single().contains("https://example.com/first.png"))
    }

    private fun job(modality: Modality, specJson: String) = GenerationJob(
        id = "job-1",
        modality = modality,
        status = JobStatus.SUCCEEDED,
        prompt = "prompt",
        specJson = specJson,
        createdAt = 1L,
        updatedAt = 1L,
        nextAttemptAt = 1L,
        attempt = 1,
        progress = 100,
        remoteId = null,
        resultUrl = null,
        resultPath = null,
        errorKind = null,
        errorMessage = null,
        httpStatus = null,
    )
}
