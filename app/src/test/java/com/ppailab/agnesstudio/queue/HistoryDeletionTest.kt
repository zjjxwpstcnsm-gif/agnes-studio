package com.ppailab.agnesstudio.queue

import android.app.Application
import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.data.SettingsStore
import com.ppailab.agnesstudio.model.*
import com.ppailab.agnesstudio.network.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HistoryDeletionTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var files: MediaFileStore
    private lateinit var processor: QueueProcessor
    private val generated get() = File(context.filesDir, "generated").apply { mkdirs() }
    private var responder: (Request) -> ResponseBody = { "image/video bytes".toResponseBody("image/png".toMediaType()) }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(responder(chain.request())).build()
    }.build()

    @Before
    fun setUp() {
        context.deleteDatabase("agnes_studio.db")
        database = AppDatabase(context, json)
        settings = SettingsStore(context, json)
        settings.updateApp(AppSettings(baseUrl = "https://agnes.example"))
        files = MediaFileStore(context, client)
        processor = QueueProcessor(database, settings, { "test-only-key" }, AgnesApiClient(client, json),
            PayloadBuilder(json), files, TemporaryMediaUploader(client), json)
    }

    @After
    fun tearDown() {
        database.close()
        generated.deleteRecursively()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `single delete removes saved images and videos with logs and survives database reopen`() = runBlocking<Unit> {
        for (modality in Modality.entries) {
            val id = enqueue(modality)
            val path = if (modality == Modality.IMAGE) files.saveBase64Image("aW1hZ2U=", id)
                else files.download("https://files.example/video.mp4", id, true)
            database.updateJob(id, JobStatus.SUCCEEDED, resultPath = path, progress = 100)
            database.addJobLog(id, JobLogLevel.INFO, "完成", "saved result")
            assertTrue(File(path).isFile)
            assertTrue(processor.deleteFinishedJob(id))
            assertNull(database.job(id))
            assertTrue(database.jobLogs(id).isEmpty())
            assertFalse(File(path).exists())
            assertFalse(processor.deleteFinishedJob(id))
        }
        database.close()
        database = AppDatabase(context, json)
        assertTrue(database.jobs().isEmpty())
    }

    @Test
    fun `failed and cancelled history also removes partial downloads without result path`() = runBlocking<Unit> {
        for (status in listOf(JobStatus.FAILED, JobStatus.CANCELLED)) {
            val id = enqueue(Modality.VIDEO)
            database.updateJob(id, status)
            val partial = File(generated, "video-$id.mp4").apply { writeText("partial bytes") }
            assertNull(database.job(id)?.resultPath)
            assertTrue(processor.deleteFinishedJob(id))
            assertFalse(partial.exists())
        }
    }

    @Test
    fun `deleting a source preserves reference materials copied jobs other results and rate history`() = runBlocking<Unit> {
        val reference = File(context.filesDir, "attachments/original.png").apply { parentFile?.mkdirs(); writeText("reference") }
        val spec = ImageTaskSpec("prompt", ImageParameters(), listOf(MediaAttachment(
            "reference", "original.png", "image/png", localPath = reference.absolutePath)))
        val sourceId = requireNotNull(database.enqueueJob(Modality.IMAGE, spec.prompt, json.encodeToString(spec), 20).jobId)
        val output = files.saveBase64Image("aW1hZ2U=", sourceId)
        database.updateJob(sourceId, JobStatus.SUCCEEDED, resultPath = output)
        val copiedId = requireNotNull(database.duplicateJob(sourceId, 20).jobId)
        val otherId = enqueue(Modality.IMAGE)
        val otherOutput = files.saveBase64Image("aW1hZ2U=", otherId)
        assertTrue(database.reserveRateSlot(RatePolicy.VIDEO, 1).granted)
        assertTrue(processor.deleteFinishedJob(sourceId))
        assertFalse(File(output).exists())
        assertTrue(reference.isFile)
        assertNotNull(database.job(copiedId))
        assertTrue(File(otherOutput).isFile)
        assertFalse(database.reserveRateSlot(RatePolicy.VIDEO, 1).granted)
        reference.delete()
    }

    @Test
    fun `active jobs and history resumed since confirmation are never deleted`() = runBlocking<Unit> {
        for (status in JobStatus.entries.filterNot { it.isFinished }) {
            val id = enqueue(Modality.IMAGE)
            database.updateJob(id, status)
            val path = files.saveBase64Image("aW1hZ2U=", id)
            assertFalse(processor.deleteFinishedJob(id))
            assertTrue(File(path).exists())
            assertNotNull(database.job(id))
        }
        val id = enqueue(Modality.IMAGE)
        database.updateJob(id, JobStatus.FAILED)
        database.retryJob(id)
        assertFalse(database.deleteFinishedJob(id) { error("Must not touch resumed task files") })
        assertNotNull(database.job(id))
    }

    @Test
    fun `unrelated result paths are not followed and filesystem errors keep history for retry`() = runBlocking<Unit> {
        val unrelated = File(context.filesDir, "attachments/keep.png").apply { parentFile?.mkdirs(); writeText("keep") }
        val id = enqueue(Modality.IMAGE)
        database.updateJob(id, JobStatus.SUCCEEDED, resultPath = unrelated.absolutePath)
        assertTrue(processor.deleteFinishedJob(id))
        assertTrue(unrelated.isFile)
        unrelated.delete()
        val blockedId = enqueue(Modality.IMAGE)
        database.updateJob(blockedId, JobStatus.FAILED)
        database.addJobLog(blockedId, JobLogLevel.ERROR, "失败", "keep log")
        val blocked = File(generated, "image-$blockedId.png").apply { mkdirs() }
        var failed = false
        try { processor.deleteFinishedJob(blockedId) } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertNotNull(database.job(blockedId))
        assertEquals(1, database.jobLogs(blockedId).size)
        assertTrue(blocked.isDirectory)
    }

    @Test
    fun `bulk selection deletes only its snapshot and skips a restarted item`() = runBlocking<Unit> {
        val selected = List(3) { enqueue(Modality.IMAGE).also { database.updateJob(it, JobStatus.FAILED) } }
        val outputs = selected.associateWith { files.saveBase64Image("aW1hZ2U=", it) }
        val newlyFinished = enqueue(Modality.IMAGE)
        database.updateJob(newlyFinished, JobStatus.SUCCEEDED)
        database.retryJob(selected.first())
        var deleted = 0
        for (id in selected) if (processor.deleteFinishedJob(id)) deleted++
        assertEquals(2, deleted)
        assertTrue(File(requireNotNull(outputs[selected.first()])).exists())
        assertNotNull(database.job(newlyFinished))
        selected.drop(1).forEach { assertFalse(File(requireNotNull(outputs[it])).exists()) }
    }

    @Test
    fun `delete waits for cancelled download callback and leaves no late file behind`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        responder = { request ->
            if (request.url.host == "agnes.example") {
                """{"status":"completed","url":"https://files.example/result.mp4"}"""
                    .toResponseBody("application/json".toMediaType())
            } else {
                object : ResponseBody() {
                    private val stream = object : ForwardingSource(Buffer().writeUtf8("video bytes")) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    override fun contentType() = "video/mp4".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = stream
                }
            }
        }
        val id = enqueue(Modality.VIDEO)
        database.updateJob(id, JobStatus.PROCESSING, remoteId = "old-video")
        val drain = launch(Dispatchers.Default) { processor.drainReady("test", { false }, { "test" }) }
        var deletion: Deferred<Boolean>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            database.cancelJob(id)
            processor.cancel(id)
            deletion = async(start = CoroutineStart.UNDISPATCHED) { processor.deleteFinishedJob(id) }
            assertFalse(requireNotNull(deletion).isCompleted)
            assertNotNull(database.job(id))
            release.countDown()
            assertTrue(withTimeout(5_000) { requireNotNull(deletion).await() })
            withTimeout(5_000) { drain.join() }
            assertNull(database.job(id))
            assertFalse(File(generated, "video-$id.mp4").exists())
        } finally {
            release.countDown()
            drain.cancelAndJoin()
            deletion?.cancelAndJoin()
        }
    }

    private fun enqueue(modality: Modality): String {
        val spec = if (modality == Modality.IMAGE) json.encodeToString(ImageTaskSpec("prompt", ImageParameters(), emptyList()))
            else json.encodeToString(VideoTaskSpec("prompt", VideoParameters(), emptyList()))
        return requireNotNull(database.enqueueJob(modality, "prompt", spec, 20).jobId)
    }
}
