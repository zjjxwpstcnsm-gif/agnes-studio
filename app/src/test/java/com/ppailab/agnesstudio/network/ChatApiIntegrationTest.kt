package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ChatApiIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val builder = PayloadBuilder(json)
    private fun message(role: String = "user", calls: List<ToolCall> = emptyList(), callId: String? = null) =
        ChatMessage("m", "c", role, "hello", "", calls, callId, emptyList(), MessageState.COMPLETE, null, 0L)

    @Test
    fun `thinking and tool controls override conflicting advanced json`() {
        val extra = """{"model":"old","chat_template_kwargs":{"enable_thinking":true,"custom":7},"tools":[{}],"tool_choice":"required"}"""
        val payload = builder.chat(ChatParameters(enableThinking = false, extraJson = extra), listOf(message()))
        assertEquals(ChatModel.DEFAULT, payload["model"]!!.jsonPrimitive.content)
        assertFalse(payload["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(7, payload["chat_template_kwargs"]!!.jsonObject["custom"]!!.jsonPrimitive.int)
        assertFalse(payload.containsKey("tools"))
        assertFalse(payload.containsKey("tool_choice"))
    }

    @Test
    fun `tool continuation and image URLs follow the documented chat contract`() {
        val call = ToolCall(0, "call_1", name = "weather", arguments = "{}")
        val user = message().copy(attachments = listOf(MediaAttachment("img", "img.png", "image/png",
            remoteUrl = "https://example.com/image.png", role = AttachmentRole.CHAT_IMAGE)))
        val payload = builder.chat(ChatParameters(maxTokens = 65536, toolsJson = """[{"type":"function","function":{"name":"weather","parameters":{"type":"object"}}}]"""),
            listOf(user, message("assistant", listOf(call)), message("tool", callId = call.id)))
        val history = payload["messages"]!!.jsonArray
        assertEquals("https://example.com/image.png", history[1].jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("call_1", history[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("call_1", history[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(payload["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        assertThrows(IllegalArgumentException::class.java) {
            builder.chat(ChatParameters(), listOf(user.copy(attachments = user.attachments.map { it.copy(remoteUrl = "data:image/png;base64,AA==") })))
        }
        assertThrows(IllegalArgumentException::class.java) { builder.chat(ChatParameters(maxTokens = 65537), emptyList()) }
    }

    @Test
    fun `connection check uses new model without thinking and rejects empty success`() = runBlocking<Unit> {
        var body = """{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1/chat/completions", chain.request().url.encodedPath)
            val sent = json.parseToJsonElement(Buffer().apply { chain.request().body!!.writeTo(this) }.readUtf8()).jsonObject
            assertEquals(ChatModel.DEFAULT, sent["model"]!!.jsonPrimitive.content)
            assertFalse(sent["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
            assertFalse(sent["stream"]!!.jsonPrimitive.boolean)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = AgnesApiClient(client, json)
        api.testKey("https://agnes.example/v1", "test-only", 30)
        body = "{}"
        try { api.testKey("https://agnes.example", "test-only", 30); fail("empty response accepted") }
        catch (_: ApiException) { }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `http streaming and complete responses preserve thinking text and tools`() = runBlocking<Unit> {
        val complete = """{"choices":[{"message":{"reasoning_content":"plan","content":"answer","tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        val streamed = "data: " + complete.replace("\"message\"", "\"delta\"") + "\n\ndata: [DONE]\n\n"
        for (stream in listOf(true, false)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (stream) streamed else complete).toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
            }.build()
            val events = mutableListOf<StreamEvent>()
            AgnesApiClient(client, json).chat("https://agnes.example", "test-only", builder.chat(ChatParameters(stream = stream), listOf(message())), 30) { events += it }
            assertTrue(events.contains(StreamEvent.TextDelta("answer")))
            assertTrue(events.contains(StreamEvent.ReasoningDelta("plan")))
            assertEquals("weather", events.filterIsInstance<StreamEvent.ToolDelta>().single().call.name)
            assertTrue(events.contains(StreamEvent.Finished("tool_calls")))
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
