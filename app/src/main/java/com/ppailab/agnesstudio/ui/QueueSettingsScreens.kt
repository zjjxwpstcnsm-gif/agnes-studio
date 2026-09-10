package com.ppailab.agnesstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ppailab.agnesstudio.BuildConfig
import com.ppailab.agnesstudio.model.AccessPlan
import com.ppailab.agnesstudio.model.AppSettings
import com.ppailab.agnesstudio.model.CollapsibleTextFormatter
import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.JobLog
import com.ppailab.agnesstudio.model.JobLogLevel
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.ThemeMode
import com.ppailab.agnesstudio.network.RatePolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class QueueFilter { ALL, ACTIVE, FAILED, CANCELLED }

@Composable
fun QueueScreen(viewModel: AppViewModel) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val selectedLogJobId by viewModel.selectedLogJobId.collectAsStateWithLifecycle()
    val selectedJobLogs by viewModel.selectedJobLogs.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(QueueFilter.ALL) }
    var settingsJobId by remember { mutableStateOf<String?>(null) }
    val activeStatuses = setOf(
        JobStatus.QUEUED, JobStatus.WAITING_RATE_LIMIT, JobStatus.SENDING,
        JobStatus.PROCESSING, JobStatus.RETRY_WAIT,
    )
    val filtered = when (filter) {
        QueueFilter.ALL -> jobs
        QueueFilter.ACTIVE -> jobs.filter { it.status in activeStatuses }
        QueueFilter.FAILED -> jobs.filter { it.status == JobStatus.FAILED }
        QueueFilter.CANCELLED -> jobs.filter { it.status == JobStatus.CANCELLED }
    }
    val activeCount = jobs.count { it.status in activeStatuses }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("任务队列", "$activeCount / ${settings.maxQueueSize} 个活动任务") {
            TextButton(onClick = viewModel::clearFinishedJobs) {
                Icon(Icons.Outlined.CleaningServices, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("清理")
            }
        }
        LinearProgressIndicator(
            progress = { activeCount.toFloat() / settings.maxQueueSize.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (activeCount > 0) BackgroundExecutionCard(compact = true)
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceChips(QueueFilter.entries.map { it.name }, filter.name, { filter = QueueFilter.valueOf(it) }) {
                when (it) {
                    "ALL" -> "全部"
                    "ACTIVE" -> "进行中"
                    "FAILED" -> "失败"
                    else -> "已取消"
                }
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Queue, contentDescription = null)
                    Text("当前没有任务", style = MaterialTheme.typography.titleMedium)
                    Text("图片和视频请求会在这里排队、重试并恢复", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id }) { job ->
                    GenerationJobCard(
                        job = job,
                        onRetry = { viewModel.retryJob(job.id) },
                        onCancel = { viewModel.cancelJob(job.id) },
                        onViewLogs = { viewModel.openJobLogs(job.id) },
                        onViewSettings = { settingsJobId = job.id },
                    )
                }
            }
        }
    }

    selectedLogJobId?.let { jobId ->
        jobs.firstOrNull { it.id == jobId }?.let { job ->
            JobLogsDialog(job, selectedJobLogs, viewModel::closeJobLogs)
        }
    }
    settingsJobId?.let { jobId ->
        jobs.firstOrNull { it.id == jobId }?.let { job ->
            GenerationSettingsDialog(job) { settingsJobId = null }
        }
    }
}

@Composable
private fun JobLogsDialog(job: GenerationJob, logs: List<JobLog>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("调用日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${if (job.modality.name == "VIDEO") "视频" else "图片"} · ${job.status.name} · ${logs.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()
                if (logs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("这条旧任务还没有调用日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(logs, key = { _, log -> log.id }) { index, log ->
                            JobLogItem(
                                log,
                                onCollapsed = { listState.requestScrollToItem(index) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = logs.isNotEmpty(),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Agnes Studio 调用日志", formatLogs(job, logs)))
                        },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("复制全部")
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun JobLogItem(log: JobLog, onCollapsed: () -> Unit) {
    val color = when (log.level) {
        JobLogLevel.INFO -> MaterialTheme.colorScheme.primary
        JobLogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        JobLogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(log.stage, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CollapsibleLogText(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
                onCollapsed = onCollapsed,
            )
            log.details?.takeIf { it.isNotBlank() }?.let { details ->
                CollapsibleLogText(
                    text = details,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    onCollapsed = onCollapsed,
                )
            }
        }
    }
}

@Composable
private fun CollapsibleLogText(
    text: String,
    style: TextStyle,
    onCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var layoutCanCollapse by remember(text.length) { mutableStateOf(false) }
    val deterministicCanCollapse = remember(text) {
        CollapsibleTextFormatter.isDeterministicallyCollapsible(
            text,
            LOG_COLLAPSED_MAX_CHARACTERS,
            LOG_COLLAPSED_MAX_LINES,
        )
    }
    Column(modifier.fillMaxWidth()) {
        if (expanded) {
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    color = color,
                    fontFamily = fontFamily,
                )
            }
        } else {
            val preview = remember(text) {
                CollapsibleTextFormatter.collapsedText(
                    text,
                    LOG_COLLAPSED_MAX_CHARACTERS,
                    LOG_COLLAPSED_MAX_LINES,
                )
            }
            SelectionContainer {
                Text(
                    preview,
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    color = color,
                    fontFamily = fontFamily,
                    maxLines = LOG_COLLAPSED_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        if (layoutCanCollapse != result.hasVisualOverflow) {
                            layoutCanCollapse = result.hasVisualOverflow
                        }
                    },
                )
            }
        }
        if (deterministicCanCollapse || layoutCanCollapse || expanded) {
            TextButton(
                onClick = {
                    if (expanded) {
                        onCollapsed()
                        expanded = false
                    } else {
                        expanded = true
                    }
                },
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "收起日志正文" else "展开日志全文")
            }
        }
    }
}

private const val LOG_COLLAPSED_MAX_CHARACTERS = 640
private const val LOG_COLLAPSED_MAX_LINES = 8

private fun formatLogs(job: GenerationJob, logs: List<JobLog>) = buildString {
    appendLine("Agnes Studio 调用日志")
    appendLine("job_id=${job.id}")
    appendLine("type=${job.modality} status=${job.status} attempt=${job.attempt}")
    job.remoteId?.let { appendLine("remote_id=$it") }
    appendLine()
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    logs.forEach { log ->
        appendLine("[${formatter.format(Date(log.createdAt))}] ${log.level} / ${log.stage}")
        appendLine(log.message)
        log.details?.let { appendLine(it) }
        appendLine()
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val savedSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val keySaved by viewModel.keySaved.collectAsStateWithLifecycle()
    val testing by viewModel.testingKey.collectAsStateWithLifecycle()
    var settings by remember(savedSettings) { mutableStateOf(savedSettings) }
    var keyInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                "设置",
                "Agnes Studio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 密钥、限流、队列与网络策略",
            )
        }
        item { BackgroundExecutionCard() }
        item {
            SettingsCard("Agnes 连接") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (keySaved) "已保存 API Key" else "尚未保存 API Key",
                        color = if (keySaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(if (keySaved) "输入新 Key 以替换" else "Agnes API Key") },
                    placeholder = { Text("sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("使用 Android Keystore AES-GCM 加密，仅保存在本机") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.saveApiKey(keyInput)
                        keyInput = ""
                    }, enabled = keyInput.isNotBlank()) { Text(if (keySaved) "替换 Key" else "保存 Key") }
                    OutlinedButton(
                        onClick = { viewModel.testApiKey(keyInput.takeIf { it.isNotBlank() }) },
                        enabled = !testing && (keySaved || keyInput.isNotBlank()),
                    ) {
                        Icon(Icons.Outlined.Science, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    if (keySaved) TextButton(onClick = { viewModel.saveApiKey("") }) { Text("清除") }
                }
                OutlinedTextField(
                    value = settings.baseUrl,
                    onValueChange = { settings = settings.copy(baseUrl = it) },
                    label = { Text("Base URL") },
                    supportingText = { Text("可填根地址或带 /v1 的地址；仅允许 HTTPS") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsCard("账户与限流") {
                Text("访问类型", fontWeight = FontWeight.Medium)
                ChoiceChips(AccessPlan.entries.map { it.name }, settings.accessPlan.name, {
                    settings = settings.copy(accessPlan = AccessPlan.valueOf(it))
                }) {
                    when (it) {
                        "FREE" -> "免费/默认"
                        "ENTERPRISE" -> "企业认证"
                        "TOKEN_PLAN" -> "Token Plan"
                        else -> "自定义"
                    }
                }
                RateSummary(settings)
                if (settings.accessPlan == AccessPlan.CUSTOM) {
                    RpmField("文本 RPM", settings.customTextRpm) { settings = settings.copy(customTextRpm = it) }
                    RpmField("图片 1K RPM", settings.customImage1kRpm) { settings = settings.copy(customImage1kRpm = it) }
                    RpmField("图片 2K RPM", settings.customImage2kRpm) { settings = settings.copy(customImage2kRpm = it) }
                    RpmField("图片 3K RPM", settings.customImage3kRpm) { settings = settings.copy(customImage3kRpm = it) }
                    RpmField("图片 4K RPM", settings.customImage4kRpm) { settings = settings.copy(customImage4kRpm = it) }
                    RpmField("视频创建 RPM", settings.customVideoRpm) { settings = settings.copy(customVideoRpm = it) }
                }
                SupportNotice("限流按密钥类型共享；创建多个同类型 Key 不会叠加 RPM。App 使用官方“实际 RPM”作为安全阈值。")
            }
        }
        item {
            SettingsCard("队列与恢复") {
                Text("队列容量 · ${settings.maxQueueSize}", fontWeight = FontWeight.Medium)
                Slider(
                    value = settings.maxQueueSize.toFloat(),
                    onValueChange = { settings = settings.copy(maxQueueSize = it.toInt()) },
                    valueRange = 5f..100f,
                    steps = 18,
                )
                Text("自动重试次数 · ${settings.maxRetries}", fontWeight = FontWeight.Medium)
                Slider(
                    value = settings.maxRetries.toFloat(),
                    onValueChange = { settings = settings.copy(maxRetries = it.toInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                )
                OutlinedTextField(
                    value = settings.requestTimeoutSeconds.toString(),
                    onValueChange = { it.toIntOrNull()?.let { value -> settings = settings.copy(requestTimeoutSeconds = value) } },
                    label = { Text("请求超时（秒）") },
                    supportingText = { Text("保存时限制在 30–600 秒；图片官方建议 60–360 秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SupportNotice("429、远端队列满与 5xx 使用指数退避 + 抖动重试；401/403 和参数错误不会盲目重试。")
            }
        }
        item {
            SettingsCard("公开素材中转池") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用自动故障切换", fontWeight = FontWeight.Medium)
                        Text("把本地首帧、参考图、音频或视频转换成 Agnes 可访问的临时公开 URL", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.temporaryUploadEnabled,
                        onCheckedChange = { settings = settings.copy(temporaryUploadEnabled = it) },
                    )
                }
                SupportNotice(
                    if (settings.temporaryUploadEnabled) {
                        "已启用：优先尝试 Uguu（约 3 小时），失败再尝试 Litterbox（约 1 小时），换源会写入调用日志。文件与上传 IP 会交给相应第三方，持链接者可访问；请勿上传敏感素材。"
                    } else {
                        "默认关闭：App 不会把本地素材发给第三方。你填写的现成 HTTPS URL 与 Agnes 生成历史中的公开结果 URL 不受影响。"
                    },
                    isWarning = settings.temporaryUploadEnabled,
                )
            }
        }
        item {
            SettingsCard("外观") {
                Text("主题", fontWeight = FontWeight.Medium)
                ChoiceChips(ThemeMode.entries.map { it.name }, settings.darkMode.name, {
                    settings = settings.copy(darkMode = ThemeMode.valueOf(it))
                }) { when (it) { "SYSTEM" -> "跟随系统"; "LIGHT" -> "浅色"; else -> "深色" } }
            }
        }
        item {
            SettingsCard("安全说明") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("API Key 不写入源码、日志、数据库或备份。", fontWeight = FontWeight.Medium)
                }
                Text(
                    "这是 BYOK 客户端，密钥必然从你的设备直接发送给你配置的 Agnes HTTPS 网关。若要多人分发 App，建议增加自有服务端代理，不要在公共 APK 中内置共享 Key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Button(
                onClick = { viewModel.updateAppSettings(settings) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Outlined.CheckCircleOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存全部设置")
            }
        }
    }
}

@Composable
private fun RateSummary(settings: AppSettings) {
    val text = RatePolicy.rpm(settings, RatePolicy.TEXT)
    val image1 = RatePolicy.rpm(settings, "image_1K")
    val image2 = RatePolicy.rpm(settings, "image_2K")
    val image3 = RatePolicy.rpm(settings, "image_3K")
    val image4 = RatePolicy.rpm(settings, "image_4K")
    val video = RatePolicy.rpm(settings, RatePolicy.VIDEO)
    Text(
        "实际执行阈值：文本 $text RPM · 图片 1K/2K/3K/4K = $image1/$image2/$image3/$image4 RPM · 视频 $video RPM",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RpmField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { it.toIntOrNull()?.coerceIn(1, 10_000)?.let(onChange) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
