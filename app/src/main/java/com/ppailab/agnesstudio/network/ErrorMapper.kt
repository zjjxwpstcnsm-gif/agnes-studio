package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.ApiFailure
import com.ppailab.agnesstudio.model.ErrorKind
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.coroutines.CancellationException

object ErrorMapper {
    fun fromHttp(statusCode: Int, body: String?, retryAfterHeader: String? = null): ApiFailure {
        val detail = extractDetail(body).ifBlank { "HTTP $statusCode" }
        val lower = detail.lowercase()
        val retryAfter = parseRetryAfter(retryAfterHeader)
        return when {
            statusCode == 400 && containsAny(lower, "content policy", "safety", "moderation", "nsfw", "sensitive") -> ApiFailure(
                ErrorKind.CONTENT_POLICY, "请求未通过内容安全检查，请调整提示词或参考素材。", detail, statusCode,
            )
            statusCode == 400 -> ApiFailure(
                ErrorKind.VALIDATION, humanizeValidation(detail), detail, statusCode,
            )
            statusCode == 401 || statusCode == 403 -> ApiFailure(
                ErrorKind.AUTHENTICATION, "API Key 无效、无权限或账户状态异常，请到设置中检查。", detail, statusCode,
            )
            statusCode == 404 -> ApiFailure(
                ErrorKind.NOT_FOUND, "接口、模型或远程任务不存在，请检查模型与任务 ID。", detail, statusCode,
                retryable = false,
            )
            statusCode == 408 -> ApiFailure(
                ErrorKind.TIMEOUT, "服务器处理超时，任务会按退避策略重试。", detail, statusCode,
                retryable = true, retryAfterMillis = retryAfter,
            )
            statusCode == 409 && containsAny(lower, "queue", "capacity") -> remoteQueueFull(detail, statusCode, retryAfter)
            statusCode == 422 -> ApiFailure(
                ErrorKind.VALIDATION, humanizeValidation(detail), detail, statusCode,
            )
            statusCode == 429 && containsAny(
                lower, "quota", "credit", "balance", "limit exhausted", "insufficient_quota", "insufficient balance",
            ) -> ApiFailure(
                ErrorKind.QUOTA_EXHAUSTED, "当前账户额度已用尽，请查看 Agnes 控制台中的配额或余额。", detail, statusCode,
                retryable = false,
            )
            statusCode == 429 -> ApiFailure(
                ErrorKind.RATE_LIMIT, "触发服务端限流，已排队等待后自动重试。", detail, statusCode,
                retryable = true, retryAfterMillis = retryAfter ?: 60_000L,
            )
            statusCode in 500..599 && containsAny(
                lower, "queue", "full", "busy", "capacity", "overload", "too many pending",
                "too many requests", "task limit", "concurrency", "队列", "繁忙", "满载",
            ) -> remoteQueueFull(detail, statusCode, retryAfter)
            statusCode == 503 -> ApiFailure(
                ErrorKind.SERVER, "Agnes 服务当前繁忙，任务已保留并将在稍后重试。", detail, statusCode,
                retryable = true, retryAfterMillis = retryAfter,
            )
            statusCode in 500..599 -> ApiFailure(
                ErrorKind.SERVER, "Agnes 服务暂时异常（$statusCode），任务会自动退避重试。", detail, statusCode,
                retryable = true, retryAfterMillis = retryAfter,
            )
            else -> ApiFailure(
                ErrorKind.UNKNOWN, "请求失败（HTTP $statusCode）：$detail", detail, statusCode,
                retryable = statusCode >= 500, retryAfterMillis = retryAfter,
            )
        }
    }

    fun fromThrowable(error: Throwable): ApiFailure = when (error) {
        is ApiException -> error.failure
        is CancellationException -> ApiFailure(
            ErrorKind.CANCELLED, "操作已取消。", error.message, retryable = false,
        )
        is SocketTimeoutException -> ApiFailure(
            ErrorKind.TIMEOUT, "网络等待超时，任务会自动重试。", error.message, retryable = true,
        )
        is UnknownHostException -> ApiFailure(
            ErrorKind.NETWORK, "无法连接网络，请检查网络、DNS 或代理设置。", error.message, retryable = true,
        )
        is IOException -> ApiFailure(
            ErrorKind.NETWORK, "网络连接中断，任务已保留并将在恢复后重试。", error.message, retryable = true,
        )
        is IllegalArgumentException -> ApiFailure(
            ErrorKind.VALIDATION, error.message ?: "参数或素材不符合接口要求。", error.message,
            retryable = false,
        )
        is SecurityException -> ApiFailure(
            ErrorKind.VALIDATION, "无法读取所选素材，请重新选择并授予访问权限。", error.message,
            retryable = false,
        )
        else -> ApiFailure(
            ErrorKind.UNKNOWN, error.message ?: "发生未预期错误，请检查参数后重试。", error.stackTraceToString().take(2_000),
            retryable = false,
        )
    }

    private fun remoteQueueFull(detail: String, status: Int, retryAfter: Long?) = ApiFailure(
        ErrorKind.REMOTE_QUEUE_FULL,
        "Agnes 生成队列已满，任务已留在本地队列中，将自动错峰重试。",
        detail,
        status,
        retryable = true,
        retryAfterMillis = retryAfter ?: 60_000L,
    )

    private fun humanizeValidation(detail: String): String {
        val lower = detail.lowercase()
        return when {
            "size must be 720p" in lower -> "免费 Video 2.5 Flash 仅支持 720P，请修改视频尺寸。"
            "images length must not exceed 5" in lower -> "免费 Video 2.5 Flash 最多支持 5 张参考图。"
            "audios length must not exceed 3" in lower -> "免费 Video 2.5 Flash 最多支持 3 段参考音频。"
            "videos is not supported" in lower -> "免费 Video 2.5 Flash 不支持参考视频；请切换完整 2.5 模型或移除视频。"
            "image" in lower && containsAny(lower, "unreachable", "access", "download") ->
                "参考素材无法被 Agnes 访问，请使用公开 HTTPS URL 或启用公开素材中转池。"
            else -> "参数校验失败：$detail"
        }
    }

    private fun extractDetail(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val compact = body.replace(Regex("\\s+"), " ").take(2_000)
        val known = listOf("detail", "message", "error")
        for (key in known) {
            Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(compact)?.let {
                return it.groupValues[1]
            }
        }
        return compact
    }

    private fun parseRetryAfter(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return max(1L, it) * 1_000L }
        return runCatching {
            val retryAt = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            max(1_000L, retryAt - System.currentTimeMillis())
        }.getOrNull()
    }

    private fun containsAny(value: String, vararg needles: String) = needles.any(value::contains)
}

class ApiException(val failure: ApiFailure) : Exception(failure.technicalMessage ?: failure.userMessage)
