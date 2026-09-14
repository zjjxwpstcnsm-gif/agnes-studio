package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.model.ChatMessage
import com.ppailab.agnesstudio.model.ChatModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolve all turns again so expired relay URLs never become permanent chat history. */
class ChatMediaResolver(
    private val files: MediaFileStore,
    private val uploader: TemporaryMediaUploader,
    private val database: AppDatabase,
) {
    suspend fun resolve(message: ChatMessage, model: String, uploadEnabled: Boolean): ChatMessage {
        if (model != ChatModel.DEFAULT) return message.copy(attachments = message.attachments.map {
            if (it.remoteUrl != null) it else it.copy(remoteUrl = files.asDataUri(it), localPath = null)
        })
        var attachments = message.attachments
        for (attachment in message.attachments) {
            val cached = RelayUrlCachePolicy.reusableUrl(attachment)
            if (cached?.toHttpUrlOrNull()?.isHttps == true) continue
            require(attachment.localPath != null) {
                "历史图片 ${attachment.displayName} 的 URL 已过期且没有本地副本，请在新对话中重新添加图片。"
            }
            require(uploadEnabled) {
                "Agnes 3.0 Flash 的本地历史图片需要公开 URL。请在设置中启用公开素材中转池，或新建纯文本对话。"
            }
            uploader.upload(attachment.copy(remoteUrl = null, remoteUrlExpiresAt = null), onUploaded = { result ->
                attachments = attachments.map {
                    if (it.id == attachment.id) it.copy(remoteUrl = result.url, remoteUrlExpiresAt = result.expiresAtMillis) else it
                }
                // Keep the local path for re-upload after expiry; persist before cancellation can discard the URL.
                database.updateMessageAttachments(message.id, attachments)
            })
        }
        return message.copy(attachments = attachments)
    }
}
