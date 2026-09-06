package com.ppailab.agnesstudio.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatParametersSnapshotTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `all conversation parameters survive a persisted snapshot round trip`() {
        val expected = ChatParameters(
            model = "agnes-custom",
            systemPrompt = "Stay concise and use tools.",
            temperature = 1.25,
            topP = 0.72,
            maxTokens = 12_345,
            stream = true,
            enableThinking = false,
            toolsJson = "[{\"type\":\"function\"}]",
            toolChoice = "required",
            extraJson = "{\"frequency_penalty\":0.2}",
        )

        val restored = json.decodeFromString<ChatParameters>(json.encodeToString(expected))

        assertEquals(expected, restored)
    }
}
