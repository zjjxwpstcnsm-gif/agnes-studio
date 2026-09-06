package com.ppailab.agnesstudio.network

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Consume and checkpoint a response before delivering it to the caller. A cancelled
 * continuation may discard a return value, so durable results belong in [consume].
 * Cancellation stops the socket; cleanup waits for its callback to finish before
 * the queue releases ownership of the job to another worker.
 */
internal suspend fun <T> Call.executeCancellable(consume: (Response) -> T): T {
    val finished = CompletableDeferred<Unit>()
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            try {
                enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        try {
                            continuation.resumeWithException(error)
                        } finally {
                            finished.complete(Unit)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = runCatching { response.use(consume) }
                            continuation.resumeWith(result)
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                })
            } catch (error: Throwable) {
                finished.complete(Unit)
                continuation.resumeWithException(error)
            }
        }
    } finally {
        withContext(NonCancellable) { finished.await() }
    }
}
