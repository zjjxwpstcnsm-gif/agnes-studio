package com.ppailab.agnesstudio.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class GenerationSettingsSnapshot(
    val typeLabel: String,
    val prompt: String,
    val fields: List<Pair<String, String>>,
    val attachments: List<String>,
    val rawJson: String,
)

object GenerationSettingsFormatter {
    private val decoder = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true }

    fun snapshot(job: GenerationJob): GenerationSettingsSnapshot = when (job.modality) {
        Modality.IMAGE -> imageSnapshot(job)
        Modality.VIDEO -> videoSnapshot(job)
    }

    private fun imageSnapshot(job: GenerationJob): GenerationSettingsSnapshot {
        val spec = runCatching { decoder.decodeFromString<ImageTaskSpec>(job.specJson) }.getOrNull()
            ?: return fallback(job, "图片")
        return GenerationSettingsSnapshot(
            typeLabel = "图片",
            prompt = spec.prompt,
            fields = listOf(
                "模型" to spec.parameters.model,
                "分辨率" to spec.parameters.size,
                "画幅比例" to spec.parameters.ratio,
                "响应格式" to spec.parameters.responseFormat.name,
                "高级参数 JSON" to spec.parameters.extraJson,
            ),
            attachments = spec.references.map(::attachmentLine),
            rawJson = prettyJson(job.specJson),
        )
    }

    private fun videoSnapshot(job: GenerationJob): GenerationSettingsSnapshot {
        val spec = runCatching { decoder.decodeFromString<VideoTaskSpec>(job.specJson) }.getOrNull()
            ?: return fallback(job, "视频")
        val mode = when (spec.parameters.mode) {
            VideoMode.TEXT -> "文生视频（text）"
            VideoMode.KEYFRAME -> "首尾帧（keyframe）"
            VideoMode.REFERENCE -> "参考生成（reference）"
        }
        return GenerationSettingsSnapshot(
            typeLabel = "视频",
            prompt = spec.prompt,
            fields = listOf(
                "模型" to spec.parameters.model,
                "生成模式" to mode,
                "时长" to "${spec.parameters.seconds} 秒",
                "分辨率" to spec.parameters.size,
                "画幅比例" to spec.parameters.aspectRatio,
                "Seed" to (spec.parameters.seed?.toString() ?: "未设置"),
                "高级参数 JSON" to spec.parameters.extraJson,
            ),
            attachments = spec.attachments.map(::attachmentLine),
            rawJson = prettyJson(job.specJson),
        )
    }

    private fun fallback(job: GenerationJob, type: String) = GenerationSettingsSnapshot(
        typeLabel = type,
        prompt = job.prompt,
        fields = listOf("解析状态" to "旧记录格式无法结构化解析，可查看下方原始 JSON"),
        attachments = emptyList(),
        rawJson = prettyJson(job.specJson),
    )

    private fun attachmentLine(attachment: MediaAttachment): String {
        val source = attachment.remoteUrl ?: attachment.localPath ?: "来源不可用"
        val videoOptions = if (attachment.role == AttachmentRole.REFERENCE_VIDEO) {
            " · start_seconds=${attachment.startSeconds} · require_audio=${attachment.requireAudio}"
        } else ""
        return "${attachment.role.name} · ${attachment.displayName} · $source$videoOptions"
    }

    private fun prettyJson(raw: String): String = runCatching {
        pretty.encodeToString(JsonElement.serializer(), decoder.parseToJsonElement(raw))
    }.getOrDefault(raw)
}
