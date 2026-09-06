package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.ApiFailure
import com.ppailab.agnesstudio.model.StreamEvent
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class ImageApiResult(val url: String? = null, val base64: String? = null)
data class VideoCreateResult(val videoId: String, val status: String, val progress: Int)
data class VideoPollResult(
    val status: String,
    val progress: Int,
    val url: String? = null,
    val error: String? = null,
)

class AgnesApiClient(
    private val baseClient: OkHttpClient,
    private val json: Json,
) {
    private val sseParser = SseParser(json)

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        payload: JsonObject,
        timeoutSeconds: Int,
        onEvent: (StreamEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val streaming = payload["stream"]?.jsonPrimitive?.contentOrNull == "true"
        val request = requestBuilder(endpoint(baseUrl, "/v1/chat/completions"), apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val client = timedClient(timeoutSeconds, streaming)
        val call = client.newCall(request)
        val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                ensureSuccess(response)
                if (streaming) readSse(response, onEvent) else {
                    val body = response.body?.string().orEmpty()
                    sseParser.parseNonStream(body).forEach(onEvent)
                }
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    suspend fun testKey(baseUrl: String, apiKey: String, timeoutSeconds: Int) {
        val payload = """{"model":"agnes-2.5-flash","messages":[{"role":"user","content":"Reply only: OK"}],"max_tokens":2,"stream":false}"""
        executeJson(
            requestBuilder(endpoint(baseUrl, "/v1/chat/completions"), apiKey)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build(),
            timeoutSeconds,
        )
    }

    suspend fun generateImage(
        baseUrl: String,
        apiKey: String,
        payload: JsonObject,
        timeoutSeconds: Int,
        onTrace: (ApiTrace) -> Unit = {},
    ): ImageApiResult {
        val root = executeJson(
            requestBuilder(endpoint(baseUrl, "/v1/images/generations"), apiKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build(),
            timeoutSeconds,
            onTrace,
        )
        val first = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ApiException(ApiFailure(
                com.ppailab.agnesstudio.model.ErrorKind.UNKNOWN,
                "图片接口返回成功，但没有可展示的图片。",
                root.toString(),
            ))
        return ImageApiResult(
            url = first["url"]?.jsonPrimitive?.contentOrNull,
            base64 = first["b64_json"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun createVideo(
        baseUrl: String,
        apiKey: String,
        payload: JsonObject,
        timeoutSeconds: Int,
        onTrace: (ApiTrace) -> Unit = {},
        onCreated: (VideoCreateResult) -> Unit = {},
    ): VideoCreateResult {
        val root = executeJson(
            requestBuilder(endpoint(baseUrl, "/v1/videos"), apiKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build(),
            timeoutSeconds,
            onTrace,
            onParsed = { onCreated(parseVideoCreate(it)) },
        )
        return parseVideoCreate(root)
    }

    suspend fun pollVideo(
        baseUrl: String,
        apiKey: String,
        videoId: String,
        model: String,
        timeoutSeconds: Int,
        onTrace: (ApiTrace) -> Unit = {},
    ): VideoPollResult {
        val rootUrl = normalizedRoot(baseUrl)
        val url = "$rootUrl/agnesapi".toHttpUrl().newBuilder()
            .addQueryParameter("video_id", videoId)
            .addQueryParameter("model_name", model)
            .build()
        val root = try {
            executeJson(
                requestBuilder(url.toString(), apiKey).get().build(),
                timeoutSeconds.coerceAtMost(60),
                onTrace,
            )
        } catch (error: ApiException) {
            if (error.failure.statusCode == 404) {
                throw ApiException(error.failure.copy(
                    userMessage = "视频任务正在同步，稍后将自动继续查询。",
                    retryable = true,
                    retryAfterMillis = 3_000L,
                ))
            }
            throw error
        }
        return parseVideoPoll(root)
    }

    internal fun parseVideoCreate(root: JsonObject): VideoCreateResult {
        val task = root.taskObject()
        val videoId = task.string("video_id") ?: root.string("video_id")
            ?: task.string("id") ?: task.string("task_id") ?: root.string("id") ?: root.string("task_id")
            ?: throw ApiException(ApiFailure(
                com.ppailab.agnesstudio.model.ErrorKind.UNKNOWN,
                "视频任务已响应，但缺少 video_id。",
                root.toString(),
            ))
        return VideoCreateResult(
            videoId,
            task.string("status") ?: root.string("status") ?: "queued",
            task.int("progress") ?: root.int("progress") ?: 0,
        )
    }

    internal fun parseVideoPoll(root: JsonObject): VideoPollResult {
        val task = root.taskObject()
        return VideoPollResult(
            status = task.string("status") ?: root.string("status") ?: "queued",
            progress = task.int("progress") ?: root.int("progress") ?: 0,
            url = task.videoUrl() ?: root.videoUrl(),
            error = task.errorMessage() ?: root.errorMessage(),
        )
    }

    private suspend fun executeJson(
        request: Request,
        timeoutSeconds: Int,
        onTrace: (ApiTrace) -> Unit = {},
        onParsed: (JsonObject) -> Unit = {},
    ): JsonObject {
        coroutineContext.ensureActive()
        val call = timedClient(timeoutSeconds, streaming = false).newCall(request)
        val startedAt = System.nanoTime()
        try {
            return call.executeCancellable { response ->
                val body = response.body?.string().orEmpty()
                emitTrace(onTrace, ApiTrace(
                    method = request.method,
                    url = request.url.toString(),
                    statusCode = response.code,
                    durationMillis = elapsedMillis(startedAt),
                    responseBody = body,
                ))
                ensureSuccess(response, body)
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                    throw ApiException(ApiFailure(
                        com.ppailab.agnesstudio.model.ErrorKind.UNKNOWN,
                        "服务器返回了无法解析的响应。",
                        body.take(2_000),
                    ))
                }
                onParsed(parsed)
                parsed
            }
        } catch (error: Throwable) {
            coroutineContext.ensureActive()
            if (error !is ApiException) {
                emitTrace(onTrace, ApiTrace(
                    method = request.method,
                    url = request.url.toString(),
                    statusCode = null,
                    durationMillis = elapsedMillis(startedAt),
                    transportError = error.message ?: error::class.java.simpleName,
                ))
            }
            throw error
        }
    }

    private suspend fun readSse(response: Response, onEvent: (StreamEvent) -> Unit) {
        val source = response.body?.source() ?: throw IOException("Empty streaming response")
        val dataLines = mutableListOf<String>()
        fun flush() {
            if (dataLines.isEmpty()) return
            sseParser.parseData(dataLines.joinToString("\n")).forEach(onEvent)
            dataLines.clear()
        }
        while (!source.exhausted()) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            when {
                line.isBlank() -> flush()
                line.startsWith("data:") -> dataLines += line.substringAfter("data:").trimStart()
                line.startsWith(":") -> Unit
            }
        }
        flush()
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val body = response.body?.string().orEmpty()
        throw ApiException(ErrorMapper.fromHttp(response.code, body, response.header("Retry-After")))
    }

    private fun ensureSuccess(response: Response, body: String) {
        if (response.isSuccessful) return
        throw ApiException(ErrorMapper.fromHttp(response.code, body, response.header("Retry-After")))
    }

    private fun timedClient(timeoutSeconds: Int, streaming: Boolean): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(if (streaming) 0 else timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(if (streaming) 0 else timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    private fun requestBuilder(url: String, apiKey: String): Request.Builder {
        if (apiKey.isBlank()) throw ApiException(ApiFailure(
            com.ppailab.agnesstudio.model.ErrorKind.AUTHENTICATION,
            "请先在设置中填写 Agnes API Key。",
        ))
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .header("User-Agent", "AgnesStudio-Android/1.0")
    }

    private fun endpoint(baseUrl: String, path: String) = normalizedRoot(baseUrl) + path

    private fun normalizedRoot(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        require(trimmed.startsWith("https://")) { "Base URL 必须使用 HTTPS" }
        return trimmed
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.let {
        it.intOrNull ?: it.doubleOrNull?.toInt()
    }

    private fun JsonObject.taskObject(): JsonObject {
        for (key in listOf("data", "result", "video", "task")) {
            val candidate = this[key] as? JsonObject ?: continue
            if (candidate.keys.any { it in TASK_KEYS }) return candidate
        }
        return this
    }

    private fun JsonObject.videoUrl(): String? {
        for (key in listOf("url", "video_url", "output_url", "download_url")) {
            string(key)?.takeIf { it.startsWith("https://") }?.let { return it }
        }
        for (key in listOf("metadata", "output", "result", "data", "video")) {
            findVideoUrl(this[key], 0)?.let { return it }
        }
        return null
    }

    private fun findVideoUrl(element: JsonElement?, depth: Int): String? {
        if (element == null || depth > 4) return null
        return when (element) {
            is JsonObject -> {
                listOf("url", "video_url", "output_url", "download_url").firstNotNullOfOrNull { key ->
                    element.string(key)?.takeIf { it.startsWith("https://") }
                } ?: element.values.firstNotNullOfOrNull { findVideoUrl(it, depth + 1) }
            }
            is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { findVideoUrl(it, depth + 1) }
            else -> element.jsonPrimitive.contentOrNull?.takeIf {
                it.startsWith("https://") && (".mp4" in it.substringBefore('?') || "/video" in it)
            }
        }
    }

    private fun JsonObject.errorMessage(): String? {
        for (key in listOf("error", "detail", "message", "failure_reason")) {
            val element = this[key] ?: continue
            if (element is JsonObject) {
                element.string("message")?.let { return it }
                element.string("detail")?.let { return it }
            } else {
                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun emitTrace(callback: (ApiTrace) -> Unit, trace: ApiTrace) {
        runCatching { callback(trace) }
    }

    private fun elapsedMillis(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val TASK_KEYS = setOf("video_id", "task_id", "status", "progress", "metadata")
    }
}
