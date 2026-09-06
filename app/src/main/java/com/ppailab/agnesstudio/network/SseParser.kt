package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.StreamEvent
import com.ppailab.agnesstudio.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SseParser(private val json: Json) {
    fun parseData(data: String): List<StreamEvent> {
        if (data.trim() == "[DONE]") return listOf(StreamEvent.Finished(null))
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val events = mutableListOf<StreamEvent>()

        root["usage"]?.asObject()?.let { usage ->
            events += StreamEvent.Usage(
                promptTokens = usage.int("prompt_tokens") ?: usage.int("input_tokens"),
                completionTokens = usage.int("completion_tokens") ?: usage.int("output_tokens"),
                totalTokens = usage.int("total_tokens"),
            )
        }

        val choices = root["choices"]?.asArray().orEmpty()
        choices.forEach { choiceElement ->
            val choice = choiceElement.asObject() ?: return@forEach
            val delta = choice["delta"]?.asObject() ?: choice["message"]?.asObject()
            delta?.let { parseDelta(it, events) }
            val finish = choice.string("finish_reason")
            if (finish != null) events += StreamEvent.Finished(finish)
        }

        return events
    }

    fun parseNonStream(body: String): List<StreamEvent> {
        val root = json.parseToJsonElement(body).jsonObject
        val events = mutableListOf<StreamEvent>()
        root["choices"]?.asArray()?.firstOrNull()?.asObject()?.let { choice ->
            choice["message"]?.asObject()?.let { parseDelta(it, events) }
            events += StreamEvent.Finished(choice.string("finish_reason"))
        }
        root["usage"]?.asObject()?.let { usage ->
            events += StreamEvent.Usage(
                usage.int("prompt_tokens"), usage.int("completion_tokens"), usage.int("total_tokens"),
            )
        }
        return events
    }

    private fun parseDelta(delta: JsonObject, events: MutableList<StreamEvent>) {
        delta.string("content")?.takeIf { it.isNotEmpty() }?.let { events += StreamEvent.TextDelta(it) }
        val reasoning = listOf("reasoning_content", "reasoning", "thinking")
            .firstNotNullOfOrNull { key -> delta.string(key)?.takeIf(String::isNotEmpty) }
        reasoning?.let { events += StreamEvent.ReasoningDelta(it) }

        delta["tool_calls"]?.asArray()?.forEachIndexed { fallbackIndex, element ->
            val call = element.asObject() ?: return@forEachIndexed
            val function = call["function"]?.asObject()
            events += StreamEvent.ToolDelta(
                ToolCall(
                    index = call.int("index") ?: fallbackIndex,
                    id = call.string("id").orEmpty(),
                    type = call.string("type") ?: "function",
                    name = function?.string("name").orEmpty(),
                    arguments = function?.string("arguments").orEmpty(),
                ),
            )
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
            JsonNull -> null
            else -> null
        }
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
}

class ThinkingStreamSplitter {
    private var insideThinking = false
    private var buffer = ""

    data class Split(val answer: String = "", val reasoning: String = "")

    fun feed(chunk: String): Split {
        buffer += chunk
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        while (buffer.isNotEmpty()) {
            if (insideThinking) {
                val end = buffer.indexOf("</think>")
                if (end >= 0) {
                    reasoning.append(buffer.substring(0, end))
                    buffer = buffer.substring(end + 8)
                    insideThinking = false
                } else {
                    val safe = (buffer.length - 7).coerceAtLeast(0)
                    reasoning.append(buffer.take(safe))
                    buffer = buffer.drop(safe)
                    break
                }
            } else {
                val start = buffer.indexOf("<think>")
                if (start >= 0) {
                    answer.append(buffer.substring(0, start))
                    buffer = buffer.substring(start + 7)
                    insideThinking = true
                } else {
                    val safe = (buffer.length - 6).coerceAtLeast(0)
                    answer.append(buffer.take(safe))
                    buffer = buffer.drop(safe)
                    break
                }
            }
        }
        return Split(answer.toString(), reasoning.toString())
    }

    fun finish(): Split {
        val result = if (insideThinking) Split(reasoning = buffer) else Split(answer = buffer)
        buffer = ""
        return result
    }
}
