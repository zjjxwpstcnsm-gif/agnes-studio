package com.ppailab.agnesstudio.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ppailab.agnesstudio.model.ErrorKind
import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.GenerationSettingsFormatter
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.model.Modality
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun MissingKeyBanner(onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("尚未配置 Agnes API Key", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}

@Composable
fun SectionLabel(title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChoiceChips(
    values: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    label: (String) -> String = { it },
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
fun AttachmentStrip(
    attachments: List<MediaAttachment>,
    removable: Boolean = true,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            OutlinedCard(modifier = Modifier.width(178.dp)) {
                Row(
                    Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (attachment.mimeType.startsWith("image/")) {
                        AsyncImage(
                            model = attachment.localPath?.let(::File) ?: attachment.remoteUrl,
                            contentDescription = attachment.displayName,
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            if (attachment.mimeType.startsWith("video/")) Icons.Outlined.PlayCircle else Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(attachment.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Text(
                            attachment.role.name.lowercase().replace('_', ' '),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (removable) {
                        IconButton(onClick = { onRemove(attachment.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteUrlDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("公开 HTTPS URL") },
                supportingText = { Text("链接需在 Agnes 任务完成前保持可访问") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(url); onDismiss() }, enabled = url.startsWith("https://")) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SupportNotice(text: String, isWarning: Boolean = false) {
    val color = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun GenerationJobCard(
    job: GenerationJob,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDuplicate: () -> Boolean,
    onDelete: () -> Unit,
    isDeleting: Boolean = false,
    onViewLogs: (() -> Unit)? = null,
    onViewSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showDelete by remember(job.id, job.status) { mutableStateOf(false) }
    var promptCopied by remember(job.id, job.prompt) { mutableStateOf(false) }
    var duplicated by remember(job.id) { mutableStateOf(false) }
    LaunchedEffect(duplicated) {
        if (duplicated) {
            delay(1_500)
            duplicated = false
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(promptCopied) {
        if (promptCopied) {
            delay(1_500)
            promptCopied = false
        }
    }
    if (job.status in setOf(JobStatus.WAITING_RATE_LIMIT, JobStatus.RETRY_WAIT, JobStatus.PROCESSING)) {
        LaunchedEffect(job.id, job.nextAttemptAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(job.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (job.modality == Modality.IMAGE) "图片" else "视频",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(job.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(job.prompt, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)

            if (job.status in setOf(JobStatus.SENDING, JobStatus.PROCESSING)) {
                LinearProgressIndicator(
                    progress = { (job.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (job.status == JobStatus.WAITING_RATE_LIMIT || job.status == JobStatus.RETRY_WAIT) {
                val seconds = ((job.nextAttemptAt - now).coerceAtLeast(0L) + 999) / 1_000
                Text("${statusDetail(job)} · ${seconds}s 后重试", style = MaterialTheme.typography.bodySmall)
            } else if (job.status == JobStatus.PROCESSING) {
                val seconds = ((job.nextAttemptAt - now).coerceAtLeast(0L) + 999) / 1_000
                val schedule = if (seconds > 0) "${seconds}s 后查询结果" else "正在查询结果"
                Text(
                    "${job.errorMessage ?: "服务端生成中"} · $schedule",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!job.errorMessage.isNullOrBlank()) {
                Text(
                    job.errorMessage,
                    color = if (job.status == JobStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (job.status == JobStatus.SUCCEEDED) {
                if (job.modality == Modality.IMAGE) {
                    AsyncImage(
                        model = job.resultPath?.let(::File) ?: job.resultUrl,
                        contentDescription = "生成图片",
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    VideoPreview(job.resultPath, job.resultUrl)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareResult(context, job) }) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("分享/保存")
                    }
                    job.resultUrl?.let { url ->
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("原始链接")
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { duplicated = onDuplicate() },
                    enabled = !duplicated && !isDeleting,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (duplicated) "已加入队列" else "复制并生成")
                }
                OutlinedButton(onClick = {
                    copyPromptToClipboard(context, job.prompt)
                    promptCopied = true
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (promptCopied) "已复制提示词" else "复制提示词")
                }
                onViewSettings?.let { openSettings ->
                    OutlinedButton(onClick = openSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("生成设置")
                    }
                }
                onViewLogs?.let { openLogs ->
                    OutlinedButton(onClick = openLogs) {
                        Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("调用日志")
                    }
                }
                if (job.status.isFinished) {
                    TextButton(onClick = { showDelete = true }, enabled = !isDeleting) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isDeleting) "删除中…" else "删除记录")
                    }
                }
            }

            when (job.status) {
                JobStatus.FAILED, JobStatus.CANCELLED -> OutlinedButton(onClick = onRetry, enabled = !isDeleting) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新排队")
                }
                JobStatus.QUEUED, JobStatus.WAITING_RATE_LIMIT, JobStatus.SENDING,
                JobStatus.PROCESSING, JobStatus.RETRY_WAIT -> TextButton(onClick = onCancel) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("取消任务")
                }
                JobStatus.SUCCEEDED -> Unit
            }
        }
    }
    if (showDelete && job.status.isFinished) {
        DeleteGenerationHistoryDialog(
            count = 1,
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                onDelete()
            },
        )
    }
}

@Composable
fun DeleteGenerationHistoryDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $count 条历史记录？") },
        text = {
            Text("将删除所选记录、调用日志及应用内保存的图片/视频结果，无法恢复。\n\n参考素材和已另存到相册或下载目录的副本会保留。")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun GenerationSettingsDialog(job: GenerationJob, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val snapshot = remember(job.id, job.specJson) { GenerationSettingsFormatter.snapshot(job) }
    var showRawJson by remember(job.id) { mutableStateOf(false) }
    var copied by remember(job.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${snapshot.typeLabel}生成设置") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("提示词", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SelectionContainer {
                    Text(snapshot.prompt, style = MaterialTheme.typography.bodyMedium)
                }
                snapshot.fields.forEach { (label, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (snapshot.attachments.isNotEmpty()) {
                    Text("参考素材", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    snapshot.attachments.forEachIndexed { index, attachment ->
                        SelectionContainer {
                            Text("${index + 1}. $attachment", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { showRawJson = !showRawJson }) {
                    Text(if (showRawJson) "隐藏完整任务 JSON" else "查看完整任务 JSON")
                }
                if (showRawJson) {
                    SelectionContainer {
                        Text(
                            snapshot.rawJson,
                            modifier = Modifier.fillMaxWidth().background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            ).padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                copyPromptToClipboard(context, job.prompt)
                copied = true
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "已复制提示词" else "复制提示词")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun copyPromptToClipboard(context: Context, prompt: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Agnes 提示词", prompt))
}

@Composable
private fun StatusPill(status: JobStatus) {
    val (label, color) = when (status) {
        JobStatus.QUEUED -> "已排队" to MaterialTheme.colorScheme.primaryContainer
        JobStatus.WAITING_RATE_LIMIT -> "限流等待" to MaterialTheme.colorScheme.tertiaryContainer
        JobStatus.SENDING -> "正在发送" to MaterialTheme.colorScheme.primaryContainer
        JobStatus.PROCESSING -> "生成中" to MaterialTheme.colorScheme.secondaryContainer
        JobStatus.RETRY_WAIT -> "等待重试" to MaterialTheme.colorScheme.tertiaryContainer
        JobStatus.SUCCEEDED -> "已完成" to MaterialTheme.colorScheme.secondaryContainer
        JobStatus.FAILED -> "失败" to MaterialTheme.colorScheme.errorContainer
        JobStatus.CANCELLED -> "已取消" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun VideoPreview(path: String?, url: String?) {
    val context = LocalContext.current
    val source = path?.let { File(it).toUri() } ?: url?.toUri() ?: return
    val player = remember(source) { ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(source))
        prepare()
    } }
    DisposableEffect(player) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)),
        update = { it.player = player },
    )
}

private fun statusDetail(job: GenerationJob): String = when (job.errorKind) {
    ErrorKind.REMOTE_QUEUE_FULL -> "远端队列已满"
    ErrorKind.RATE_LIMIT -> "正在保护调用频率"
    ErrorKind.NETWORK -> "等待网络恢复"
    ErrorKind.SERVER -> "服务暂时异常"
    else -> job.errorMessage ?: "等待下一次尝试"
}

private fun shareResult(context: android.content.Context, job: GenerationJob) {
    val path = job.resultPath
    if (path != null && File(path).exists()) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        val mime = if (job.modality == Modality.VIDEO) "video/mp4" else "image/*"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享或保存生成结果"))
    } else if (job.resultUrl != null) {
        context.startActivity(Intent(Intent.ACTION_VIEW, job.resultUrl.toUri()))
    }
}
