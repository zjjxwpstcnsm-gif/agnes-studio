package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.MediaAttachment
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryMediaUploaderTest {
    @Test
    fun `switches from uguu to litterbox and checkpoints the direct url`() = runTest {
        val startedAt = System.currentTimeMillis()
        val visitedHosts = mutableListOf<String>()
        val multipartBodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            visitedHosts += request.url.host
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            multipartBodies += buffer.readUtf8()
            val (status, body) = if (request.url.host == "uguu.se") {
                503 to "temporarily unavailable"
            } else {
                200 to "https://litter.catbox.moe/reference.png"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Unavailable")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val file = File.createTempFile("agnes-relay-", ".png").apply { writeText("image") }
        val events = mutableListOf<RelayAttemptEvent>()
        var saved: TemporaryUploadResult? = null

        try {
            val result = TemporaryMediaUploader(client).upload(
                MediaAttachment(
                    id = "image-1",
                    displayName = "reference.png",
                    mimeType = "image/png",
                    localPath = file.absolutePath,
                    role = AttachmentRole.FIRST_FRAME,
                ),
                onUploaded = { saved = it },
                onAttempt = events::add,
            )

            assertEquals("https://litter.catbox.moe/reference.png", result.url)
            assertEquals(result, saved)
            assertTrue(requireNotNull(result.expiresAtMillis) > startedAt + 50 * 60_000L)
            assertEquals(listOf("uguu.se", "litterbox.catbox.moe"), visitedHosts)
            assertEquals(
                listOf(
                    RelayAttemptState.STARTED,
                    RelayAttemptState.FAILED,
                    RelayAttemptState.STARTED,
                    RelayAttemptState.SUCCEEDED,
                ),
                events.map { it.state },
            )
            assertTrue(events[1].willTryNext)
            assertTrue(multipartBodies[0].contains("files[]"))
            assertTrue(multipartBodies[1].contains("fileToUpload"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `uguu parser rejects unsuccessful response`() {
        assertEquals(null, parseUguuUrl("""{"success":false,"description":"busy"}"""))
    }

    @Test
    fun `relay url cache is reused until its safety window`() {
        val now = 1_000_000L
        val attachment = MediaAttachment(
            id = "cached-image",
            displayName = "reference.png",
            mimeType = "image/png",
            localPath = "/local/reference.png",
            remoteUrl = "https://files.example/reference.png",
            remoteUrlExpiresAt = now + 120_000L,
        )

        assertEquals(
            "https://files.example/reference.png",
            RelayUrlCachePolicy.reusableUrl(attachment, now),
        )
        assertEquals(null, RelayUrlCachePolicy.reusableUrl(attachment, now + 60_000L))
    }

    @Test
    fun `user supplied remote url does not expire locally`() {
        val attachment = MediaAttachment(
            id = "remote-image",
            displayName = "reference.png",
            mimeType = "image/png",
            remoteUrl = "https://cdn.example/reference.png",
            remoteUrlExpiresAt = 1L,
        )

        assertEquals(
            "https://cdn.example/reference.png",
            RelayUrlCachePolicy.reusableUrl(attachment, Long.MAX_VALUE),
        )
    }

    @Test
    fun `parent cancellation cancels the socket and never starts a fallback upload`() = runBlocking {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val finishNetwork = CountDownLatch(1)
        val runningCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            runningCall.set(chain.call())
            started.countDown()
            check(finishNetwork.await(5, TimeUnit.SECONDS))
            throw IOException("socket closed")
        }.build()
        val file = File.createTempFile("agnes-relay-cancel-", ".png").apply { writeText("image") }
        val events = mutableListOf<RelayAttemptEvent>()
        val task = launch(Dispatchers.Default) {
            TemporaryMediaUploader(client).upload(
                MediaAttachment("image-2", "reference.png", "image/png", localPath = file.absolutePath),
                onAttempt = events::add,
            )
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            task.cancel()
            assertTrue(runningCall.get().isCanceled())
            finishNetwork.countDown()
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertEquals(1, calls.get())
            assertEquals(listOf(RelayAttemptState.STARTED), events.map { it.state })
        } finally {
            finishNetwork.countDown()
            task.cancelAndJoin()
            file.delete()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `successful upload is saved even when parent is cancelled before result delivery`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"success":true,"files":[{"url":"https://files.example/reference.png"}]}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val file = File.createTempFile("agnes-relay-checkpoint-", ".png").apply { writeText("image") }
        val attachment = MediaAttachment("image-3", "reference.png", "image/png", localPath = file.absolutePath)
        var checkpoint: TemporaryUploadResult? = null
        var returned = false
        lateinit var task: Job
        task = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            TemporaryMediaUploader(client).upload(attachment, onUploaded = {
                checkpoint = it
                task.cancel(CancellationException("worker stopped at response boundary"))
            })
            returned = true
        }
        try {
            task.start()
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertEquals(false, returned)
            val saved = requireNotNull(checkpoint)
            assertEquals("https://files.example/reference.png", saved.url)
            val reused = TemporaryMediaUploader(client).upload(attachment.copy(
                remoteUrl = saved.url,
                remoteUrlExpiresAt = saved.expiresAtMillis,
            ))
            assertEquals(saved.url, reused.url)
            assertEquals(1, calls.get())
        } finally {
            task.cancelAndJoin()
            file.delete()
            client.dispatcher.executorService.shutdown()
        }
    }
}
