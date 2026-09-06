package com.ppailab.agnesstudio.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoResponseParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = AgnesApiClient(OkHttpClient(), json)

    @Test
    fun `parses video id from wrapped create response`() {
        val root = json.parseToJsonElement(
            """{"data":{"video_id":"video-123","status":"queued","progress":"2"}}""",
        ).jsonObject

        val result = client.parseVideoCreate(root)
        assertEquals("video-123", result.videoId)
        assertEquals("queued", result.status)
        assertEquals(2, result.progress)
    }

    @Test
    fun `parses completed url from nested output response`() {
        val root = json.parseToJsonElement(
            """{
                "result": {
                    "status":"completed",
                    "progress":100,
                    "output":{"video_url":"https://cdn.example.com/generated/video.mp4"}
                }
            }""".trimIndent(),
        ).jsonObject

        val result = client.parseVideoPoll(root)
        assertEquals("completed", result.status)
        assertEquals(100, result.progress)
        assertEquals("https://cdn.example.com/generated/video.mp4", result.url)
    }

    @Test
    fun `parses official metadata url response`() {
        val root = json.parseToJsonElement(
            """{
                "status":"completed",
                "progress":100,
                "metadata":{"url":"https://cdn.example.com/video.mp4"}
            }""".trimIndent(),
        ).jsonObject

        assertEquals("https://cdn.example.com/video.mp4", client.parseVideoPoll(root).url)
    }
}
