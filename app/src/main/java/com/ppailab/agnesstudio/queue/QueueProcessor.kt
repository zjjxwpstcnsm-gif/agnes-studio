package com.ppailab.agnesstudio.queue

import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.data.SettingsStore
import com.ppailab.agnesstudio.model.ApiFailure
import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.ErrorKind
import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.ImageTaskSpec
import com.ppailab.agnesstudio.model.JobLogLevel
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.VideoTaskSpec
import com.ppailab.agnesstudio.network.AgnesApiClient
import com.ppailab.agnesstudio.network.ApiException
import com.ppailab.agnesstudio.network.ApiTrace
import com.ppailab.agnesstudio.network.ErrorMapper
import com.ppailab.agnesstudio.network.LogSanitizer
import com.ppailab.agnesstudio.network.PayloadBuilder
import com.ppailab.agnesstudio.network.RatePolicy
import com.ppailab.agnesstudio.network.RelayAttemptState
import com.ppailab.agnesstudio.network.RelayUrlCachePolicy
import com.ppailab.agnesstudio.network.TemporaryMediaUploader
import com.ppailab.agnesstudio.network.VideoMediaPolicy
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class QueueProcessor(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val loadApiKey: () -> String?,
    private val api: AgnesApiClient,
    private val payloadBuilder: PayloadBuilder,
    private val fileStore: MediaFileStore,
    private val uploader: TemporaryMediaUploader,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    // WorkManager may start a replacement before the stopped worker's HTTP
    // callbacks finish. Share one gate for the whole application process.
    private val drainMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun wake() {
        wakeups.trySend(Unit)
    }

    suspend fun drain(workerId: String = "local", stopReason: () -> String = { "unknown" }) {
        drainMutex.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = nowMillis()
                val job = database.nextRunnableJob(now)
                if (job == null) {
                    val next = database.earliestNextAttempt() ?: return@withLock
                    // New submissions and cancellations wake this delay immediately.
                    // Keep the foreground service alive across rate windows and polls.
                    withTimeoutOrNull((next - now).coerceIn(1L, 30_000L)) { wakeups.receive() }
                    continue
                }
                processOwned(job, workerId, stopReason)
            }
        }
    }

    /** Recovery jobs do not wait for a service owner, rate windows or future polls. */
    suspend fun drainReady(
        workerId: String,
        foregroundRunning: () -> Boolean,
        stopReason: () -> String,
    ) {
        if (foregroundRunning() || !drainMutex.tryLock()) return
        try {
            while (!foregroundRunning()) {
                currentCoroutineContext().ensureActive()
                val job = database.nextRunnableJob(nowMillis()) ?: return
                processOwned(job, workerId, stopReason)
            }
        } finally {
            drainMutex.unlock()
        }
    }

    fun cancel(jobId: String) {
        activeJobs[jobId]?.cancel(CancellationException("User cancelled generation"))
        wake()
    }

    suspend fun awaitStopped(jobId: String) {
        activeJobs[jobId]?.cancelAndJoin()
    }

    private suspend fun processOwned(
        job: GenerationJob,
        workerId: String,
        stopReason: () -> String,
    ) = coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) { process(job, workerId, stopReason) }
        activeJobs[job.id] = operation
        try {
            if (database.job(job.id)?.status == JobStatus.CANCELLED) operation.cancel()
            operation.await()
        } catch (cancelled: CancellationException) {
            // User cancellation stops only this item. A worker/system cancellation
            // must propagate out of the drain loop, never become a business failure.
            if (!currentCoroutineContext().isActive || database.job(job.id)?.status != JobStatus.CANCELLED) {
                throw cancelled
            }
        } finally {
            // Keep the item registered until HTTP cleanup/checkpoints finish, so
            // an immediate manual retry cannot race the old response callback.
            withContext(NonCancellable) { operation.join() }
            activeJobs.remove(job.id, operation)
        }
    }

    private suspend fun process(job: GenerationJob, workerId: String, stopReason: () -> String) {
        val settings = settingsStore.app.value
        val key = loadApiKey()
        if (key.isNullOrBlank()) {
            log(job.id, JobLogLevel.ERROR, "认证", "未发送请求：设备中没有可用的 API Key")
            database.updateJob(
                job.id,
                JobStatus.FAILED,
                errorKind = ErrorKind.AUTHENTICATION,
                errorMessage = "请先在设置中填写 Agnes API Key。",
            )
            return
        }
        try {
            currentCoroutineContext().ensureActive()
            log(job.id, JobLogLevel.INFO, "后台执行", "执行器已接管任务", "worker_id=$workerId")
            if (job.status == JobStatus.QUEUED) {
                log(job.id, JobLogLevel.INFO, "调度", "任务已由后台队列取出")
            }
            when (job.modality) {
                Modality.IMAGE -> processImage(job, key)
                Modality.VIDEO -> processVideo(job, key)
            }
        } catch (cancelled: CancellationException) {
            if (database.recoverInterruptedJob(job.id)) {
                log(
                    job.id, JobLogLevel.WARNING, "后台暂停",
                    "后台执行被中断，已保留素材链接和视频任务 ID，稍后自动恢复。",
                    "worker_id=$workerId\nstop_reason=${stopReason()}\n${cancelled.message.orEmpty()}",
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            if (database.job(job.id)?.status == JobStatus.CANCELLED) return
            handleFailure(job, ErrorMapper.fromThrowable(error), settings.maxRetries)
        }
    }

    private suspend fun processImage(job: GenerationJob, apiKey: String) {
        val settings = settingsStore.app.value
        val spec = json.decodeFromString<ImageTaskSpec>(job.specJson)
        if (job.status != JobStatus.SENDING) {
            val bucket = RatePolicy.imageBucket(spec.parameters.size)
            val reservation = database.reserveRateSlot(bucket, RatePolicy.rpm(settings, bucket))
            if (!reservation.granted) {
                log(
                    job.id,
                    JobLogLevel.INFO,
                    "本地限流",
                    "图片 ${spec.parameters.size} 调用窗口已满，等待下一个可用槽位",
                    "next_attempt_at=${reservation.retryAt}",
                )
                database.updateJob(
                    job.id,
                    JobStatus.WAITING_RATE_LIMIT,
                    nextAttemptAt = reservation.retryAt,
                    errorKind = ErrorKind.RATE_LIMIT,
                    errorMessage = "本地限流保护：将在可用窗口自动发送。",
                )
                return
            }
        }
        database.updateJob(job.id, JobStatus.SENDING, attempt = job.attempt + 1, progress = 5)
        val references = spec.references.map { fileStore.asDataUri(it) }
        val payload = payloadBuilder.image(spec, references)
        log(
            job.id,
            JobLogLevel.INFO,
            "图片请求",
            "POST /v1/images/generations",
            LogSanitizer.json(payload.toString()),
        )
        val result = api.generateImage(
            settings.baseUrl,
            apiKey,
            payload,
            settings.requestTimeoutSeconds,
            traceLogger(job.id, "图片响应"),
        )
        if (database.job(job.id)?.status == JobStatus.CANCELLED) return
        val path = when {
            !result.base64.isNullOrBlank() -> fileStore.saveBase64Image(result.base64, job.id)
            !result.url.isNullOrBlank() -> downloadResult(result.url, job.id, isVideo = false)
            else -> null
        }
        if (path == null && result.url.isNullOrBlank()) {
            throw IllegalStateException("图片接口未返回 URL 或 Base64 数据")
        }
        database.updateJob(
            job.id,
            JobStatus.SUCCEEDED,
            progress = 100,
            resultUrl = result.url,
            resultPath = path,
        )
        log(
            job.id,
            JobLogLevel.INFO,
            "图片完成",
            if (path != null) "生成结果已下载到本机" else "已保留远端生成结果 URL",
        )
    }

    private suspend fun processVideo(job: GenerationJob, apiKey: String) {
        val settings = settingsStore.app.value
        val spec = json.decodeFromString<VideoTaskSpec>(job.specJson)
        if (job.remoteId == null) {
            database.updateJob(job.id, JobStatus.SENDING, progress = 2)
            val resolved = resolveVideoMedia(job.id, spec, settings.temporaryUploadEnabled)
            currentCoroutineContext().ensureActive()
            if (database.job(job.id)?.status == JobStatus.CANCELLED) return
            // Reserve immediately before POST; time spent uploading must not consume
            // or invalidate the video-creation rate window.
            run {
                val reservation = database.reserveRateSlot(
                    RatePolicy.VIDEO,
                    RatePolicy.rpm(settings, RatePolicy.VIDEO),
                )
                if (!reservation.granted) {
                    log(
                        job.id,
                        JobLogLevel.INFO,
                        "本地限流",
                        "视频创建调用窗口已满，等待下一个可用槽位",
                        "next_attempt_at=${reservation.retryAt}",
                    )
                    database.updateJob(
                        job.id,
                        JobStatus.WAITING_RATE_LIMIT,
                        nextAttemptAt = reservation.retryAt,
                        errorKind = ErrorKind.RATE_LIMIT,
                        errorMessage = "视频生成限流保护：任务正在本地排队。",
                    )
                    return
                }
            }
            database.updateJob(job.id, JobStatus.SENDING, attempt = job.attempt + 1, progress = 5)
            val payload = payloadBuilder.video(resolved.spec, resolved.urls)
            log(
                job.id,
                JobLogLevel.INFO,
                "视频创建请求",
                "POST /v1/videos · ${spec.parameters.model} · ${spec.parameters.mode.name.lowercase()}",
                LogSanitizer.json(payload.toString()),
            )
            api.createVideo(
                settings.baseUrl,
                apiKey,
                payload,
                settings.requestTimeoutSeconds,
                traceLogger(job.id, "视频创建响应"),
                onCreated = { created ->
                    // Persist video_id in the response callback, before a cancelled
                    // continuation can discard the successfully created remote task.
                    database.recordVideoCreated(job.id, created.videoId, created.progress)
                    log(
                        job.id, JobLogLevel.INFO, "视频任务已创建",
                        "video_id=${created.videoId} · status=${created.status} · progress=${created.progress}%",
                    )
                },
            )
            return
        }

        log(
            job.id,
            JobLogLevel.INFO,
            "视频轮询",
            "查询 video_id=${job.remoteId} · model_name=${spec.parameters.model}",
        )
        val polled = api.pollVideo(
            settings.baseUrl,
            apiKey,
            job.remoteId,
            spec.parameters.model,
            settings.requestTimeoutSeconds,
            traceLogger(job.id, "视频轮询响应"),
        )
        if (database.job(job.id)?.status == JobStatus.CANCELLED) return
        when (polled.status.lowercase()) {
            "completed", "succeeded", "success", "finished", "done" -> {
                val url = polled.url ?: throw ApiException(ApiFailure(
                    kind = ErrorKind.SERVER,
                    userMessage = "视频已完成但结果地址仍在同步，稍后将自动重试获取。",
                    technicalMessage = "Completed response did not contain a recognized video URL",
                    retryable = true,
                    retryAfterMillis = 3_000L,
                ))
                val path = downloadResult(url, job.id, isVideo = true)
                database.updateJob(
                    job.id,
                    JobStatus.SUCCEEDED,
                    progress = 100,
                    resultUrl = url,
                    resultPath = path,
                )
                log(
                    job.id,
                    JobLogLevel.INFO,
                    "视频完成",
                    if (path != null) "生成视频已下载到本机" else "生成完成；本地下载失败，已保留远端 URL",
                    LogSanitizer.url(url),
                )
            }
            "failed", "failure", "error", "cancelled", "canceled" -> {
                log(
                    job.id,
                    JobLogLevel.ERROR,
                    "视频失败",
                    polled.error ?: "服务端将任务标记为 ${polled.status}，但未返回具体原因",
                )
                database.updateJob(
                    job.id,
                    JobStatus.FAILED,
                    progress = polled.progress,
                    errorKind = ErrorKind.SERVER,
                    errorMessage = polled.error ?: "视频生成失败，服务端未返回具体原因。",
                )
            }
            else -> {
                database.updateJob(
                    job.id,
                    JobStatus.PROCESSING,
                    nextAttemptAt = System.currentTimeMillis() + QueueTimingPolicy.VIDEO_POLL_INTERVAL_MILLIS,
                    progress = polled.progress,
                    errorMessage = "服务端生成中 · ${polled.status}",
                )
            }
        }
    }

    private suspend fun resolveVideoMedia(
        jobId: String,
        originalSpec: VideoTaskSpec,
        temporaryUploadEnabled: Boolean,
    ): ResolvedVideoMedia {
        var persistedSpec = originalSpec
        val urls = linkedMapOf<String, String>()
        VideoMediaPolicy.validateForSubmission(persistedSpec.attachments, temporaryUploadEnabled)
        for (attachmentId in originalSpec.attachments.map { it.id }) {
            val attachmentIndex = persistedSpec.attachments.indexOfFirst { it.id == attachmentId }
            val attachment = persistedSpec.attachments[attachmentIndex]
            val cachedUrl = RelayUrlCachePolicy.reusableUrl(attachment)
            val value = when {
                cachedUrl != null -> {
                    if (attachment.localPath != null) {
                        log(
                            jobId,
                            JobLogLevel.INFO,
                            "素材中转缓存",
                            "复用已上传链接：${attachment.displayName}",
                            LogSanitizer.url(cachedUrl),
                        )
                    }
                    cachedUrl
                }
                attachment.localPath == null -> throw IllegalArgumentException("素材缺少 URL 或本地文件")
                temporaryUploadEnabled -> {
                    if (attachment.remoteUrl != null) {
                        log(
                            jobId,
                            JobLogLevel.INFO,
                            "素材中转缓存",
                            "缓存链接已过期，将重新上传：${attachment.displayName}",
                        )
                    }
                    val uploaded = uploader.upload(
                        attachment,
                        onUploaded = { result ->
                            val updatedAttachments = persistedSpec.attachments.toMutableList().apply {
                                this[attachmentIndex] = attachment.copy(
                                    remoteUrl = result.url,
                                    remoteUrlExpiresAt = result.expiresAtMillis,
                                )
                            }
                            persistedSpec = persistedSpec.copy(attachments = updatedAttachments)
                            database.updateJobSpec(jobId, json.encodeToString(persistedSpec))
                            log(
                                jobId, JobLogLevel.INFO, "素材中转缓存",
                                "链接已保存，重试和后台恢复将直接复用：${attachment.displayName}",
                                LogSanitizer.url(result.url),
                            )
                        },
                    ) { event ->
                        when (event.state) {
                            RelayAttemptState.STARTED -> log(
                                jobId,
                                JobLogLevel.INFO,
                                "素材中转",
                                "${event.providerName} · 正在上传 ${attachment.displayName}",
                            )
                            RelayAttemptState.FAILED -> log(
                                jobId,
                                JobLogLevel.WARNING,
                                "中转换源",
                                if (event.willTryNext) {
                                    "${event.providerName} 不可用，自动切换下一个服务"
                                } else {
                                    "${event.providerName} 不可用，中转池已全部尝试"
                                },
                                event.details?.let(LogSanitizer::throwable),
                            )
                            RelayAttemptState.SUCCEEDED -> log(
                                jobId,
                                JobLogLevel.INFO,
                                "素材中转",
                                "${event.providerName} · 上传完成 ${attachment.displayName}",
                                event.details?.let(LogSanitizer::url),
                            )
                        }
                    }
                    uploaded.url
                }
                else -> throw IllegalArgumentException(
                    "Agnes 视频接口只接受公开 URL；请为 ${attachment.displayName} 填写 URL，或在设置中启用公开素材中转池。",
                )
            }
            urls[attachment.id] = value
        }
        return ResolvedVideoMedia(persistedSpec, urls)
    }

    private fun handleFailure(job: GenerationJob, failure: ApiFailure, maxRetries: Int) {
        val attempts = (job.attempt + 1).coerceAtLeast(1)
        log(
            job.id,
            if (failure.retryable) JobLogLevel.WARNING else JobLogLevel.ERROR,
            "异常处理",
            failure.userMessage,
            buildString {
                failure.statusCode?.let { append("HTTP $it\n") }
                failure.technicalMessage?.let { append(LogSanitizer.json(it)) }
            }.takeIf { it.isNotBlank() },
        )
        if (failure.retryable && attempts <= maxRetries) {
            val computed = exponentialBackoff(attempts)
            val wait = maxOf(
                computed,
                failure.retryAfterMillis ?: 0L,
                QueueTimingPolicy.minimumRetryDelayMillis(job),
            )
            database.updateJob(
                job.id,
                JobStatus.RETRY_WAIT,
                nextAttemptAt = System.currentTimeMillis() + wait,
                attempt = attempts,
                errorKind = failure.kind,
                errorMessage = failure.userMessage,
                httpStatus = failure.statusCode,
            )
            val retryAt = requireNotNull(database.job(job.id)).nextAttemptAt
            log(job.id, JobLogLevel.INFO, "重试计划",
                "第 $attempts 次异常，可自动重试；计划等待 ${wait / 1_000} 秒，实际发送仍遵守限流窗口。",
                "next_attempt_at=$retryAt\nmax_retries=$maxRetries")
        } else {
            database.updateJob(
                job.id,
                JobStatus.FAILED,
                attempt = attempts,
                errorKind = failure.kind,
                errorMessage = failure.userMessage,
                httpStatus = failure.statusCode,
            )
            if (failure.retryable) log(job.id, JobLogLevel.ERROR, "重试结束",
                "已达到自动重试上限（$maxRetries），任务停止；可在队列中手动重试。")
        }
    }

    private suspend fun downloadResult(url: String, jobId: String, isVideo: Boolean): String? = try {
        fileStore.download(url, jobId, isVideo)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    private fun exponentialBackoff(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 7)
        val base = (2_000L * (1L shl exponent)).coerceAtMost(300_000L)
        return base + ThreadLocalRandom.current().nextLong(250L, 1_500L)
    }

    private fun traceLogger(jobId: String, stage: String): (ApiTrace) -> Unit = { trace ->
        val status = trace.statusCode
        val level = when {
            status == null -> JobLogLevel.WARNING
            status >= 400 -> JobLogLevel.ERROR
            else -> JobLogLevel.INFO
        }
        val message = if (status == null) {
            "${trace.method} 网络调用未获得 HTTP 响应 · ${trace.durationMillis} ms"
        } else {
            "${trace.method} HTTP $status · ${trace.durationMillis} ms"
        }
        val details = buildString {
            append(LogSanitizer.url(trace.url))
            trace.responseBody?.takeIf { it.isNotBlank() }?.let {
                append("\n\nResponse:\n")
                append(LogSanitizer.json(it))
            }
            trace.transportError?.let {
                append("\n\nTransport error:\n")
                append(LogSanitizer.throwable(it))
            }
        }
        log(jobId, level, stage, message, details)
    }

    private fun log(
        jobId: String,
        level: JobLogLevel,
        stage: String,
        message: String,
        details: String? = null,
    ) {
        database.addJobLog(jobId, level, stage, message, details)
    }

    private data class ResolvedVideoMedia(
        val spec: VideoTaskSpec,
        val urls: Map<String, String>,
    )

}
