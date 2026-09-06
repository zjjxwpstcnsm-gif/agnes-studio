package com.ppailab.agnesstudio.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun `removes credentials binary bodies and signed query strings`() {
        val value = LogSanitizer.json(
            """{
                "api_key":"sk-secret-value",
                "first_frame":"data:image/png;base64,QUJDREVGRw==",
                "url":"https://cdn.example.com/video.mp4?signature=private"
            }""".trimIndent(),
        )

        assertFalse(value.contains("sk-secret-value"))
        assertFalse(value.contains("QUJDREVGRw"))
        assertFalse(value.contains("signature=private"))
        assertTrue(value.contains("<redacted>"))
        assertTrue(value.contains("data URI omitted"))
    }

    @Test
    fun `redacts bearer token from plain error text`() {
        val value = LogSanitizer.throwable("Authorization: Bearer abc.def.secret").orEmpty()
        assertFalse(value.contains("abc.def.secret"))
        assertTrue(value.contains("<redacted>"))
    }
}
