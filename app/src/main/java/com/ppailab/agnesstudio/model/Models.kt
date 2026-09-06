package com.ppailab.agnesstudio.model

import kotlinx.serialization.Serializable

@Serializable
enum class AccessPlan { FREE, ENTERPRISE, TOKEN_PLAN, CUSTOM }

@Serializable
data class AppSettings(
    val baseUrl: String = "https://apihub.agnes-ai.com",
    val accessPlan: AccessPlan = AccessPlan.FREE,
    val maxQueueSize: Int = 20,
    val maxRetries: Int = 6,
    val requestTimeoutSeconds: Int = 360,
    val customTextRpm: Int = 20,
    val customImage1kRpm: Int = 20,
    val customImage2kRpm: Int = 10,
    val customImage3kRpm: Int = 1,
    val customImage4kRpm: Int = 1,
    val customVideoRpm: Int = 1,
    val temporaryUploadEnabled: Boolean = false,
    val darkMode: ThemeMode = ThemeMode.SYSTEM,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class ChatParameters(
    val model: String = "agnes-2.5-flash",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val enableThinking: Boolean = true,
    val toolsJson: String = "[]",
    val toolChoice: String = "auto",
    val extraJson: String = "{}",
)

@Serializable
data class ImageParameters(
    val model: String = "agnes-image-2.5-flash",
    val size: String = "2K",
    val ratio: String = "9:16",
    val responseFormat: ImageResponseFormat = ImageResponseFormat.URL,
    val extraJson: String = "{}",
)

@Serializable
enum class ImageResponseFormat { URL, BASE64 }

@Serializable
data class VideoParameters(
    val model: String = "agnes-video-2.5-flash",
    val mode: VideoMode = VideoMode.TEXT,
    val seconds: Int = 5,
    val size: String = "720P",
    val aspectRatio: String = "9:16",
    val seed: Long? = null,
    val extraJson: String = "{}",
)

@Serializable
enum class VideoMode { TEXT, KEYFRAME, REFERENCE }

@Serializable
data class MediaAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val remoteUrlExpiresAt: Long? = null,
    val role: AttachmentRole = AttachmentRole.REFERENCE_IMAGE,
    val startSeconds: Double = 0.0,
    val requireAudio: Boolean = false,
)

@Serializable
enum class AttachmentRole {
    CHAT_IMAGE,
    REFERENCE_IMAGE,
    FIRST_FRAME,
    LAST_FRAME,
    REFERENCE_AUDIO,
    REFERENCE_VIDEO,
}

@Serializable
data class ImageTaskSpec(
    val prompt: String,
    val parameters: ImageParameters,
    val references: List<MediaAttachment> = emptyList(),
)

@Serializable
data class VideoTaskSpec(
    val prompt: String,
    val parameters: VideoParameters,
    val attachments: List<MediaAttachment> = emptyList(),
)

enum class Modality { IMAGE, VIDEO }

enum class JobStatus {
    QUEUED,
    WAITING_RATE_LIMIT,
    SENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class JobLogLevel { INFO, WARNING, ERROR }

data class JobLog(
    val id: Long,
    val jobId: String,
    val createdAt: Long,
    val level: JobLogLevel,
    val stage: String,
    val message: String,
    val details: String?,
)

enum class ErrorKind {
    VALIDATION,
    AUTHENTICATION,
    RATE_LIMIT,
    LOCAL_QUEUE_FULL,
    REMOTE_QUEUE_FULL,
    QUOTA_EXHAUSTED,
    CONTENT_POLICY,
    NETWORK,
    TIMEOUT,
    NOT_FOUND,
    SERVER,
    CANCELLED,
    UNKNOWN,
}

data class GenerationJob(
    val id: String,
    val modality: Modality,
    val status: JobStatus,
    val prompt: String,
    val specJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nextAttemptAt: Long,
    val attempt: Int,
    val progress: Int,
    val remoteId: String?,
    val resultUrl: String?,
    val resultPath: String?,
    val errorKind: ErrorKind?,
    val errorMessage: String?,
    val httpStatus: Int?,
)

@Serializable
data class ToolCall(
    val index: Int,
    val id: String = "",
    val type: String = "function",
    val name: String = "",
    val arguments: String = "",
)

enum class MessageState { COMPLETE, STREAMING, WAITING_TOOL, INTERRUPTED, ERROR }

data class ChatConversation(
    val id: String,
    val title: String,
    val parameters: ChatParameters,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val toolCallId: String?,
    val attachments: List<MediaAttachment>,
    val state: MessageState,
    val errorMessage: String?,
    val createdAt: Long,
)

data class RateReservation(
    val granted: Boolean,
    val retryAt: Long = 0L,
)

data class QueueReceipt(
    val accepted: Boolean,
    val jobId: String? = null,
    val position: Int = 0,
    val error: String? = null,
)

data class ApiFailure(
    val kind: ErrorKind,
    val userMessage: String,
    val technicalMessage: String? = null,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
    val retryAfterMillis: Long? = null,
)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolDelta(val call: ToolCall) : StreamEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?) : StreamEvent
    data class Finished(val reason: String?) : StreamEvent
}

data class ChatStreamState(
    val activeMessageId: String? = null,
    val waitingForRateLimitUntil: Long? = null,
    val firstTokenReceived: Boolean = false,
)
