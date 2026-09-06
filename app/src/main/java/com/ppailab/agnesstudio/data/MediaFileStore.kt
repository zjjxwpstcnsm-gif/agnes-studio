package com.ppailab.agnesstudio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.network.executeCancellable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MediaFileStore(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    suspend fun import(uri: Uri, role: AttachmentRole): MediaAttachment = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(uri) ?: "media-${UUID.randomUUID()}"
        val extension = displayName.substringAfterLast('.', extensionFor(mime))
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .take(10)
            .ifBlank { extensionFor(mime) }
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        val maxBytes = when {
            mime.startsWith("image/") -> 20L * 1024 * 1024
            mime.startsWith("audio/") -> 100L * 1024 * 1024
            mime.startsWith("video/") -> 500L * 1024 * 1024
            else -> 20L * 1024 * 1024
        }
        if (target.length() > maxBytes) {
            target.delete()
            throw IllegalArgumentException("素材过大：$displayName（上限 ${maxBytes / 1024 / 1024} MB）")
        }
        MediaAttachment(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            mimeType = mime,
            localPath = target.absolutePath,
            role = role,
        )
    }

    suspend fun asDataUri(attachment: MediaAttachment): String = withContext(Dispatchers.IO) {
        attachment.remoteUrl?.let { return@withContext it }
        val file = requireNotNull(attachment.localPath).let(::File)
        require(file.exists()) { "素材文件已不存在：${attachment.displayName}" }
        require(file.length() <= 20L * 1024 * 1024) { "图片超过 20 MB，无法安全编码为 Data URI" }
        "data:${attachment.mimeType};base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    suspend fun saveBase64Image(base64: String, jobId: String): String = withContext(Dispatchers.IO) {
        val clean = base64.substringAfter("base64,", base64)
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        val target = generatedFile("image-$jobId.png")
        target.writeBytes(bytes)
        target.absolutePath
    }

    suspend fun download(url: String, jobId: String, isVideo: Boolean): String {
        val request = Request.Builder().url(url).get().build()
        return httpClient.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载生成结果失败：HTTP ${response.code}")
            val contentType = response.body?.contentType()?.toString().orEmpty()
            val extension = when {
                isVideo -> "mp4"
                "jpeg" in contentType -> "jpg"
                "webp" in contentType -> "webp"
                else -> "png"
            }
            val target = generatedFile("${if (isVideo) "video" else "image"}-$jobId.$extension")
            response.body?.byteStream().use { input ->
                requireNotNull(input) { "生成结果为空" }
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }
    }

    private fun generatedFile(name: String): File = File(context.filesDir, "generated").run {
        mkdirs()
        File(this, name)
    }

    private fun queryName(uri: Uri): String? = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun extensionFor(mime: String) = when {
        mime.startsWith("image/") -> mime.substringAfter('/').replace("jpeg", "jpg")
        mime.startsWith("video/") -> mime.substringAfter('/')
        mime.startsWith("audio/") -> mime.substringAfter('/')
        else -> "bin"
    }
}
