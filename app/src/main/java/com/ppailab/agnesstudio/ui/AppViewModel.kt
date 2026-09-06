package com.ppailab.agnesstudio.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ppailab.agnesstudio.AppGraph
import com.ppailab.agnesstudio.model.AppSettings
import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.ChatConversation
import com.ppailab.agnesstudio.model.ChatMessage
import com.ppailab.agnesstudio.model.ChatParameters
import com.ppailab.agnesstudio.model.ChatStreamState
import com.ppailab.agnesstudio.model.ErrorKind
import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.ImageParameters
import com.ppailab.agnesstudio.model.ImageTaskSpec
import com.ppailab.agnesstudio.model.JobLog
import com.ppailab.agnesstudio.model.JobLogLevel
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.model.MessageState
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.StreamEvent
import com.ppailab.agnesstudio.model.ToolCall
import com.ppailab.agnesstudio.model.VideoParameters
import com.ppailab.agnesstudio.model.VideoTaskSpec
import com.ppailab.agnesstudio.network.ErrorMapper
import com.ppailab.agnesstudio.network.RatePolicy
import com.ppailab.agnesstudio.network.ThinkingStreamSplitter
import com.ppailab.agnesstudio.network.VideoMediaPolicy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class UiNotice(val message: String, val isError: Boolean = false)

enum class AttachmentTarget { CHAT, IMAGE, VIDEO }

class AppViewModel(private val graph: AppGraph) : ViewModel() {
    val appSettings = graph.settings.app
    val imageParameters = graph.settings.image
    val videoParameters = graph.settings.video

    private val initialConversation = graph.database.ensureConversation(graph.settings.chat.value)
    private val _chatParameters = MutableStateFlow(initialConversation.parameters)
    val chatParameters: StateFlow<ChatParameters> = _chatParameters.asStateFlow()
    private val _currentConversationId = MutableStateFlow(initialConversation.id)
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    val conversations: StateFlow<List<ChatConversation>> = graph.database.changes
        .map { graph.database.conversations() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.database.conversations())

    val messages: StateFlow<List<ChatMessage>> = combine(
        graph.database.changes,
        _currentConversationId,
    ) { _, id -> graph.database.messages(id) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            graph.database.messages(initialConversation.id),
        )

    val jobs: StateFlow<List<GenerationJob>> = graph.database.changes
        .map { graph.database.jobs() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.database.jobs())

    private val _selectedLogJobId = MutableStateFlow<String?>(null)
    val selectedLogJobId: StateFlow<String?> = _selectedLogJobId.asStateFlow()
    val selectedJobLogs: StateFlow<List<JobLog>> = combine(
        graph.database.changes,
        _selectedLogJobId,
    ) { _, jobId -> if (jobId == null) emptyList() else graph.database.jobLogs(jobId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _chatStream = MutableStateFlow(ChatStreamState())
    val chatStream: StateFlow<ChatStreamState> = _chatStream.asStateFlow()

    private val _chatAttachments = MutableStateFlow<List<MediaAttachment>>(emptyList())
    private val _imageAttachments = MutableStateFlow<List<MediaAttachment>>(emptyList())
    private val _videoAttachments = MutableStateFlow<List<MediaAttachment>>(emptyList())
    val chatAttachments = _chatAttachments.asStateFlow()
    val imageAttachments = _imageAttachments.asStateFlow()
    val videoAttachments = _videoAttachments.asStateFlow()

    private val _keySaved = MutableStateFlow(graph.credentials.hasKey())
    val keySaved = _keySaved.asStateFlow()
    private val _testingKey = MutableStateFlow(false)
    val testingKey = _testingKey.asStateFlow()

    private val _notices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 16)
    val notices = _notices.asSharedFlow()

    private var chatJob: Job? = null
    private val explicitlyStoppedMessages = mutableSetOf<String>()

    fun selectConversation(id: String) {
        if (id != _currentConversationId.value) {
            val conversation = graph.database.conversation(id) ?: return
            stopChat()
            _currentConversationId.value = id
            _chatParameters.value = conversation.parameters
        }
    }

    fun newConversation() {
        stopChat()
        val conversation = graph.database.createConversation(parameters = _chatParameters.value)
        _currentConversationId.value = conversation.id
        _chatParameters.value = conversation.parameters
    }

    fun deleteConversation(id: String) {
        stopChat()
        graph.database.deleteConversation(id)
        val conversation = graph.database.ensureConversation(graph.settings.chat.value)
        _currentConversationId.value = conversation.id
        _chatParameters.value = conversation.parameters
    }

    fun saveApiKey(key: String) {
        runCatching {
            graph.credentials.save(key)
            _keySaved.value = graph.credentials.hasKey()
        }.onSuccess {
            notice(if (_keySaved.value) "API Key 已加密保存在本机" else "API Key 已清除")
            if (_keySaved.value) graph.generationQueue.kick()
        }.onFailure {
            notice("无法安全保存 API Key：${it.message ?: "Android Keystore 不可用"}", true)
        }
    }

    fun testApiKey(candidate: String? = null) {
        if (_testingKey.value) return
        viewModelScope.launch {
            _testingKey.value = true
            try {
                val key = candidate?.trim()?.takeIf { it.isNotBlank() } ?: graph.credentials.load()
                require(!key.isNullOrBlank()) { "请先填写 API Key" }
                val settings = appSettings.value
                graph.api.testKey(settings.baseUrl, key, settings.requestTimeoutSeconds)
                notice("连接成功，Agnes 2.5 Flash 可用")
            } catch (error: Throwable) {
                notice(ErrorMapper.fromThrowable(error).userMessage, true)
            } finally {
                _testingKey.value = false
            }
        }
    }

    fun updateAppSettings(value: AppSettings) {
        val normalizedBase = value.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        if (normalizedBase.toHttpUrlOrNull()?.isHttps != true) {
            notice("Base URL 必须是有效的 HTTPS 地址", true)
            return
        }
        graph.settings.updateApp(value.copy(
            baseUrl = normalizedBase,
            maxQueueSize = value.maxQueueSize.coerceIn(1, 100),
            maxRetries = value.maxRetries.coerceIn(0, 12),
            requestTimeoutSeconds = value.requestTimeoutSeconds.coerceIn(30, 600),
        ))
        graph.generationQueue.kick()
    }

    fun updateChatParameters(value: ChatParameters): Boolean = runCatching {
        graph.payloadBuilder.chat(value, emptyList())
        graph.settings.updateChat(value)
        graph.database.updateConversationParameters(_currentConversationId.value, value)
        _chatParameters.value = value
    }.fold(
        onSuccess = { true },
        onFailure = {
            notice(it.message ?: "文本参数不合法", true)
            false
        },
    )
    fun updateImageParameters(value: ImageParameters) = graph.settings.updateImage(value)
    fun updateVideoParameters(value: VideoParameters) = graph.settings.updateVideo(value)

    fun importAttachment(uri: Uri, role: AttachmentRole, target: AttachmentTarget) {
        if (target == AttachmentTarget.VIDEO && !appSettings.value.temporaryUploadEnabled) {
            notice(
                "本地视频素材需要先转换成 Agnes 可访问的公开 URL。请使用“素材 URL”，或在设置中启用公开素材中转池。",
                true,
            )
            return
        }
        viewModelScope.launch {
            runCatching { graph.fileStore.import(uri, role) }
                .onSuccess { addAttachment(it, target) }
                .onFailure { notice(it.message ?: "无法读取所选素材", true) }
        }
    }

    fun addRemoteAttachment(url: String, role: AttachmentRole, target: AttachmentTarget) {
        val clean = url.trim()
        if (clean.toHttpUrlOrNull()?.isHttps != true) {
            notice("请输入有效的公开 HTTPS 素材 URL", true)
            return
        }
        addAttachment(
            MediaAttachment(
                id = UUID.randomUUID().toString(),
                displayName = clean.substringAfterLast('/').substringBefore('?').ifBlank { "远程素材" },
                mimeType = when (role) {
                    AttachmentRole.REFERENCE_AUDIO -> "audio/*"
                    AttachmentRole.REFERENCE_VIDEO -> "video/*"
                    else -> "image/*"
                },
                remoteUrl = clean,
                role = role,
            ),
            target,
        )
    }

    fun removeAttachment(id: String, target: AttachmentTarget) {
        when (target) {
            AttachmentTarget.CHAT -> _chatAttachments.value = _chatAttachments.value.filterNot { it.id == id }
            AttachmentTarget.IMAGE -> _imageAttachments.value = _imageAttachments.value.filterNot { it.id == id }
            AttachmentTarget.VIDEO -> _videoAttachments.value = _videoAttachments.value.filterNot { it.id == id }
        }
    }

    fun updateVideoAttachment(id: String, startSeconds: Double? = null, requireAudio: Boolean? = null) {
        _videoAttachments.value = _videoAttachments.value.map { attachment ->
            if (attachment.id != id) attachment else attachment.copy(
                startSeconds = startSeconds?.coerceAtLeast(0.0) ?: attachment.startSeconds,
                requireAudio = requireAudio ?: attachment.requireAudio,
            )
        }
    }

    private fun addAttachment(item: MediaAttachment, target: AttachmentTarget) {
        when (target) {
            AttachmentTarget.CHAT -> _chatAttachments.value += item
            AttachmentTarget.IMAGE -> _imageAttachments.value += item
            AttachmentTarget.VIDEO -> {
                val current = _videoAttachments.value
                val replaceRoles = setOf(AttachmentRole.FIRST_FRAME, AttachmentRole.LAST_FRAME)
                _videoAttachments.value = if (item.role in replaceRoles) {
                    current.filterNot { it.role == item.role } + item
                } else current + item
            }
        }
    }

    fun sendChat(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank() && _chatAttachments.value.isEmpty()) {
            notice("请输入消息或添加图片", true)
            return false
        }
        if (!graph.credentials.hasKey()) {
            notice("请先在设置中填写 API Key", true)
            return false
        }
        if (chatJob?.isActive == true) return false
        val conversationId = _currentConversationId.value
        val existing = graph.database.messages(conversationId)
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "user",
            content = clean,
            reasoning = "",
            toolCalls = emptyList(),
            toolCallId = null,
            attachments = _chatAttachments.value,
            state = MessageState.COMPLETE,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
        )
        graph.database.insertMessage(userMessage)
        _chatAttachments.value = emptyList()
        if (existing.none { it.role == "user" }) {
            graph.database.renameConversation(conversationId, clean.ifBlank { "图片对话" }.take(30))
        }
        launchAssistant(conversationId)
        return true
    }

    fun submitToolResult(call: ToolCall, result: String) {
        val clean = result.trim()
        if (clean.isEmpty()) {
            notice("工具结果不能为空", true)
            return
        }
        val conversationId = _currentConversationId.value
        graph.database.insertMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "tool",
                content = clean,
                reasoning = "",
                toolCalls = emptyList(),
                toolCallId = call.id,
                attachments = emptyList(),
                state = MessageState.COMPLETE,
                errorMessage = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val allMessages = graph.database.messages(conversationId)
        val requestingMessage = allMessages.lastOrNull { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        val requestedIds = requestingMessage?.toolCalls?.map { it.id }?.toSet().orEmpty()
        val suppliedIds = allMessages.filter { it.createdAt > (requestingMessage?.createdAt ?: 0L) && it.role == "tool" }
            .mapNotNull { it.toolCallId }.toSet()
        if (requestedIds.all { it in suppliedIds }) launchAssistant(conversationId)
        else notice("工具结果已记录，仍有 ${requestedIds.count { it !in suppliedIds }} 项待填写")
    }

    fun stopChat() {
        _chatStream.value.activeMessageId?.let(explicitlyStoppedMessages::add)
        chatJob?.cancel()
        chatJob = null
        _chatStream.value = ChatStreamState()
    }

    private fun launchAssistant(conversationId: String) {
        chatJob = viewModelScope.launch {
            val assistantId = UUID.randomUUID().toString()
            graph.database.insertMessage(
                ChatMessage(
                    id = assistantId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    reasoning = "",
                    toolCalls = emptyList(),
                    toolCallId = null,
                    attachments = emptyList(),
                    state = MessageState.STREAMING,
                    errorMessage = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _chatStream.value = ChatStreamState(activeMessageId = assistantId)
            streamAssistant(conversationId, assistantId)
        }
    }

    private suspend fun streamAssistant(conversationId: String, assistantId: String) {
        var answer = ""
        var reasoning = ""
        val calls = mutableMapOf<Int, ToolCall>()
        var firstToken = false
        var finishReason: String? = null
        var attempts = 0
        val splitter = ThinkingStreamSplitter()
        try {
            while (true) {
                attempts++
                waitForTextRateSlot(assistantId)
                val rawHistory = graph.database.messages(conversationId).filterNot { it.id == assistantId }
                val history = withContext(Dispatchers.IO) {
                    rawHistory.map { message ->
                        message.copy(attachments = message.attachments.map { attachment ->
                            if (attachment.remoteUrl != null) attachment
                            else attachment.copy(remoteUrl = graph.fileStore.asDataUri(attachment), localPath = null)
                        })
                    }
                }
                val parameters = chatParameters.value
                val payload = graph.payloadBuilder.chat(parameters, history)
                val key = graph.credentials.load().orEmpty()
                try {
                    graph.api.chat(
                        appSettings.value.baseUrl,
                        key,
                        payload,
                        appSettings.value.requestTimeoutSeconds,
                    ) { event ->
                        when (event) {
                            is StreamEvent.TextDelta -> {
                                val split = splitter.feed(event.text)
                                answer += split.answer
                                reasoning += split.reasoning
                                firstToken = firstToken || split.answer.isNotEmpty() || split.reasoning.isNotEmpty()
                            }
                            is StreamEvent.ReasoningDelta -> {
                                reasoning += event.text
                                firstToken = true
                            }
                            is StreamEvent.ToolDelta -> {
                                val old = calls[event.call.index]
                                calls[event.call.index] = if (old == null) event.call else old.copy(
                                    id = old.id + event.call.id,
                                    type = event.call.type.ifBlank { old.type },
                                    name = old.name + event.call.name,
                                    arguments = old.arguments + event.call.arguments,
                                )
                                firstToken = true
                            }
                            is StreamEvent.Finished -> finishReason = event.reason
                            is StreamEvent.Usage -> Unit
                        }
                        _chatStream.value = ChatStreamState(assistantId, firstTokenReceived = firstToken)
                        graph.database.updateAssistantMessage(
                            assistantId, answer, reasoning, calls.values.sortedBy { it.index }, MessageState.STREAMING,
                        )
                    }
                    break
                } catch (error: Throwable) {
                    val failure = ErrorMapper.fromThrowable(error)
                    if (!firstToken && failure.retryable && attempts < 3) {
                        val wait = failure.retryAfterMillis ?: (attempts * 2_000L)
                        _chatStream.value = ChatStreamState(
                            activeMessageId = assistantId,
                            waitingForRateLimitUntil = System.currentTimeMillis() + wait,
                        )
                        delay(wait)
                        continue
                    }
                    throw error
                }
            }
            val tail = splitter.finish()
            answer += tail.answer
            reasoning += tail.reasoning
            val finalCalls = calls.values.sortedBy { it.index }
            val state = if (finalCalls.isNotEmpty() || finishReason == "tool_calls") {
                MessageState.WAITING_TOOL
            } else MessageState.COMPLETE
            graph.database.updateAssistantMessage(assistantId, answer, reasoning, finalCalls, state)
        } catch (cancelled: CancellationException) {
            val tail = splitter.finish()
            if (assistantId !in explicitlyStoppedMessages) throw cancelled
            graph.database.updateAssistantMessage(
                assistantId,
                answer + tail.answer,
                reasoning + tail.reasoning,
                calls.values.sortedBy { it.index },
                MessageState.INTERRUPTED,
                "已由用户停止，已保留当前内容。",
            )
        } catch (error: Throwable) {
            val failure = ErrorMapper.fromThrowable(error)
            graph.database.updateAssistantMessage(
                assistantId,
                answer,
                reasoning,
                calls.values.sortedBy { it.index },
                if (firstToken) MessageState.INTERRUPTED else MessageState.ERROR,
                failure.userMessage,
            )
            notice(failure.userMessage, true)
        } finally {
            explicitlyStoppedMessages.remove(assistantId)
            _chatStream.value = ChatStreamState()
            chatJob = null
        }
    }

    private suspend fun waitForTextRateSlot(assistantId: String) {
        while (true) {
            val settings = appSettings.value
            val reservation = graph.database.reserveRateSlot(
                RatePolicy.TEXT,
                RatePolicy.rpm(settings, RatePolicy.TEXT),
            )
            if (reservation.granted) return
            _chatStream.value = ChatStreamState(
                activeMessageId = assistantId,
                waitingForRateLimitUntil = reservation.retryAt,
            )
            delay((reservation.retryAt - System.currentTimeMillis()).coerceAtLeast(250L))
        }
    }

    fun enqueueImage(prompt: String) {
        if (!graph.credentials.hasKey()) {
            notice("请先在设置中填写 API Key", true)
            return
        }
        viewModelScope.launch {
            val spec = ImageTaskSpec(prompt.trim(), imageParameters.value, _imageAttachments.value)
            runCatching {
                graph.payloadBuilder.image(spec, spec.references.map { "data:image/png;base64,preview" })
                graph.database.enqueueJob(
                    Modality.IMAGE,
                    spec.prompt,
                    graph.json.encodeToString(spec),
                    appSettings.value.maxQueueSize,
                )
            }.onSuccess { receipt ->
                if (receipt.accepted) {
                    receipt.jobId?.let { jobId ->
                        graph.database.addJobLog(
                            jobId,
                            JobLogLevel.INFO,
                            "入队",
                            "图片任务已进入本地持久队列 · 第 ${receipt.position} 位",
                        )
                    }
                    _imageAttachments.value = emptyList()
                    graph.generationQueue.kick()
                    notice("图片任务已进入队列 · 当前第 ${receipt.position} 位")
                } else notice(receipt.error ?: "图片任务未入队", true)
            }.onFailure { notice(it.message ?: "图片参数不合法", true) }
        }
    }

    fun enqueueVideo(prompt: String) {
        if (!graph.credentials.hasKey()) {
            notice("请先在设置中填写 API Key", true)
            return
        }
        viewModelScope.launch {
            val spec = VideoTaskSpec(prompt.trim(), videoParameters.value, _videoAttachments.value)
            runCatching {
                VideoMediaPolicy.validateForSubmission(
                    spec.attachments,
                    appSettings.value.temporaryUploadEnabled,
                )
                graph.payloadBuilder.validateVideo(spec)
                graph.database.enqueueJob(
                    Modality.VIDEO,
                    spec.prompt,
                    graph.json.encodeToString(spec),
                    appSettings.value.maxQueueSize,
                )
            }.onSuccess { receipt ->
                if (receipt.accepted) {
                    receipt.jobId?.let { jobId ->
                        graph.database.addJobLog(
                            jobId,
                            JobLogLevel.INFO,
                            "入队",
                            "视频任务已进入本地持久队列 · 第 ${receipt.position} 位",
                        )
                    }
                    _videoAttachments.value = emptyList()
                    graph.generationQueue.kick()
                    notice("视频任务已进入持久队列 · 当前第 ${receipt.position} 位")
                } else notice(receipt.error ?: "视频任务未入队", true)
            }.onFailure { notice(it.message ?: "视频参数不合法", true) }
        }
    }

    fun cancelJob(id: String) {
        graph.database.cancelJob(id)
        graph.queueProcessor.cancel(id)
        graph.database.addJobLog(id, JobLogLevel.WARNING, "用户操作", "任务已由用户取消")
        notice("任务已取消")
    }

    fun retryJob(id: String) {
        viewModelScope.launch {
            graph.queueProcessor.awaitStopped(id)
            graph.database.retryJob(id)
            graph.database.addJobLog(id, JobLogLevel.INFO, "用户操作", "任务已手动重新排队")
            graph.generationQueue.kick()
            notice("任务已重新排队")
        }
    }

    fun openJobLogs(id: String) {
        _selectedLogJobId.value = id
    }

    fun closeJobLogs() {
        _selectedLogJobId.value = null
    }

    fun clearFinishedJobs() {
        graph.database.clearFinishedJobs()
        notice("已清理完成、失败和取消的任务记录")
    }

    private fun notice(message: String, isError: Boolean = false) {
        _notices.tryEmit(UiNotice(message, isError))
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}
