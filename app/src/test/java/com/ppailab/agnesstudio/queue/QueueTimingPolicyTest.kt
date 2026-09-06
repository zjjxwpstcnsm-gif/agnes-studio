package com.ppailab.agnesstudio.queue

import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueTimingPolicyTest {
    @Test
    fun `video result polling and its retries have a thirty second floor`() {
        val pollingJob = job(Modality.VIDEO, remoteId = "video-1")

        assertEquals(30_000L, QueueTimingPolicy.VIDEO_POLL_INTERVAL_MILLIS)
        assertEquals(30_000L, QueueTimingPolicy.minimumRetryDelayMillis(pollingJob))
    }

    @Test
    fun `video creation and image retries keep their normal backoff`() {
        assertEquals(0L, QueueTimingPolicy.minimumRetryDelayMillis(job(Modality.VIDEO)))
        assertEquals(0L, QueueTimingPolicy.minimumRetryDelayMillis(job(Modality.IMAGE)))
    }

    private fun job(modality: Modality, remoteId: String? = null) = GenerationJob(
        id = "job-1",
        modality = modality,
        status = JobStatus.PROCESSING,
        prompt = "prompt",
        specJson = "{}",
        createdAt = 1L,
        updatedAt = 1L,
        nextAttemptAt = 1L,
        attempt = 1,
        progress = 50,
        remoteId = remoteId,
        resultUrl = null,
        resultPath = null,
        errorKind = null,
        errorMessage = null,
        httpStatus = null,
    )
}
