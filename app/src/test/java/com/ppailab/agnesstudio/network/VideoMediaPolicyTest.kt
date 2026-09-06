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

    private fun media(localPath: String? = null, remoteUrl: String? = null) = MediaAttachment(
        id = "media-1",
        displayName = "frame.png",
        mimeType = "image/png",
        localPath = localPath,
        remoteUrl = remoteUrl,
        role = AttachmentRole.FIRST_FRAME,
    )
}
