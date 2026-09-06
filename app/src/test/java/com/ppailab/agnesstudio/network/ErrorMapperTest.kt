package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.ErrorKind
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMapperTest {
    @Test
    fun `maps 5xx queue-full payload to retryable remote queue state`() {
        val failure = ErrorMapper.fromHttp(500, """{"error":{"message":"image queue is full"}}""")

        assertEquals(ErrorKind.REMOTE_QUEUE_FULL, failure.kind)
        assertTrue(failure.retryable)
        assertEquals(60_000L, failure.retryAfterMillis)
    }

    @Test
    fun `honors numeric retry-after for rate limits`() {
        val failure = ErrorMapper.fromHttp(429, "too many requests", "17")

        assertEquals(ErrorKind.RATE_LIMIT, failure.kind)
        assertEquals(17_000L, failure.retryAfterMillis)
    }

    @Test
    fun `does not retry local validation errors`() {
        val failure = ErrorMapper.fromThrowable(IllegalArgumentException("参考素材 URL 无效"))

        assertEquals(ErrorKind.VALIDATION, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `retries transport IO failures`() {
        val failure = ErrorMapper.fromThrowable(IOException("connection reset"))

        assertEquals(ErrorKind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
    }
}
