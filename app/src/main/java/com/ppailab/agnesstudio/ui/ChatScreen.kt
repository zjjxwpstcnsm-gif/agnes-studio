package com.ppailab.agnesstudio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ppailab.agnesstudio.model.AttachmentRole
import com.ppailab.agnesstudio.model.ChatConversation
import com.ppailab.agnesstudio.model.ChatMessage
import com.ppailab.agnesstudio.model.CollapsibleTextFormatter
import com.ppailab.agnesstudio.model.ChatParameters
import com.ppailab.agnesstudio.model.MessageState
import com.ppailab.agnesstudio.model.ToolCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun ChatScreen(viewModel: AppViewModel) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val conversationId by viewModel.currentConversationId.collectAsStateWithLifecycle()
    val parameters by viewModel.chatParameters.collectAsStateWithLifecycle()
    val stream by viewModel.chatStream.collectAsStateWithLifecycle()
    val attachments by viewModel.chatAttachments.collectAsStateWithLifecycle()
    var input by remember(conversationId) { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    var followStreamingOutput by remember(conversationId) { mutableStateOf(true) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { viewModel.importAttachment(it, AttachmentRole.CHAT_IMAGE, AttachmentTarget.CHAT) }
    }

    LaunchedEffect(userDragging, listState.canScrollForward) {
        if (userDragging && listState.canScrollForward) followStreamingOutput = false
        if (!userDragging && !listState.canScrollForward) followStreamingOutput = true
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            followStreamingOutput = true
            listState.scrollToItem(messages.size)
        }
    }

    LaunchedEffect(
        messages.lastOrNull()?.content?.length,
        messages.lastOrNull()?.reasoning?.length,
        messages.lastOrNull()?.toolCalls?.sumOf { it.arguments.length },
    ) {
        if (messages.isNotEmpty() && followStreamingOutput && !userDragging) {
            listState.scrollToItem(messages.size)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Agnes 对话",
            subtitle = "${parameters.model} · ${if (parameters.enableThinking) "Thinking 开" else "Thinking 关"}",
        ) {
            Box {
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Outlined.History, contentDescription = "对话历史")
                }
                DropdownMenu(expanded = showHistory, onDismissRequest = { showHistory = false }) {
                    conversations.forEach { conversation ->
                        ConversationMenuItem(
                            conversation = conversation,
                            selected = conversation.id == conversationId,
                            onSelect = {
                                viewModel.selectConversation(conversation.id)
                                showHistory = false
                            },
                            onDelete = {
                                viewModel.deleteConversation(conversation.id)
                                showHistory = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = viewModel::newConversation) {
                Icon(Icons.Outlined.AddComment, contentDescription = "新对话")
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Outlined.Tune, contentDescription = "对话参数")
            }
        }

        if (messages.isEmpty()) {
            EmptyChat(Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        ChatMessageCard(
                            message = message,
                            onContentCollapsed = {
                                followStreamingOutput = false
                                // Apply the new anchor during the same remeasure that
                                // replaces the full body with the compact body.
                                listState.requestScrollToItem(index)
                            },
                            onToolResult = viewModel::submitToolResult,
                        )
                    }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.height(1.dp)) }
                }
                if (!followStreamingOutput && listState.canScrollForward) {
                    FilledTonalButton(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        onClick = {
                            followStreamingOutput = true
                            scope.launch { listState.animateScrollToItem(messages.size) }
                        },
                    ) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("回到底部")
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.imePadding(),
            shadowElevation = 5.dp,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachmentStrip(attachments) { viewModel.removeAttachment(it, AttachmentTarget.CHAT) }
                RateLimitCountdown(stream.waitingForRateLimitUntil)
                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { imagePicker.launch("image/*") }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "添加本地图片")
                    }
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Outlined.AddLink, contentDescription = "添加图片 URL")
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息…") },
                        minLines = 1,
                        maxLines = 6,
                        shape = RoundedCornerShape(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (stream.activeMessageId != null) {
                        FilledIconButton(onClick = viewModel::stopChat) {
                            Icon(Icons.Outlined.Stop, contentDescription = "停止生成")
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                if (viewModel.sendChat(input)) input = ""
                            },
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                        ) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送") }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ChatSettingsSheet(
            initial = parameters,
            onDismiss = { showSettings = false },
            onSave = {
                if (viewModel.updateChatParameters(it)) showSettings = false
            },
        )
    }
    if (showUrlDialog) {
        RemoteUrlDialog(
            title = "添加视觉理解图片",
            onDismiss = { showUrlDialog = false },
            onConfirm = { viewModel.addRemoteAttachment(it, AttachmentRole.CHAT_IMAGE, AttachmentTarget.CHAT) },
        )
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) { Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(34.dp))
            } }
            Text("开始一段多轮对话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "支持流式输出、视觉输入、Thinking 与 Tool Call",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationMenuItem(
    conversation: ChatConversation,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column(Modifier.width(210.dp)) {
                Text(
                    conversation.title,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${conversation.parameters.model} · T=${conversation.parameters.temperature} · ${if (conversation.parameters.enableThinking) "Thinking" else "普通"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onSelect,
        trailingIcon = {
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除")
            }
        },
    )
}

@Composable
private fun ChatMessageCard(
    message: ChatMessage,
    onContentCollapsed: () -> Unit,
    onToolResult: (ToolCall, String) -> Unit,
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.96f),
            shape = if (isUser) {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
            } else {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
            },
            border = if (!isUser && !isTool) androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            ) else null,
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    isTool -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
            ),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    when {
                        isUser -> "你"
                        isTool -> "工具结果"
                        else -> "Agnes"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (message.attachments.isNotEmpty()) {
                    AttachmentStrip(message.attachments, removable = false, onRemove = {})
                }
                if (message.reasoning.isNotBlank()) ReasoningBlock(message.reasoning)
                if (message.content.isNotBlank()) {
                    CollapsibleMessageBody(
                        messageId = message.id,
                        content = message.content,
                        onCollapsed = onContentCollapsed,
                    )
                }
                if (message.state == MessageState.STREAMING && message.content.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在思考…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                message.toolCalls.forEach { call -> ToolCallBlock(call, onToolResult) }
                MessageStateLine(message)
            }
        }
    }
}

@Composable
internal fun CollapsibleMessageBody(
    messageId: String,
    content: String,
    onCollapsed: () -> Unit,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var layoutCanCollapse by remember(messageId, content.length) { mutableStateOf(false) }
    val deterministicCanCollapse = remember(content) {
        CollapsibleTextFormatter.isDeterministicallyCollapsible(
            content,
            CHAT_COLLAPSED_MAX_CHARACTERS,
            CHAT_COLLAPSED_MAX_LINES,
        )
    }

    val preview = remember(content) {
        CollapsibleTextFormatter.collapsedText(
            content,
            CHAT_COLLAPSED_MAX_CHARACTERS,
            CHAT_COLLAPSED_MAX_LINES,
        )
    }
    val canCollapse = deterministicCanCollapse || layoutCanCollapse || expanded

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("chat-message-body:$messageId")
            .semantics {
                stateDescription = if (expanded) "正文已展开" else "正文已收起，仅显示前 6 行"
            },
    ) {
        // Force Compose to dispose the old text node when switching states. This
        // avoids a long SelectionContainer retaining the full-text layout after
        // its parent LazyColumn is remeasured.
        key(expanded) {
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = content,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                SelectionContainer {
                    Text(
                        text = preview,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = CHAT_COLLAPSED_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (layoutCanCollapse != result.hasVisualOverflow) {
                                layoutCanCollapse = result.hasVisualOverflow
                            }
                        },
                    )
                }
            }
        }

        if (canCollapse) {
            TextButton(
                onClick = {
                    if (expanded) {
                        // Let the containing LazyColumn establish its new anchor
                        // before this very tall item invalidates its measurement.
                        onCollapsed()
                        expanded = false
                    } else {
                        expanded = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "收起正文" else "展开全文")
            }
            if (!expanded) {
                Text(
                    "已收起 · 仅显示前 $CHAT_COLLAPSED_MAX_LINES 行",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val CHAT_COLLAPSED_MAX_CHARACTERS = 260
private const val CHAT_COLLAPSED_MAX_LINES = 6

@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("思考过程 · ${reasoning.length} 字", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                SelectionContainer {
                    Text(
                        reasoning,
                        Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallBlock(call: ToolCall, onToolResult: (ToolCall, String) -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    var showResultDialog by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("工具调用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(call.name.ifBlank { "function" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Text(
                            call.arguments.ifBlank { "{}" },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            ).padding(9.dp),
                        )
                    }
                    Text(
                        "App 不会擅自执行任意外部函数。你可以运行工具后把结果返回给模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { showResultDialog = true }) { Text("填写工具结果") }
                }
            }
        }
    }
    if (showResultDialog) ToolResultDialog(
        call = call,
        onDismiss = { showResultDialog = false },
        onConfirm = {
            onToolResult(call, it)
            showResultDialog = false
        },
    )
}

@Composable
private fun ToolResultDialog(call: ToolCall, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var result by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${call.name} 的执行结果") },
        text = {
            OutlinedTextField(
                value = result,
                onValueChange = { result = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                label = { Text("文本或 JSON") },
            )
        },
        confirmButton = { Button(onClick = { onConfirm(result) }, enabled = result.isNotBlank()) { Text("返回模型") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MessageStateLine(message: ChatMessage) {
    val line = when (message.state) {
        MessageState.COMPLETE -> null
        MessageState.STREAMING -> "流式生成中"
        MessageState.WAITING_TOOL -> "等待工具结果"
        MessageState.INTERRUPTED -> message.errorMessage ?: "生成已中断，当前内容已保留"
        MessageState.ERROR -> message.errorMessage ?: "生成失败"
    }
    line?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.state in setOf(MessageState.ERROR, MessageState.INTERRUPTED)) {
                MaterialTheme.colorScheme.error
            } else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RateLimitCountdown(retryAt: Long?) {
    if (retryAt == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (now < retryAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((retryAt - now).coerceAtLeast(0) + 999) / 1_000
    SupportNotice("触发本地限流保护，消息已排队，将在约 ${seconds}s 后自动发送。")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatSettingsSheet(
    initial: ChatParameters,
    onDismiss: () -> Unit,
    onSave: (ChatParameters) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { SectionLabel("文本模型参数", "整套参数会保存在当前会话，并应用于下一次请求") }
            item {
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    label = { Text("模型 ID") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("默认：agnes-2.5-flash") },
                )
            }
            item {
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { draft = draft.copy(systemPrompt = it) },
                    label = { Text("System Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }
            item {
                Text("Temperature · ${"%.2f".format(draft.temperature)}")
                Slider(
                    value = draft.temperature.toFloat(),
                    onValueChange = { draft = draft.copy(temperature = it.toDouble()) },
                    valueRange = 0f..2f,
                )
            }
            item {
                Text("Top-P · ${"%.2f".format(draft.topP)}")
                Slider(
                    value = draft.topP.toFloat(),
                    onValueChange = { draft = draft.copy(topP = it.toDouble()) },
                    valueRange = 0f..1f,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.maxTokens.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let { draft = draft.copy(maxTokens = it) } },
                    label = { Text("Max tokens（1–65536）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Thinking 模式")
                        Text("通过 chat_template_kwargs.enable_thinking", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(draft.enableThinking, { draft = draft.copy(enableThinking = it) })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("流式输出")
                        Text("关闭后也会正常展示完整响应", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(draft.stream, { draft = draft.copy(stream = it) })
                }
            }
            item {
                OutlinedTextField(
                    value = draft.toolsJson,
                    onValueChange = { draft = draft.copy(toolsJson = it) },
                    label = { Text("Tools JSON 数组") },
                    supportingText = { Text("OpenAI function tools 格式；留 [] 表示禁用") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.toolChoice,
                    onValueChange = { draft = draft.copy(toolChoice = it) },
                    label = { Text("Tool choice") },
                    supportingText = { Text("auto / none / required / function:函数名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.extraJson,
                    onValueChange = { draft = draft.copy(extraJson = it) },
                    label = { Text("高级参数 JSON 对象") },
                    supportingText = { Text("透传未在界面列出的兼容参数；核心字段由上方控件覆盖") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            item {
                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存参数") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
