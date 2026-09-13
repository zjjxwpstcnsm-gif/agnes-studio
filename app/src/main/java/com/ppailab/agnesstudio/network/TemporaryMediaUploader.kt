package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.ApiFailure
import com.ppailab.agnesstudio.model.ErrorKind
import com.ppailab.agnesstudio.model.MediaAttachment
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response

internal enum class RelayAttemptState { STARTED, FAILED, SUCCEEDED }

internal data class RelayAttemptEvent(
    val providerName: String,
    val state: RelayAttemptState,
    val details: String? = null,
    val willTryNext: Boolean = false,
)

internal data class TemporaryUploadResult(
    val url: String,
    val providerName: String,
    val expiresAtMillis: Long?,
)

internal object RelayUrlCachePolicy {
    private const val EXPIRY_SAFETY_MILLIS = 60_000L

    fun reusableUrl(
        attachment: MediaAttachment,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        val url = attachment.remoteUrl ?: return null
        val expiresAt = attachment.remoteUrlExpiresAt
        if (expiresAt == null) {
            val host = url.toHttpUrlOrNull()?.host.orEmpty()
            val temporaryHost = host == "uguu.se" || host.endsWith(".uguu.se") ||
                host == "litter.catbox.moe" || host == "litterbox.catbox.moe"
            // Legacy local uploads without an expiry cannot be considered
            // permanent. Keep user-supplied URLs without known expiry intact.
            return url.takeUnless { temporaryHost && attachment.localPath != null }
        }
        return url.takeIf { expiresAt > nowMillis + EXPIRY_SAFETY_MILLIS }
    }
}

class TemporaryMediaUploader(client: OkHttpClient) {
    private val uploadClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    private val providers: List<TemporaryUploadProvider> = listOf(
        UguuUploadProvider(),
        LitterboxUploadProvider(),
    )

    internal suspend fun upload(
        attachment: MediaAttachment,
        onUploaded: (TemporaryUploadResult) -> Unit = {},
        onAttempt: (RelayAttemptEvent) -> Unit = {},
    ): TemporaryUploadResult {
        currentCoroutineContext().ensureActive()
        RelayUrlCachePolicy.reusableUrl(attachment)?.let {
            return TemporaryUploadResult(
                validateHttpsUrl(it),
                "现有公开 URL",
                attachment.remoteUrlExpiresAt,
            )
        }
        val file = File(requireNotNull(attachment.localPath))
        require(file.exists() && file.isFile) { "本地素材不存在：${attachment.displayName}" }

        val failures = mutableListOf<Pair<String, Throwable>>()
        providers.forEachIndexed { index, provider ->
            currentCoroutineContext().ensureActive()
            notifySafely(onAttempt, RelayAttemptEvent(provider.displayName, RelayAttemptState.STARTED))
            try {
                return uploadClient.newCall(provider.request(file, attachment)).executeCancellable { response ->
                    val url = validateHttpsUrl(provider.readUrl(response))
                    val result = TemporaryUploadResult(
                        url = url,
                        providerName = provider.displayName,
                        expiresAtMillis = System.currentTimeMillis() + provider.retentionMillis,
                    )
                    // The DB write must finish inside this callback, before a
                    // cancelled caller can lose the return value.
                    try {
                        onUploaded(result)
                    } catch (error: Throwable) {
                        throw UploadCheckpointException(error)
                    }
                    notifySafely(
                        onAttempt,
                        RelayAttemptEvent(provider.displayName, RelayAttemptState.SUCCEEDED, url),
                    )
                    result
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                if (error is UploadCheckpointException) throw requireNotNull(error.cause)
                failures += provider.displayName to error
                notifySafely(
                    onAttempt,
                    RelayAttemptEvent(
                        providerName = provider.displayName,
                        state = RelayAttemptState.FAILED,
                        details = technicalMessage(error),
                        willTryNext = index < providers.lastIndex,
                    ),
                )
            }
        }

        val apiFailures = failures.mapNotNull { (_, error) -> (error as? ApiException)?.failure }
        val allValidation = apiFailures.size == failures.size &&
            apiFailures.isNotEmpty() && apiFailures.all { it.kind == ErrorKind.VALIDATION }
        throw ApiException(
            ApiFailure(
                kind = if (allValidation) ErrorKind.VALIDATION else ErrorKind.NETWORK,
                userMessage = if (allValidation) {
                    "素材不符合临时中转服务的文件限制，请改用较小文件或公开 HTTPS URL。"
                } else {
                    "公开素材中转池当前全部不可用，将按任务队列策略重试；也可改用现成 HTTPS URL。"
                },
                technicalMessage = failures.joinToString("\n") { (name, error) ->
                    "$name: ${technicalMessage(error)}"
                },
                statusCode = apiFailures.firstNotNullOfOrNull { it.statusCode },
                retryable = !allValidation && failures.any { (_, error) ->
                    error !is ApiException || error.failure.retryable
                },
            ),
        )
    }

    private fun notifySafely(callback: (RelayAttemptEvent) -> Unit, event: RelayAttemptEvent) {
        runCatching { callback(event) }
    }

    private fun technicalMessage(error: Throwable): String = when (error) {
        is ApiException -> error.failure.technicalMessage ?: error.failure.userMessage
        else -> error.message ?: error::class.java.simpleName
    }
}

private interface TemporaryUploadProvider {
    val displayName: String
    val retentionMillis: Long
    fun request(file: File, attachment: MediaAttachment): Request
    fun readUrl(response: Response): String
}

private class UploadCheckpointException(cause: Throwable) : RuntimeException(cause)

private class LitterboxUploadProvider : TemporaryUploadProvider {
    override val displayName = "Litterbox（1 小时）"
    override val retentionMillis = 60 * 60_000L

    override fun request(file: File, attachment: MediaAttachment): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", "1h")
            .addFormDataPart(
                "fileToUpload",
                attachment.displayName,
                file.asRequestBody(attachment.mimeType.toMediaTypeOrNull()),
            )
            .build()
        return Request.Builder()
            .url("https://litterbox.catbox.moe/resources/internals/api.php")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .build()
    }

    override fun readUrl(response: Response): String {
        val responseBody = response.body?.string().orEmpty().trim()
        if (!response.isSuccessful) throw providerFailure(displayName, response, responseBody)
        return responseBody
    }
}

private class UguuUploadProvider : TemporaryUploadProvider {
    override val displayName = "Uguu（3 小时）"
    override val retentionMillis = 3 * 60 * 60_000L

    override fun request(file: File, attachment: MediaAttachment): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "files[]",
                attachment.displayName,
                file.asRequestBody(attachment.mimeType.toMediaTypeOrNull()),
            )
            .build()
        return Request.Builder()
            .url("https://uguu.se/upload")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .build()
    }

    override fun readUrl(response: Response): String {
        val responseBody = response.body?.string().orEmpty().trim()
        if (!response.isSuccessful) throw providerFailure(displayName, response, responseBody)
        return parseUguuUrl(responseBody) ?: throw providerFailure(
            displayName,
            response,
            responseBody.ifBlank { "Uguu returned an empty response" },
        )
    }
}

internal fun parseUguuUrl(responseBody: String): String? = runCatching {
    val root = Json.parseToJsonElement(responseBody).jsonObject
    if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching null
    root["files"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("url")?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun validateHttpsUrl(value: String): String {
    val parsed = value.trim().toHttpUrlOrNull()
    if (parsed?.isHttps != true) throw IOException("中转服务未返回有效的 HTTPS 直链")
    return parsed.toString()
}

private fun providerFailure(provider: String, response: Response, responseBody: String): ApiException {
    val status = response.code
    val validation = status == 413 || status == 415
    return ApiException(
        ApiFailure(
            kind = if (validation) ErrorKind.VALIDATION else ErrorKind.NETWORK,
            userMessage = "$provider 上传失败（HTTP $status）",
            technicalMessage = responseBody.take(MAX_ERROR_BODY).ifBlank { response.message },
            statusCode = status,
            retryable = status in 200..299 || status == 408 || status == 429 || status >= 500,
            retryAfterMillis = if (status == 429) 60_000L else null,
        ),
    )
}

private const val USER_AGENT = "AgnesStudio/1.0.8"
private const val MAX_ERROR_BODY = 2_000
