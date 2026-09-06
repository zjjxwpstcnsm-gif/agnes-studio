package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.StreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private val parser = SseParser(Json { ignoreUnknownKeys = true })

    @Test
    fun `parses content reasoning tool chunks and finish reason`() {
        val body = """
            {
              "choices": [{
                "delta": {
                  "content": "答案",
                  "reasoning_content": "思考",
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "weather", "arguments": "{\"city\":"}
                  }]
                },
                "finish_reason": "tool_calls"
              }]
            }
        """.trimIndent()

        val events = parser.parseData(body)
        assertEquals("答案", (events[0] as StreamEvent.TextDelta).text)
        assertEquals("思考", (events[1] as StreamEvent.ReasoningDelta).text)
        assertEquals("weather", (events[2] as StreamEvent.ToolDelta).call.name)
        assertEquals("tool_calls", (events[3] as StreamEvent.Finished).reason)
    }

    @Test
    fun `recognizes done sentinel`() {
        assertTrue(parser.parseData("[DONE]").single() is StreamEvent.Finished)
    }

    @Test
    fun `splits think tags even when tags cross chunk boundaries`() {
        val splitter = ThinkingStreamSplitter()
        val parts = listOf("<thi", "nk>分析", "过程</th", "ink>结论")
            .map(splitter::feed) + splitter.finish()

        assertEquals("结论", parts.joinToString("") { it.answer })
        assertEquals("分析过程", parts.joinToString("") { it.reasoning })
    }
}
