package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.ImageParameters
import com.ppailab.agnesstudio.model.ImageTaskSpec
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.model.VideoMode
import com.ppailab.agnesstudio.model.VideoParameters
import com.ppailab.agnesstudio.model.VideoTaskSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayloadBuilderTest {
    private val builder = PayloadBuilder(Json { ignoreUnknownKeys = true })

    @Test
    fun `places image references and response format inside extra body`() {
        val payload = builder.image(
            ImageTaskSpec("portrait", ImageParameters(size = "2K", ratio = "9:16")),
            listOf("data:image/png;base64,AA=="),
        )

        val extraBody = payload.getValue("extra_body").jsonObject
        assertEquals("url", extraBody.getValue("response_format").jsonPrimitive.content)
        assertEquals(1, extraBody.getValue("image").jsonArray.size)
    }

    @Test
    fun `serializes seconds as a string for video 25`() {
        val spec = VideoTaskSpec(
            prompt = "slow camera move",
            parameters = VideoParameters(mode = VideoMode.TEXT, seconds = 8),
        )

        val payload = builder.video(spec, emptyMap())
        assertEquals("8", payload.getValue("seconds").jsonPrimitive.content)
    }

    @Test
    fun `rejects reference video on free flash model`() {
        val media = MediaAttachment(
            id = "video-1",
            displayName = "reference.mp4",
            mimeType = "video/mp4",
            remoteUrl = "https://example.com/reference.mp4",
            role = AttachmentRole.REFERENCE_VIDEO,
        )
        val spec = VideoTaskSpec(
            prompt = "follow reference motion",
            parameters = VideoParameters(mode = VideoMode.REFERENCE),
            attachments = listOf(media),
        )

        assertThrows(IllegalArgumentException::class.java) { builder.validateVideo(spec) }
    }

    @Test
    fun `keyframe mode sends resolved frame url at top level`() {
        val frame = MediaAttachment(
            id = "frame-1",
            displayName = "first.png",
            mimeType = "image/png",
            remoteUrl = "https://example.com/first.png",
            role = AttachmentRole.FIRST_FRAME,
        )
        val payload = builder.video(
            VideoTaskSpec(
                prompt = "camera pushes in",
                parameters = VideoParameters(mode = VideoMode.KEYFRAME),
                attachments = listOf(frame),
            ),
            mapOf(frame.id to frame.remoteUrl!!),
        )

        assertEquals("keyframe", payload.getValue("mode").jsonPrimitive.content)
        assertEquals(frame.remoteUrl, payload.getValue("first_frame").jsonPrimitive.content)
    }

    @Test
    fun `reference mode sends resolved images array`() {
        val image = MediaAttachment(
            id = "image-1",
            displayName = "character.png",
            mimeType = "image/png",
            remoteUrl = "https://example.com/character.png",
            role = AttachmentRole.REFERENCE_IMAGE,
        )
        val payload = builder.video(
            VideoTaskSpec(
                prompt = "Use <Picture 1> as character reference",
                parameters = VideoParameters(mode = VideoMode.REFERENCE),
                attachments = listOf(image),
            ),
            mapOf(image.id to image.remoteUrl!!),
        )

        assertEquals("reference", payload.getValue("mode").jsonPrimitive.content)
        assertEquals(image.remoteUrl, payload.getValue("images").jsonArray.single().jsonPrimitive.content)
    }
}
