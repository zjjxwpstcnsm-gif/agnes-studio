package com.ppailab.agnesstudio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.ImageResponseFormat
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.VideoMode
import com.ppailab.agnesstudio.network.PayloadBuilder

@Composable
fun ImageScreen(viewModel: AppViewModel, onOpenQueue: () -> Unit) {
    val parameters by viewModel.imageParameters.collectAsStateWithLifecycle()
    val attachments by viewModel.imageAttachments.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val deletingJobIds by viewModel.deletingJobIds.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("") }
    var showUrlDialog by remember { mutableStateOf(false) }
    var settingsJobId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { viewModel.importAttachment(it, AttachmentRole.REFERENCE_IMAGE, AttachmentTarget.IMAGE) }
    }
    val imageJobs = jobs.filter { it.modality == Modality.IMAGE }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader("图片工坊", "agnes-image-2.5-flash · 文生图 / 图生图 / 多图合成") {
                TextButton(onClick = onOpenQueue) {
                    Icon(Icons.Outlined.Queue, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("队列")
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("图片提示词") },
                    placeholder = { Text("描述主体、场景、风格、光照、构图和细节…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 12,
                )
                AttachmentStrip(attachments) { viewModel.removeAttachment(it, AttachmentTarget.IMAGE) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch("image/*") }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("本地参考图")
                    }
                    OutlinedButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Outlined.AddLink, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("图片 URL")
                    }
                }
                SupportNotice("本地参考图会直接编码为 Data URI 发送给 Agnes，不经过第三方图床。")
            }
        }
        item {
            ParameterCard("生成参数") {
                OutlinedTextField(
                    value = parameters.model,
                    onValueChange = { viewModel.updateImageParameters(parameters.copy(model = it)) },
                    label = { Text("模型 ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分辨率档位", fontWeight = FontWeight.Medium)
                ChoiceChips(listOf("1K", "2K", "3K", "4K"), parameters.size, onSelected = {
                    viewModel.updateImageParameters(parameters.copy(size = it))
                })
                Text("画幅比例", fontWeight = FontWeight.Medium)
                ChoiceChips(PayloadBuilder.IMAGE_RATIOS.toList(), parameters.ratio, onSelected = {
                    viewModel.updateImageParameters(parameters.copy(ratio = it))
                })
                Text("响应格式", fontWeight = FontWeight.Medium)
                ChoiceChips(ImageResponseFormat.entries.map { it.name }, parameters.responseFormat.name, {
                    viewModel.updateImageParameters(parameters.copy(responseFormat = ImageResponseFormat.valueOf(it)))
                }) { if (it == "URL") "URL（推荐）" else "Base64" }
                ResolutionHint(parameters.size, parameters.ratio)
                OutlinedTextField(
                    value = parameters.extraJson,
                    onValueChange = { viewModel.updateImageParameters(parameters.copy(extraJson = it)) },
                    label = { Text("高级参数 JSON") },
                    supportingText = { Text("额外字段会透传；response_format 会自动放入 extra_body") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        item {
            Button(
                onClick = { viewModel.enqueueImage(prompt) },
                enabled = prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("加入图片生成队列")
            }
        }
        if (imageJobs.isNotEmpty()) {
            item { SectionLabel("生成记录", "结果完成后会直接在此预览").let {
                Column(Modifier.padding(horizontal = 16.dp)) { it }
            } }
            items(imageJobs, key = { it.id }) { job ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GenerationJobCard(
                        job = job,
                        onRetry = { viewModel.retryJob(job.id) },
                        onDuplicate = { viewModel.duplicateJob(job.id) },
                        onCancel = { viewModel.cancelJob(job.id) },
                        onDelete = { viewModel.deleteJob(job.id) },
                        isDeleting = job.id in deletingJobIds,
                        onViewSettings = { settingsJobId = job.id },
                    )
                }
            }
        }
    }

    if (showUrlDialog) RemoteUrlDialog(
        "添加参考图 URL",
        onDismiss = { showUrlDialog = false },
        onConfirm = { viewModel.addRemoteAttachment(it, AttachmentRole.REFERENCE_IMAGE, AttachmentTarget.IMAGE) },
    )
    settingsJobId?.let { id ->
        jobs.firstOrNull { it.id == id }?.let { job ->
            GenerationSettingsDialog(job) { settingsJobId = null }
        }
    }
}

@Composable
fun VideoScreen(viewModel: AppViewModel, onOpenQueue: () -> Unit) {
    val parameters by viewModel.videoParameters.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val attachments by viewModel.videoAttachments.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val deletingJobIds by viewModel.deletingJobIds.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("") }
    var remoteRole by remember { mutableStateOf<AttachmentRole?>(null) }
    var settingsJobId by remember { mutableStateOf<String?>(null) }

    val firstFramePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importAttachment(it, AttachmentRole.FIRST_FRAME, AttachmentTarget.VIDEO) }
    }
    val lastFramePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importAttachment(it, AttachmentRole.LAST_FRAME, AttachmentTarget.VIDEO) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { viewModel.importAttachment(it, AttachmentRole.REFERENCE_IMAGE, AttachmentTarget.VIDEO) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { viewModel.importAttachment(it, AttachmentRole.REFERENCE_AUDIO, AttachmentTarget.VIDEO) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { viewModel.importAttachment(it, AttachmentRole.REFERENCE_VIDEO, AttachmentTarget.VIDEO) }
    }
    val videoJobs = jobs.filter { it.modality == Modality.VIDEO }
    val isFlash = parameters.model == PayloadBuilder.FLASH_VIDEO_MODEL

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader("视频工坊", "异步生成 · 持久队列 · 自动轮询") {
                TextButton(onClick = onOpenQueue) {
                    Icon(Icons.Outlined.Queue, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("队列")
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("视频提示词") },
                    placeholder = { Text("描述主体动作、环境动态、镜头运动、节奏与声音…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 12,
                )
                AttachmentStrip(attachments) { viewModel.removeAttachment(it, AttachmentTarget.VIDEO) }
            }
        }
        item {
            ParameterCard("模型与模式") {
                Text("模型", fontWeight = FontWeight.Medium)
                ChoiceChips(
                    values = listOf(PayloadBuilder.FLASH_VIDEO_MODEL, PayloadBuilder.FULL_VIDEO_MODEL),
                    selected = parameters.model,
                    onSelected = { model ->
                        val safeSize = if (model == PayloadBuilder.FLASH_VIDEO_MODEL) "720P" else parameters.size
                        viewModel.updateVideoParameters(parameters.copy(model = model, size = safeSize))
                        if (model == PayloadBuilder.FLASH_VIDEO_MODEL) {
                            attachments.filter { it.role == AttachmentRole.REFERENCE_VIDEO }
                                .forEach { viewModel.removeAttachment(it.id, AttachmentTarget.VIDEO) }
                        }
                    },
                    label = { if (it == PayloadBuilder.FLASH_VIDEO_MODEL) "2.5 Flash（免费）" else "2.5 完整版（付费）" },
                )
                OutlinedTextField(
                    value = parameters.model,
                    onValueChange = { viewModel.updateVideoParameters(parameters.copy(model = it)) },
                    label = { Text("模型 ID（可自定义）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isFlash) {
                    SupportNotice("免费 2.5 Flash：固定 720P；支持文本、首尾帧、最多 5 图 + 3 音频；官方不支持参考视频。")
                } else if (parameters.model == PayloadBuilder.FULL_VIDEO_MODEL) {
                    SupportNotice("完整 Video 2.5 支持参考视频与 960P/2K，但属于付费模型，最终以账户权限和余额为准。", true)
                }
                Text("生成模式", fontWeight = FontWeight.Medium)
                ChoiceChips(VideoMode.entries.map { it.name }, parameters.mode.name, { value ->
                    val next = VideoMode.valueOf(value)
                    attachments.forEach { viewModel.removeAttachment(it.id, AttachmentTarget.VIDEO) }
                    viewModel.updateVideoParameters(parameters.copy(mode = next))
                }) {
                    when (it) {
                        "TEXT" -> "文生视频"
                        "KEYFRAME" -> "首尾帧"
                        else -> "参考生成"
                    }
                }
            }
        }
        item {
            ParameterCard("输出参数") {
                Text("时长 · ${parameters.seconds} 秒", fontWeight = FontWeight.Medium)
                Slider(
                    value = parameters.seconds.toFloat(),
                    onValueChange = { viewModel.updateVideoParameters(parameters.copy(seconds = it.toInt())) },
                    valueRange = 4f..12f,
                    steps = 7,
                )
                Text("尺寸", fontWeight = FontWeight.Medium)
                ChoiceChips(
                    if (isFlash) listOf("720P") else listOf("720P", "960P", "2K"),
                    parameters.size,
                    onSelected = { viewModel.updateVideoParameters(parameters.copy(size = it)) },
                )
                Text("画幅比例", fontWeight = FontWeight.Medium)
                ChoiceChips(PayloadBuilder.VIDEO_RATIOS.toList(), parameters.aspectRatio, onSelected = {
                    viewModel.updateVideoParameters(parameters.copy(aspectRatio = it))
                })
                OutlinedTextField(
                    value = parameters.seed?.toString().orEmpty(),
                    onValueChange = { raw ->
                        if (raw.isBlank()) viewModel.updateVideoParameters(parameters.copy(seed = null))
                        else raw.toLongOrNull()?.let { viewModel.updateVideoParameters(parameters.copy(seed = it)) }
                    },
                    label = { Text("Seed（可选）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("每次生成数量 n = 1（接口固定）", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ParameterCard("参考素材") {
                if (!appSettings.temporaryUploadEnabled && parameters.mode != VideoMode.TEXT) {
                    SupportNotice(
                        "本地素材选择已关闭：Agnes 只接受在任务完成前持续可访问的公开 URL。你仍可直接添加素材 URL；如需选择手机文件，请先在设置中启用公开素材中转池。",
                        true,
                    )
                }
                when (parameters.mode) {
                    VideoMode.TEXT -> SupportNotice("纯文本模式无需素材；媒体字段不会发送。")
                    VideoMode.KEYFRAME -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { firstFramePicker.launch("image/*") },
                                enabled = appSettings.temporaryUploadEnabled,
                            ) { Text("选择首帧") }
                            OutlinedButton(
                                onClick = { lastFramePicker.launch("image/*") },
                                enabled = appSettings.temporaryUploadEnabled,
                            ) { Text("选择尾帧") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { remoteRole = AttachmentRole.FIRST_FRAME }) { Text("首帧 URL") }
                            TextButton(onClick = { remoteRole = AttachmentRole.LAST_FRAME }) { Text("尾帧 URL") }
                        }
                        Text("至少提供首帧或尾帧之一。", style = MaterialTheme.typography.bodySmall)
                    }
                    VideoMode.REFERENCE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { imagePicker.launch("image/*") },
                                enabled = appSettings.temporaryUploadEnabled,
                            ) {
                                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                                Spacer(Modifier.width(5.dp)); Text("参考图")
                            }
                            OutlinedButton(
                                onClick = { audioPicker.launch("audio/*") },
                                enabled = appSettings.temporaryUploadEnabled,
                            ) {
                                Icon(Icons.Outlined.AudioFile, contentDescription = null)
                                Spacer(Modifier.width(5.dp)); Text("音频")
                            }
                        }
                        if (!isFlash) {
                            OutlinedButton(
                                onClick = { videoPicker.launch("video/*") },
                                enabled = appSettings.temporaryUploadEnabled,
                            ) {
                                Icon(Icons.Outlined.VideoFile, contentDescription = null)
                                Spacer(Modifier.width(5.dp)); Text("参考视频")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { remoteRole = AttachmentRole.REFERENCE_IMAGE }) { Text("图片 URL") }
                            TextButton(onClick = { remoteRole = AttachmentRole.REFERENCE_AUDIO }) { Text("音频 URL") }
                            if (!isFlash) TextButton(onClick = { remoteRole = AttachmentRole.REFERENCE_VIDEO }) { Text("视频 URL") }
                        }
                        attachments.filter { it.role == AttachmentRole.REFERENCE_VIDEO }.forEach { video ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(video.displayName, fontWeight = FontWeight.Medium)
                                    OutlinedTextField(
                                        value = video.startSeconds.toString(),
                                        onValueChange = { raw ->
                                            raw.toDoubleOrNull()?.let {
                                                viewModel.updateVideoAttachment(video.id, startSeconds = it)
                                            }
                                        },
                                        label = { Text("start_seconds") },
                                        supportingText = { Text("从参考视频第几秒开始读取") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("保留参考视频声音")
                                            Text("require_audio", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Switch(
                                            checked = video.requireAudio,
                                            onCheckedChange = {
                                                viewModel.updateVideoAttachment(video.id, requireAudio = it)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (attachments.any { it.localPath != null } && !appSettings.temporaryUploadEnabled) {
                    SupportNotice(
                        "Agnes 视频只接收公开 URL。当前本地素材不会被静默上传；请改用 URL，或在设置中明确启用公开素材中转池。",
                        true,
                    )
                } else if (attachments.any { it.localPath != null }) {
                    SupportNotice("本地素材将在发送前由中转池上传为约 1–3 小时有效的公开临时链接；单个服务失败时自动换源，过程可在调用日志中查看。")
                }
            }
        }
        item {
            ParameterCard("高级参数") {
                OutlinedTextField(
                    value = parameters.extraJson,
                    onValueChange = { viewModel.updateVideoParameters(parameters.copy(extraJson = it)) },
                    label = { Text("高级参数 JSON") },
                    supportingText = { Text("未列出的兼容字段可透传；model/prompt/mode 等核心字段由控件覆盖") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        item {
            Button(
                onClick = { viewModel.enqueueVideo(prompt) },
                enabled = prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Outlined.Movie, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("加入视频生成队列")
            }
        }
        if (videoJobs.isNotEmpty()) {
            item { Column(Modifier.padding(horizontal = 16.dp)) { SectionLabel("生成记录", "进度与结果会自动刷新") } }
            items(videoJobs, key = { it.id }) { job ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GenerationJobCard(
                        job = job,
                        onRetry = { viewModel.retryJob(job.id) },
                        onDuplicate = { viewModel.duplicateJob(job.id) },
                        onCancel = { viewModel.cancelJob(job.id) },
                        onDelete = { viewModel.deleteJob(job.id) },
                        isDeleting = job.id in deletingJobIds,
                        onViewSettings = { settingsJobId = job.id },
                    )
                }
            }
        }
    }

    remoteRole?.let { role ->
        RemoteUrlDialog(
            title = when (role) {
                AttachmentRole.FIRST_FRAME -> "添加首帧 URL"
                AttachmentRole.LAST_FRAME -> "添加尾帧 URL"
                AttachmentRole.REFERENCE_AUDIO -> "添加参考音频 URL"
                AttachmentRole.REFERENCE_VIDEO -> "添加参考视频 URL"
                else -> "添加参考图 URL"
            },
            onDismiss = { remoteRole = null },
            onConfirm = { viewModel.addRemoteAttachment(it, role, AttachmentTarget.VIDEO) },
        )
    }
    settingsJobId?.let { id ->
        jobs.firstOrNull { it.id == id }?.let { job ->
            GenerationSettingsDialog(job) { settingsJobId = null }
        }
    }
}

@Composable
private fun ParameterCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

@Composable
private fun ResolutionHint(size: String, ratio: String) {
    val value = imageResolution(size, ratio)
    Text(
        if (value == null) "实际尺寸由服务端标准化" else "预计输出：$value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun imageResolution(size: String, ratio: String): String? {
    val oneK = mapOf(
        "1:1" to (1024 to 1024), "3:4" to (864 to 1152), "4:3" to (1152 to 864),
        "16:9" to (1312 to 736), "9:16" to (736 to 1312), "2:3" to (832 to 1248),
        "3:2" to (1248 to 832), "21:9" to (1568 to 672),
    )[ratio] ?: return null
    val factor = when (size) { "1K" -> 1; "2K" -> 2; "3K" -> 3; "4K" -> 4; else -> return null }
    return "${oneK.first * factor} × ${oneK.second * factor}"
}
