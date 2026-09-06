package com.ppailab.agnesstudio.queue

import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.Modality

internal object QueueTimingPolicy {
    const val VIDEO_POLL_INTERVAL_MILLIS = 30_000L

    fun minimumRetryDelayMillis(job: GenerationJob): Long =
        if (job.modality == Modality.VIDEO && job.remoteId != null) {
            VIDEO_POLL_INTERVAL_MILLIS
        } else {
            0L
        }
}
