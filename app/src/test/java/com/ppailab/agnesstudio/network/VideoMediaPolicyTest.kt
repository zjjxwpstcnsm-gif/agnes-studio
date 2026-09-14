package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.MediaAttachment
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoMediaPolicyTest {
    @Test
    fun `text to video works when temporary relay is disabled`() {
        VideoMediaPolicy.validateForSubmission(
            attachments = emptyList(),
            temporaryUploadEnabled = false,
        )
    }

    @Test
    fun `remote https media works when temporary relay is disabled`() {
        VideoMediaPolicy.validateForSubmission(
            listOf(media(remoteUrl = "https://example.com/frame.png")),
            temporaryUploadEnabled = false,
        )
    }

    @Test
    fun `local media is rejected before queueing when relay is disabled`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoMediaPolicy.validateForSubmission(
                listOf(media(localPath = "/device/frame.png")),
                temporaryUploadEnabled = false,
            )
        }
    }

    @Test
    fun `local media is accepted when temporary relay is enabled`() {
        VideoMediaPolicy.validateForSubmission(
            listOf(media(localPath = "/device/frame.png")),
            temporaryUploadEnabled = true,
        )
    }

    @Test
    fun `expired relay requires upload permission even when a url is present`() {
        val attachment = media(localPath = "/local/frame.png", remoteUrl = "https://a.uguu.se/frame.png")
            .copy(remoteUrlExpiresAt = 1L)
        assertThrows(IllegalArgumentException::class.java) {
            VideoMediaPolicy.validateForSubmission(listOf(attachment), temporaryUploadEnabled = false)
        }
        VideoMediaPolicy.validateForSubmission(listOf(attachment), temporaryUploadEnabled = true)
    }

    @Test
    fun `expired url without original is rejected instead of sent`() {
        val attachment = media(remoteUrl = "https://a.uguu.se/frame.png").copy(remoteUrlExpiresAt = 1L)
        assertThrows(IllegalArgumentException::class.java) {
            VideoMediaPolicy.validateForSubmission(listOf(attachment), temporaryUploadEnabled = true)
        }
    }

    private fun media(localPath: String? = null, remoteUrl: String? = null) = MediaAttachment(
        id = "media-1",
        displayName = "frame.png",
        mimeType = "image/png",
        localPath = localPath,
        remoteUrl = remoteUrl,
        role = AttachmentRole.FIRST_FRAME,
    )
}
