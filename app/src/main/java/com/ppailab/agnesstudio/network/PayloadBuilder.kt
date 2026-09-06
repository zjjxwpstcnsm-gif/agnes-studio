package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.ChatMessage
import com.ppailab.agnesstudio.model.ChatParameters
import com.ppailab.agnesstudio.model.ImageResponseFormat
import com.ppailab.agnesstudio.model.ImageTaskSpec
import com.ppailab.agnesstudio.model.ToolCall
import com.ppailab.agnesstudio.model.VideoMode
import com.ppailab.agnesstudio.model.VideoTaskSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PayloadBuilder(private val json: Json) {
    fun chat(parameters: ChatParameters, messages: List<ChatMessage>): JsonObject {
        require(parameters.model.isNotBlank()) { "文本模型不能为空" }
        require(parameters.temperature in 0.0..2.0) { "temperature 必须在 0–2 之间" }
        require(parameters.topP in 0.0..1.0) { "top_p 必须在 0–1 之间" }
        require(parameters.maxTokens in 1..65_536) { "max_tokens 必须在 1–65536 之间" }

        val extra = parseObject(parameters.extraJson, "文本高级 JSON")
        val tools = parseArray(parameters.toolsJson, "工具定义")
        return buildJsonObject {
            extra.forEach { (key, value) -> put(key, value) }
            put("model", parameters.model)
            put("messages", buildJsonArray {
                if (parameters.systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", parameters.systemPrompt)
                    })
                }
                messages.forEach { add(chatMessage(it)) }
            })
            put("temperature", parameters.temperature)
            put("top_p", parameters.topP)
            put("max_tokens", parameters.maxTokens)
            put("stream", parameters.stream)
            if (parameters.enableThinking) {
                put("chat_template_kwargs", mergeObjects(
                    extra["chat_template_kwargs"] as? JsonObject,
                    buildJsonObject { put("enable_thinking", true) },
                ))
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", toolChoice(parameters.toolChoice))
            }
        }
    }

    fun image(spec: ImageTaskSpec, resolvedReferences: List<String>): JsonObject {
        require(spec.prompt.isNotBlank()) { "图片提示词不能为空" }
        require(spec.parameters.model.isNotBlank()) { "图片模型不能为空" }
        require(spec.parameters.size.isNotBlank()) { "图片尺寸不能为空" }
        require(spec.parameters.ratio in IMAGE_RATIOS) { "不支持的图片比例：${spec.parameters.ratio}" }
        val extra = parseObject(spec.parameters.extraJson, "图片高级 JSON")
        val requestedExtraBody = extra["extra_body"] as? JsonObject
        val responseFormat = when (spec.parameters.responseFormat) {
            ImageResponseFormat.URL -> "url"
            ImageResponseFormat.BASE64 -> "b64_json"
        }
        val extraBody = buildJsonObject {
            requestedExtraBody?.forEach { (key, value) -> put(key, value) }
            if (resolvedReferences.isNotEmpty()) {
                put("image", buildJsonArray { resolvedReferences.forEach { add(JsonPrimitive(it)) } })
            }
            put("response_format", responseFormat)
        }
        return buildJsonObject {
            extra.filterKeys { it != "extra_body" && it != "response_format" && it != "image" }
                .forEach { (key, value) -> put(key, value) }
            put("model", spec.parameters.model)
            put("prompt", spec.prompt)
            put("size", spec.parameters.size)
            put("ratio", spec.parameters.ratio)
            if (spec.parameters.responseFormat == ImageResponseFormat.BASE64 && resolvedReferences.isEmpty()) {
                put("return_base64", true)
            }
            put("extra_body", extraBody)
        }
    }

    fun video(spec: VideoTaskSpec, resolvedMedia: Map<String, String>): JsonObject {
        validateVideo(spec, resolvedMedia)
        val extra = parseObject(spec.parameters.extraJson, "视频高级 JSON")
        val attachments = spec.attachments.map { attachment ->
            attachment to requireNotNull(resolvedMedia[attachment.id]) { "素材 ${attachment.displayName} 尚未就绪" }
        }
        return buildJsonObject {
            extra.forEach { (key, value) -> put(key, value) }
            put("model", spec.parameters.model)
            put("prompt", spec.prompt)
            put("mode", spec.parameters.mode.name.lowercase())
            put("seconds", spec.parameters.seconds.toString())
            put("size", spec.parameters.size)
            put("aspect_ratio", spec.parameters.aspectRatio)
            put("n", 1)
            spec.parameters.seed?.let { put("seed", it) }

            when (spec.parameters.mode) {
                VideoMode.TEXT -> Unit
                VideoMode.KEYFRAME -> {
                    attachments.firstOrNull { it.first.role == AttachmentRole.FIRST_FRAME }
                        ?.let { put("first_frame", it.second) }
                    attachments.firstOrNull { it.first.role == AttachmentRole.LAST_FRAME }
                        ?.let { put("last_frame", it.second) }
                }
                VideoMode.REFERENCE -> {
                    val images = attachments.filter { it.first.role == AttachmentRole.REFERENCE_IMAGE }
                    val audios = attachments.filter { it.first.role == AttachmentRole.REFERENCE_AUDIO }
                    val videos = attachments.filter { it.first.role == AttachmentRole.REFERENCE_VIDEO }
                    if (images.isNotEmpty()) put("images", buildJsonArray {
                        images.forEach { add(JsonPrimitive(it.second)) }
                    })
                    if (audios.isNotEmpty()) put("audios", buildJsonArray {
                        audios.forEach { add(JsonPrimitive(it.second)) }
                    })
                    if (videos.isNotEmpty()) put("videos", buildJsonArray {
                        videos.forEach { (media, url) ->
                            add(buildJsonObject {
                                put("url", url)
                                put("start_seconds", media.startSeconds)
                                put("require_audio", media.requireAudio)
                            })
                        }
                    })
                }
            }
        }
    }

    fun validateVideo(spec: VideoTaskSpec, resolvedMedia: Map<String, String>? = null) {
        val params = spec.parameters
        require(spec.prompt.isNotBlank()) { "视频提示词不能为空" }
        require(params.model.isNotBlank()) { "视频模型不能为空" }
        require(params.seconds in 4..12) { "Video 2.5 时长必须为 4–12 秒" }
        require(params.aspectRatio in VIDEO_RATIOS) { "不支持的视频比例：${params.aspectRatio}" }
        if (params.model == FLASH_VIDEO_MODEL) {
            require(params.size == "720P") { "免费 Video 2.5 Flash 仅支持 720P" }
            require(spec.attachments.count { it.role == AttachmentRole.REFERENCE_IMAGE } <= 5) {
                "免费 Video 2.5 Flash 最多支持 5 张参考图"
            }
            require(spec.attachments.count { it.role == AttachmentRole.REFERENCE_AUDIO } <= 3) {
                "免费 Video 2.5 Flash 最多支持 3 段参考音频"
            }
            require(spec.attachments.none { it.role == AttachmentRole.REFERENCE_VIDEO }) {
                "免费 Video 2.5 Flash 不支持参考视频，请切换 agnes-video-2.5"
            }
        }
        if (params.model == FULL_VIDEO_MODEL) {
            require(params.size in setOf("720P", "960P", "2K")) { "Video 2.5 仅支持 720P、960P 或 2K" }
        }
        when (params.mode) {
            VideoMode.TEXT -> require(spec.attachments.isEmpty()) { "文本模式不应包含参考素材" }
            VideoMode.KEYFRAME -> {
                require(spec.attachments.any { it.role == AttachmentRole.FIRST_FRAME || it.role == AttachmentRole.LAST_FRAME }) {
                    "关键帧模式至少需要首帧或尾帧"
                }
                require(spec.attachments.none {
                    it.role in setOf(AttachmentRole.REFERENCE_IMAGE, AttachmentRole.REFERENCE_AUDIO, AttachmentRole.REFERENCE_VIDEO)
                }) { "关键帧模式只能使用首帧和尾帧" }
            }
            VideoMode.REFERENCE -> {
                require(spec.attachments.any {
                    it.role in setOf(AttachmentRole.REFERENCE_IMAGE, AttachmentRole.REFERENCE_AUDIO, AttachmentRole.REFERENCE_VIDEO)
                }) { "参考模式至少需要一项图片、音频或视频素材" }
                require(spec.attachments.none { it.role == AttachmentRole.FIRST_FRAME || it.role == AttachmentRole.LAST_FRAME }) {
                    "参考模式不能同时使用首尾帧"
                }
            }
        }
        resolvedMedia?.values?.forEach { value ->
            require(value.toHttpUrlOrNull()?.isHttps == true) { "视频媒体地址必须是有效的公开 HTTPS URL" }
        }
    }

    private fun chatMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        if (message.attachments.isEmpty()) {
            put("content", message.content)
        } else {
            put("content", buildJsonArray {
                if (message.content.isNotBlank()) add(buildJsonObject {
                    put("type", "text")
                    put("text", message.content)
                })
                message.attachments.forEach { media ->
                    val url = media.remoteUrl ?: media.localPath.orEmpty()
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", url) })
                    })
                }
            })
        }
        if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
            message.toolCalls.forEach { add(toolCall(it)) }
        })
        message.toolCallId?.let { put("tool_call_id", it) }
    }

    private fun toolCall(call: ToolCall): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", call.type)
        put("function", buildJsonObject {
            put("name", call.name)
            put("arguments", call.arguments)
        })
    }

    private fun toolChoice(value: String): JsonElement = when {
        value.startsWith("function:") -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", value.substringAfter(':').trim()) })
        }
        else -> JsonPrimitive(value.ifBlank { "auto" })
    }

    private fun parseObject(value: String, label: String): JsonObject {
        if (value.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(value).jsonObject }
            .getOrElse { throw IllegalArgumentException("$label 必须是合法的 JSON 对象：${it.message}") }
    }

    private fun parseArray(value: String, label: String): JsonArray {
        if (value.isBlank()) return JsonArray(emptyList())
        return runCatching { json.parseToJsonElement(value).jsonArray }
            .getOrElse { throw IllegalArgumentException("$label 必须是合法的 JSON 数组：${it.message}") }
    }

    private fun mergeObjects(first: JsonObject?, second: JsonObject) = JsonObject(
        (first?.toMutableMap() ?: mutableMapOf()).apply { putAll(second) },
    )

    companion object {
        const val FLASH_VIDEO_MODEL = "agnes-video-2.5-flash"
        const val FULL_VIDEO_MODEL = "agnes-video-2.5"
        val IMAGE_RATIOS = setOf("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9")
        val VIDEO_RATIOS = setOf("21:9", "16:9", "4:3", "1:1", "3:4", "9:16")
    }
}
