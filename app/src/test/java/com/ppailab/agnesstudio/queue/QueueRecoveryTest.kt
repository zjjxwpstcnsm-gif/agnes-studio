package com.ppailab.agnesstudio.queue

import android.app.Application
import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.data.SettingsStore
import com.ppailab.agnesstudio.model.AccessPlan
import com.ppailab.agnesstudio.model.AppSettings
import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.VideoMode
import com.ppailab.agnesstudio.model.VideoParameters
import com.ppailab.agnesstudio.model.VideoTaskSpec
import com.ppailab.agnesstudio.network.AgnesApiClient
import com.ppailab.agnesstudio.network.PayloadBuilder
import com.ppailab.agnesstudio.network.TemporaryMediaUploader
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class QueueRecoveryTest {
    @Test
    fun `worker interruption preserves relay cache and recreated processor finishes the video`() = runBlocking {
        val uploads = AtomicInteger()
        val creates = AtomicInteger()
        val creating = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val firstCall = AtomicReference<Call>()
        val fixture = Fixture { chain ->
            when (chain.request().url.host) {
                "uguu.se" -> {
                    uploads.incrementAndGet()
                    response(chain, """{"success":true,"files":[{"url":"https://files.example/ref.png"}]}""")
                }
                "agnes.example" -> when (chain.request().url.encodedPath) {
                    "/v1/videos" -> {
                        if (creates.incrementAndGet() == 1) {
                            firstCall.set(chain.call())
                            creating.countDown()
                            check(releaseRequest.await(5, TimeUnit.SECONDS))
                            throw IOException("stopped before a create response was received")
                        }
                        response(chain, """{"video_id":"video-resumed","status":"queued"}""")
                    }
                    else -> response(chain, """{"status":"completed","url":"https://files.example/result.mp4"}""")
                }
                else -> response(chain, "fake video bytes")
            }
        }
        val first = launch(Dispatchers.Default) { fixture.processor().drain("worker-before-stop") }
        var resumed: Job? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { creating.await(5, TimeUnit.SECONDS) })
            first.cancel()
            assertTrue(firstCall.get().isCanceled())
            releaseRequest.countDown()
            withTimeout(5_000) { first.join() }
            assertEquals(JobStatus.QUEUED, fixture.database.job(fixture.jobId)?.status)
            assertTrue(fixture.database.jobLogs(fixture.jobId).any {
                it.stage == "后台暂停" && it.details.orEmpty().contains("worker-before-stop")
            })

            // Reopen SQLite and construct a new processor: the cache must be on
            // disk, not merely in the old uploader's local variables.
            fixture.reopenDatabase()
            val saved = fixture.json.decodeFromString<VideoTaskSpec>(
                requireNotNull(fixture.database.job(fixture.jobId)).specJson,
            ).attachments.single()
            assertEquals("https://files.example/ref.png", saved.remoteUrl)
            assertNotNull(saved.remoteUrlExpiresAt)
            fixture.database.updateJob(fixture.jobId, JobStatus.QUEUED)
            resumed = launch(Dispatchers.Default) { fixture.processor().drain("worker-resumed") }
            withTimeout(5_000) {
                while (fixture.database.job(fixture.jobId)?.remoteId == null) delay(10)
            }
            requireNotNull(resumed).cancelAndJoin()
            assertEquals("video-resumed", fixture.database.job(fixture.jobId)?.remoteId)
            assertEquals(JobStatus.PROCESSING, fixture.database.job(fixture.jobId)?.status)
            assertEquals(1, uploads.get())

            // Advance only the persisted due time; normal production polling is
            // still governed by the separately tested 30-second floor.
            fixture.database.updateJob(fixture.jobId, JobStatus.PROCESSING)
            withTimeout(5_000) { fixture.processor().drain("worker-poll") }
            assertEquals(JobStatus.SUCCEEDED, fixture.database.job(fixture.jobId)?.status)
            assertEquals(1, uploads.get())
            assertEquals(2, creates.get()) // one interrupted attempt + one successful request
        } finally {
            releaseRequest.countDown()
            first.cancelAndJoin()
            resumed?.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun `two workers cannot upload the same queued item concurrently and user cancel stays cancelled`() = runBlocking {
        val uploads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val fixture = Fixture { chain ->
            uploads.incrementAndGet()
            call.set(chain.call())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw IOException("cancelled")
        }
        val processor = fixture.processor()
        val first = launch(Dispatchers.Default) { processor.drain("worker-1") }
        var second: Job? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            second = launch(start = CoroutineStart.UNDISPATCHED) { processor.drain("worker-2") }
            assertEquals(1, uploads.get())
            fixture.database.cancelJob(fixture.jobId)
            processor.cancel(fixture.jobId)
            assertTrue(call.get().isCanceled())
            release.countDown()
            withTimeout(5_000) {
                first.join()
                requireNotNull(second).join()
            }
            assertEquals(1, uploads.get())
            assertEquals(JobStatus.CANCELLED, fixture.database.job(fixture.jobId)?.status)
            assertFalse(fixture.database.jobLogs(fixture.jobId).any { it.stage == "中转换源" })
        } finally {
            release.countDown()
            first.cancelAndJoin()
            second?.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun `video id is durable when cancellation wins before response delivery`() = runBlocking {
        val fixture = Fixture { chain -> response(chain, """{"video_id":"video-checkpoint","status":"queued"}""") }
        var delivered = false
        lateinit var task: Job
        task = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            fixture.api.createVideo(
                "https://agnes.example", "test-only-key", buildJsonObject {}, 30,
                onCreated = { created ->
                    fixture.database.recordVideoCreated(fixture.jobId, created.videoId, created.progress)
                    task.cancel()
                },
            )
            delivered = true
        }
        try {
            task.start()
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertFalse(delivered)
            fixture.reopenDatabase()
            assertEquals("video-checkpoint", fixture.database.job(fixture.jobId)?.remoteId)
            assertEquals(JobStatus.PROCESSING, fixture.database.job(fixture.jobId)?.status)
        } finally {
            task.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun `late success preserves user cancellation and retry reuses remote id`() {
        val fixture = Fixture { chain -> response(chain, "{}") }
        try {
            fixture.database.cancelJob(fixture.jobId)
            fixture.database.recordVideoCreated(fixture.jobId, "video-late", 10)
            fixture.database.updateJob(fixture.jobId, JobStatus.FAILED)
            assertEquals(JobStatus.CANCELLED, fixture.database.job(fixture.jobId)?.status)
            assertFalse(fixture.database.recoverInterruptedJob(fixture.jobId))
            fixture.database.retryJob(fixture.jobId)
            assertEquals("video-late", fixture.database.job(fixture.jobId)?.remoteId)
            assertEquals(JobStatus.PROCESSING, fixture.database.job(fixture.jobId)?.status)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(interceptor: Interceptor) {
        private val context: Application = RuntimeEnvironment.getApplication()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val api = AgnesApiClient(client, json)
        var database: AppDatabase
            private set
        private val settings = SettingsStore(context, json)
        private val file = File.createTempFile("agnes-queue-recovery-", ".png", context.cacheDir)
        val jobId: String

        init {
            context.deleteDatabase("agnes_studio.db")
            database = AppDatabase(context, json)
            file.writeText("fake image bytes")
            settings.updateApp(AppSettings(
                baseUrl = "https://agnes.example",
                accessPlan = AccessPlan.CUSTOM,
                customVideoRpm = 100,
                temporaryUploadEnabled = true,
            ))
            val spec = VideoTaskSpec(
                "test prompt",
                VideoParameters(mode = VideoMode.KEYFRAME),
                listOf(MediaAttachment(
                    "reference", "5012.png", "image/png",
                    localPath = file.absolutePath, role = AttachmentRole.FIRST_FRAME,
                )),
            )
            jobId = requireNotNull(database.enqueueJob(Modality.VIDEO, spec.prompt, json.encodeToString(spec), 10).jobId)
        }

        fun processor() = QueueProcessor(
            database, settings, { "test-only-key" }, api, PayloadBuilder(json),
            MediaFileStore(context, client), TemporaryMediaUploader(client), json,
        )

        fun reopenDatabase() {
            database.close()
            database = AppDatabase(context, json)
        }

        fun close() {
            database.close()
            file.delete()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun response(chain: Interceptor.Chain, body: String): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
