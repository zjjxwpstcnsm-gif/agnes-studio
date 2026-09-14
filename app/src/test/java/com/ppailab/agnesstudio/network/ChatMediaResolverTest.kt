package com.ppailab.agnesstudio.network

import android.app.Application
import com.ppailab.agnesstudio.data.*
import com.ppailab.agnesstudio.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatMediaResolverTest {
    @Test
    fun `local chat images require opt in and reuse durable URLs until expiry`() = runBlocking<Unit> {
        val context: Application = RuntimeEnvironment.getApplication()
        context.deleteDatabase("agnes_studio.db")
        val db = AppDatabase(context, Json { encodeDefaults = true; ignoreUnknownKeys = true })
        var uploads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            uploads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"files":[{"url":"https://d.uguu.se/image$uploads.png"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val local = File(context.filesDir, "chat.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val conv = db.createConversation()
            val attachment = MediaAttachment("img", "chat.png", "image/png", localPath = local.path, role = AttachmentRole.CHAT_IMAGE)
            val message = ChatMessage("user", conv.id, "user", "describe", "", emptyList(), null, listOf(attachment), MessageState.COMPLETE, null, 0L)
            db.insertMessage(message)
            val resolver = ChatMediaResolver(MediaFileStore(context, client), TemporaryMediaUploader(client), db)
            try { resolver.resolve(message, ChatModel.DEFAULT, false); fail("uploaded without opt in") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, uploads)
            val first = resolver.resolve(message, ChatModel.DEFAULT, true)
            assertEquals(1, uploads)
            assertEquals(local.path, first.attachments.single().localPath)
            assertEquals(first, db.messages(conv.id).single())
            assertEquals(first, resolver.resolve(db.messages(conv.id).single(), ChatModel.DEFAULT, false))
            assertEquals(1, uploads)
            val expired = first.copy(attachments = first.attachments.map { it.copy(remoteUrlExpiresAt = 1L) })
            db.updateMessageAttachments(message.id, expired.attachments)
            val second = resolver.resolve(expired, ChatModel.DEFAULT, true)
            assertEquals(2, uploads)
            assertNotEquals(first.attachments.single().remoteUrl, second.attachments.single().remoteUrl)
            assertEquals(second, db.messages(conv.id).single())
            val remote = message.copy(attachments = listOf(attachment.copy(localPath = null, remoteUrl = "https://example.com/image.png")))
            assertEquals(remote, resolver.resolve(remote, ChatModel.DEFAULT, false))
            val unavailable = expired.copy(attachments = expired.attachments.map { it.copy(localPath = null) })
            try { resolver.resolve(unavailable, ChatModel.DEFAULT, true); fail("used expired URL") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, uploads)
        } finally {
            db.close()
            local.delete()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
