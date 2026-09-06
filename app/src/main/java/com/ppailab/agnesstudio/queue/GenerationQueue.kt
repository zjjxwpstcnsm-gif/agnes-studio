package com.ppailab.agnesstudio.queue

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ppailab.agnesstudio.AgnesStudioApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class GenerationQueue(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun kick(delayMillis: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<GenerationQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()
        // Append one wake-up behind an in-flight worker so a task cannot be stranded by
        // the small race between the worker observing an empty database and finishing.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "agnes-persistent-generation-queue"
    }
}

class GenerationQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as AgnesStudioApplication).graph
        return try {
            graph.queueProcessor.drain(id.toString()) {
                val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    stopReason.toString()
                } else {
                    "unavailable_on_api_${Build.VERSION.SDK_INT}"
                }
                "code=$reason, isStopped=$isStopped"
            }
            Result.success()
        } catch (elapsed: TimeoutCancellationException) {
            // Our bounded work window yields back to WorkManager. Cancellation of
            // the worker itself must still propagate, not be turned into a retry result.
            currentCoroutineContext().ensureActive()
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            Result.retry()
        }
    }
}
