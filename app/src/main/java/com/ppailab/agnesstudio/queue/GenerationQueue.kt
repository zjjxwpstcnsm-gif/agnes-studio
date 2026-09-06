package com.ppailab.agnesstudio.queue

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ppailab.agnesstudio.AgnesStudioApplication
import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.model.JobLogLevel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

class GenerationQueue(
    context: Context,
    private val database: AppDatabase,
    private val processor: QueueProcessor,
) {
    private val context = context.applicationContext
    private val workManager get() = WorkManager.getInstance(context)

    @Volatile
    internal var foregroundRunning = false

    /** Call synchronously from a visible Activity/user action, before switching apps. */
    fun startFromUser() {
        if (database.earliestNextAttempt() == null) return
        processor.wake()
        try {
            ContextCompat.startForegroundService(context, Intent(context, GenerationQueueService::class.java))
            kick(RECOVERY_INTERVAL_MILLIS)
        } catch (error: IllegalStateException) {
            foregroundUnavailable(error)
        } catch (error: SecurityException) {
            foregroundUnavailable(error)
        }
    }

    private fun foregroundUnavailable(error: Exception) {
        database.nextRunnableJob()?.let {
            database.addJobLog(
                it.id, JobLogLevel.WARNING, "后台调度",
                "系统暂不允许启动持续执行，已保留任务并安排恢复；返回应用后会继续处理。",
                error.javaClass.simpleName,
            )
        }
        kick()
    }

    /** Durable fallback only; never the primary executor for a user submission. */
    fun kick(delayMillis: Long = 0L, append: Boolean = false) {
        if (database.earliestNextAttempt() == null) return
        val request = OneTimeWorkRequestBuilder<GenerationQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()
        // User wakeups coalesce. A finishing recovery worker appends exactly one
        // successor, since KEEP would discard a request behind itself.
        workManager.enqueueUniqueWork(
            WORK_NAME,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "agnes-persistent-generation-queue"
        internal const val RECOVERY_INTERVAL_MILLIS = 60_000L
    }
}

class GenerationQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as AgnesStudioApplication).graph
        return try {
            withTimeout(8 * 60_000L) {
                graph.queueProcessor.drainReady(id.toString(), { graph.generationQueue.foregroundRunning }) {
                    val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        stopReason.toString()
                    } else {
                        "unavailable_on_api_${Build.VERSION.SDK_INT}"
                    }
                    "code=$reason, isStopped=$isStopped"
                }
            }
            graph.database.earliestNextAttempt()?.let { next ->
                val delay = if (graph.generationQueue.foregroundRunning) {
                    GenerationQueue.RECOVERY_INTERVAL_MILLIS
                } else {
                    (next - System.currentTimeMillis()).coerceAtLeast(1_000L)
                }
                graph.generationQueue.kick(delay, append = true)
            }
            Result.success()
        } catch (elapsed: TimeoutCancellationException) {
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
