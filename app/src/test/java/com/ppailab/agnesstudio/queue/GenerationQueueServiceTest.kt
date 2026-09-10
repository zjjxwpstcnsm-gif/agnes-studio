package com.ppailab.agnesstudio.queue

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import com.ppailab.agnesstudio.AgnesStudioApplication
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.model.AccessPlan
import com.ppailab.agnesstudio.model.AppSettings
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.VideoParameters
import com.ppailab.agnesstudio.model.VideoTaskSpec
import com.ppailab.agnesstudio.network.AgnesApiClient
import com.ppailab.agnesstudio.network.TemporaryMediaUploader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = AgnesStudioApplication::class)
class GenerationQueueServiceTest {
    @Before
    fun initializeWorkManager() {
        val app = RuntimeEnvironment.getApplication() as AgnesStudioApplication
        runCatching { WorkManager.getInstance(app) }.getOrElse {
            WorkManager.initialize(app, Configuration.Builder().build())
            WorkManager.getInstance(app)
        }
    }

    @Test
    fun `queue full retries after activity closes without another user wakeup`() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as AgnesStudioApplication
        val graph = app.graph
        val creates = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val attempt = creates.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (attempt == 1) 503 else 200).message("test")
                .body((if (attempt == 1) """{"code":"video_queue_full","message":"video queue is full"}"""
                    else """{"video_id":"retry-after-background","status":"queued"}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        graph.settings.updateApp(AppSettings(baseUrl = "https://agnes.example", accessPlan = AccessPlan.CUSTOM, customVideoRpm = 100))
        val processor = QueueProcessor(graph.database, graph.settings, { "test-key" },
            AgnesApiClient(client, graph.json), graph.payloadBuilder, MediaFileStore(app, client),
            TemporaryMediaUploader(client), graph.json)
        val queue = GenerationQueue(app, graph.database, processor)
        app.graph = graph.copy(queueProcessor = processor, generationQueue = queue)
        val spec = VideoTaskSpec("background retry", VideoParameters(), emptyList())
        val id = requireNotNull(graph.database.enqueueJob(Modality.VIDEO, spec.prompt, graph.json.encodeToString(spec), 10).jobId)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val service = Robolectric.buildService(GenerationQueueService::class.java).create()
        try {
            service.startCommand(0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(3_000) { while (graph.database.job(id)?.status != JobStatus.RETRY_WAIT) delay(10) }
            activity.pause().stop().destroy()
            try {
                withTimeout(10_000) {
                    while (graph.database.job(id)?.remoteId == null) {
                        // A paused Robolectric looper/Android clock does not
                        // advance just because the host coroutine suspends.
                        // Simulate elapsed device time, never a UI wake/kick.
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                        delay(10)
                    }
                }
            } catch (error: Exception) {
                println("Background retry state: ${graph.database.job(id)}")
                println("Background retry logs: ${graph.database.jobLogs(id)}")
                throw error
            }
            assertEquals(2, creates.get())
            assertEquals("retry-after-background", graph.database.job(id)?.remoteId)
            assertTrue(queue.foregroundRunning)
            assertTrue(graph.database.jobLogs(id).any { it.stage == "重试计划" })
        } finally {
            graph.database.cancelJob(id)
            processor.cancel(id)
            service.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `activity stop and recovery worker do not cancel foreground create or duplicate remote task`() = runBlocking {
        val app = (RuntimeEnvironment.getApplication() as AgnesStudioApplication)
        val graph = app.graph
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val creates = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            creates.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"video_id":"service-video","status":"queued"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        graph.settings.updateApp(AppSettings(baseUrl = "https://agnes.example", accessPlan = AccessPlan.CUSTOM, customVideoRpm = 100))
        val processor = QueueProcessor(graph.database, graph.settings, { "test-key" },
            AgnesApiClient(client, graph.json), graph.payloadBuilder, MediaFileStore(app, client),
            TemporaryMediaUploader(client), graph.json)
        val queue = GenerationQueue(app, graph.database, processor)
        app.graph = graph.copy(queueProcessor = processor, generationQueue = queue)
        val spec = VideoTaskSpec("test foreground", VideoParameters(), emptyList())
        val jobId = requireNotNull(graph.database.enqueueJob(Modality.VIDEO, spec.prompt, graph.json.encodeToString(spec), 10).jobId)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val service = Robolectric.buildService(GenerationQueueService::class.java).create()
        try {
            queue.startFromUser()
            assertEquals(GenerationQueueService::class.java.name, shadowOf(app).nextStartedService.component?.className)
            service.startCommand(0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(queue.foregroundRunning)
            val notification = shadowOf(service.get()).lastForegroundNotification
            assertNotNull(notification)
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(wakeLock.isHeld)

            activity.pause().stop().destroy()
            withTimeout(500) { processor.drainReady("recovery", { queue.foregroundRunning }, { "test" }) }
            release.countDown()
            withTimeout(3_000) {
                while (graph.database.job(jobId)?.remoteId == null) delay(10)
            }
            assertEquals(1, creates.get())
            assertEquals("service-video", graph.database.job(jobId)?.remoteId)
            assertEquals(JobStatus.PROCESSING, graph.database.job(jobId)?.status)
            assertTrue(queue.foregroundRunning)

            graph.database.cancelJob(jobId)
            processor.cancel(jobId)
            service.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(queue.foregroundRunning)
            assertFalse(wakeLock.isHeld)
        } finally {
            release.countDown()
            if (queue.foregroundRunning) service.destroy()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `empty queue does not start a foreground service`() {
        val app = (RuntimeEnvironment.getApplication() as AgnesStudioApplication)
        app.graph.generationQueue.startFromUser()
        assertEquals(null, shadowOf(app).nextStartedService)
    }
}
