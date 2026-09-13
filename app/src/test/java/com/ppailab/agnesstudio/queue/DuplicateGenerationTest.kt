package com.ppailab.agnesstudio.queue

import android.app.Application
import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.data.SettingsStore
import com.ppailab.agnesstudio.model.*
import com.ppailab.agnesstudio.network.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
class DuplicateGenerationTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var file: File
    private var uploads = 0
    private val creates = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = when (request.url.host) {
            "uguu.se" -> {
                uploads++
                """{"success":true,"files":[{"url":"https://files.example/fresh-$uploads.png"}]}"""
            }
            "agnes.example" -> {
                creates += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                when (request.url.encodedPath) {
                    "/v1/videos" -> """{"video_id":"new-video-${creates.size}","status":"queued"}"""
                    "/v1/images/generations" -> """{"data":[{"b64_json":"aW1hZ2U="}]}"""
                    else -> error("A duplicate must create a new video, not poll the old ID")
                }
            }
            else -> error("Unexpected network access: ${request.url}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Before
    fun setUp() {
        context.deleteDatabase("agnes_studio.db")
        database = AppDatabase(context, json)
        settings = SettingsStore(context, json)
        settings.updateApp(AppSettings(baseUrl = "https://agnes.example", temporaryUploadEnabled = true))
        file = File.createTempFile("duplicate-source-", ".png", context.cacheDir).apply { writeText("original image") }
    }

    @After
    fun tearDown() {
        database.close()
        file.delete()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `image copy keeps exact historical parameters and uses the image rate bucket`() = runBlocking {
        val spec = ImageTaskSpec("original prompt", ImageParameters(size = "4K", ratio = "3:2",
            responseFormat = ImageResponseFormat.BASE64, extraJson = """{"seed":42}"""), listOf(media().copy(remoteUrl = null, remoteUrlExpiresAt = null)))
        val sourceId = requireNotNull(database.enqueueJob(Modality.IMAGE, spec.prompt, json.encodeToString(spec), 10).jobId)
        database.updateJob(sourceId, JobStatus.SUCCEEDED, attempt = 3, progress = 100,
            resultPath = "/old/image.png", resultUrl = "https://old.example/image.png")
        val original = requireNotNull(database.job(sourceId))
        settings.updateImage(ImageParameters(size = "1K", ratio = "1:1"))
        val copyId = requireNotNull(database.duplicateJob(sourceId, 10).jobId)
        assertFreshCopy(original, requireNotNull(database.job(copyId)))
        assertTrue(database.reserveRateSlot(RatePolicy.imageBucket("4K"), 1).granted)
        processor().drainReady("test", { false }, { "test" })
        assertEquals(JobStatus.WAITING_RATE_LIMIT, database.job(copyId)?.status)
        assertTrue(creates.isEmpty())
        elapseRateWait(copyId)
        processor().drainReady("test", { false }, { "test" })
        assertEquals(JobStatus.SUCCEEDED, database.job(copyId)?.status)
        assertEquals(1, creates.size)
        assertTrue(creates.single().contains("4K"))
        assertTrue(creates.single().contains("original prompt"))
        assertTrue(creates.single().contains("base64,"))
        assertEquals(original, database.job(sourceId))
    }

    @Test
    fun `video copy reuploads expired media and creates a new remote id`() = runBlocking {
        val sourceId = videoSource(media().copy(remoteUrlExpiresAt = System.currentTimeMillis() - 1))
        val original = requireNotNull(database.job(sourceId))
        val copyId = requireNotNull(database.duplicateJob(sourceId, 10).jobId)
        assertFreshCopy(original, requireNotNull(database.job(copyId)))
        processor().drainReady("test", { false }, { "test" })
        assertEquals(1, uploads)
        assertEquals(1, creates.size)
        assertTrue(creates.single().contains("https://files.example/fresh-1.png"))
        assertFalse(creates.single().contains("old.uguu"))
        assertEquals("new-video-1", database.job(copyId)?.remoteId)
        val saved = json.decodeFromString<VideoTaskSpec>(requireNotNull(database.job(copyId)).specJson)
        assertEquals(file.absolutePath, saved.attachments.single().localPath)
        assertTrue(requireNotNull(saved.attachments.single().remoteUrlExpiresAt) > System.currentTimeMillis())
        assertEquals(original, database.job(sourceId))
    }

    @Test
    fun `valid video cache is reused but expiry is rechecked after a rate wait and restart`() = runBlocking {
        // Fresh when copied, within the expiry safety window after rate waiting.
        val sourceId = videoSource(media().copy(remoteUrlExpiresAt = System.currentTimeMillis() + 90_000))
        val copyId = requireNotNull(database.duplicateJob(sourceId, 10).jobId)
        assertTrue(database.reserveRateSlot(RatePolicy.VIDEO, 1).granted)
        processor().drainReady("before-wait", { false }, { "test" })
        assertEquals(JobStatus.WAITING_RATE_LIMIT, database.job(copyId)?.status)
        assertEquals(0, uploads)
        assertTrue(creates.isEmpty())
        database.close()
        database = AppDatabase(context, json)
        elapseRateWait(copyId)
        processor().drainReady("after-restart", { false }, { "test" })
        assertEquals(1, uploads)
        assertEquals(1, creates.size)
        assertTrue(creates.single().contains("fresh-1.png"))
        assertEquals("new-video-1", database.job(copyId)?.remoteId)
    }

    @Test
    fun `fresh video cache is copied without another upload`() = runBlocking {
        val sourceId = videoSource(media())
        val copyId = requireNotNull(database.duplicateJob(sourceId, 10).jobId)
        processor().drainReady("test", { false }, { "test" })
        assertEquals(0, uploads)
        assertTrue(creates.single().contains("https://old.uguu.se/reference.png"))
        assertEquals("new-video-1", database.job(copyId)?.remoteId)
    }

    @Test
    fun `missing original file with expired cache fails without uploading or creating`() = runBlocking {
        val sourceId = videoSource(media().copy(remoteUrlExpiresAt = 1))
        val copyId = requireNotNull(database.duplicateJob(sourceId, 10).jobId)
        file.delete()
        processor().drainReady("test", { false }, { "test" })
        assertEquals(JobStatus.FAILED, database.job(copyId)?.status)
        assertEquals(0, uploads)
        assertTrue(creates.isEmpty())
    }

    @Test
    fun `copy obeys capacity and does not change a processing source`() {
        val sourceId = videoSource(media())
        database.updateJob(sourceId, JobStatus.PROCESSING)
        val original = requireNotNull(database.job(sourceId))
        assertFalse(database.duplicateJob(sourceId, 1).accepted)
        assertFalse(database.duplicateJob("deleted-source", 10).accepted)
        assertEquals(1, database.jobs().size)
        val receipt = database.duplicateJob(sourceId, 2)
        assertTrue(receipt.accepted)
        assertFreshCopy(original, requireNotNull(database.job(requireNotNull(receipt.jobId))))
        assertEquals(original, database.job(sourceId))
    }

    private fun media() = MediaAttachment("reference", "frame.png", "image/png",
        localPath = file.absolutePath, remoteUrl = "https://old.uguu.se/reference.png",
        remoteUrlExpiresAt = System.currentTimeMillis() + 3 * 60 * 60_000L, role = AttachmentRole.FIRST_FRAME)

    private fun videoSource(attachment: MediaAttachment): String {
        val spec = VideoTaskSpec("historical video", VideoParameters(mode = VideoMode.KEYFRAME,
            seconds = 12, seed = 23, extraJson = """{"custom_field":"preserved"}"""), listOf(attachment))
        val id = requireNotNull(database.enqueueJob(Modality.VIDEO, spec.prompt, json.encodeToString(spec), 10).jobId)
        database.updateJob(id, JobStatus.SUCCEEDED, attempt = 4, progress = 100,
            remoteId = "old-video-id", resultPath = "/old/result.mp4", resultUrl = "https://old.example/result.mp4")
        return id
    }

    private fun assertFreshCopy(original: GenerationJob, copy: GenerationJob) {
        assertNotEquals(original.id, copy.id)
        assertEquals(original.specJson, copy.specJson)
        assertEquals(original.prompt, copy.prompt)
        assertEquals(JobStatus.QUEUED, copy.status)
        assertEquals(0, copy.attempt)
        assertEquals(0, copy.progress)
        assertNull(copy.remoteId)
        assertNull(copy.resultPath)
        assertNull(copy.resultUrl)
        assertNull(copy.errorKind)
    }

    private fun elapseRateWait(id: String) {
        // Robolectric uptime does not advance java.lang.System wall time used
        // by SQLite deadlines. Age the persisted timestamps by one rate window.
        database.writableDatabase.execSQL("UPDATE rate_events SET occurred_at = occurred_at - 61000")
        val copy = requireNotNull(database.job(id))
        if (copy.modality == Modality.VIDEO) {
            val spec = json.decodeFromString<VideoTaskSpec>(copy.specJson)
            database.updateJobSpec(id, json.encodeToString(spec.copy(attachments = spec.attachments.map {
                it.copy(remoteUrlExpiresAt = it.remoteUrlExpiresAt?.minus(61_000L))
            })))
        }
        database.updateJob(id, JobStatus.WAITING_RATE_LIMIT, nextAttemptAt = System.currentTimeMillis())
    }

    private fun processor() = QueueProcessor(database, settings, { "test-only-key" },
        AgnesApiClient(client, json), PayloadBuilder(json), MediaFileStore(context, client),
        TemporaryMediaUploader(client), json)
}
