package com.example.myapplication.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.utils.LogEntry
import com.example.myapplication.utils.LogLevel
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.delay

class LogViewModel : ViewModel() {
    val logs = Logger.logEntries

    fun clearLogs() {
        Logger.clearLogs()
    }

    fun exportLogsAsString(): String {
        return Logger.exportLogsAsString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    modifier: Modifier = Modifier,
    viewModel: LogViewModel = viewModel()
) {
    val listState = rememberLazyListState()
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var showCopySuccess by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            delay(100)
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Filter logs
    val filteredLogs = remember(logs, filterLevel, searchQuery) {
        logs.filter { log ->
            val matchesLevel = filterLevel == null || log.level == filterLevel
            val matchesSearch = searchQuery.isEmpty() ||
                    log.message.contains(searchQuery, ignoreCase = true) ||
                    log.tag.contains(searchQuery, ignoreCase = true)
            matchesLevel && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志 (${logs.size})") },
                actions = {
                    // Copy to clipboard button
                    IconButton(onClick = {
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val logText = viewModel.exportLogsAsString()
                        val clip = ClipData.newPlainText("App Logs", logText)
                        clipboardManager.setPrimaryClip(clip)
                        showCopySuccess = true
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志")
                    }
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Default.KeyboardArrowDown else Icons.Default.MoreVert,
                            contentDescription = if (autoScroll) "自动滚动" else "手动滚动"
                        )
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空日志")
                    }
                }
            )
        },
        snackbarHost = {
            if (showCopySuccess) {
                LaunchedEffect(Unit) {
                    delay(2000)
                    showCopySuccess = false
                }
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showCopySuccess = false }) {
                            Text("确定")
                        }
                    }
                ) {
                    Text("日志已复制到剪贴板")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                placeholder = { Text("搜索日志...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true
            )

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterLevel == null,
                    onClick = { filterLevel = null },
                    label = { Text("全部") }
                )
                FilterChip(
                    selected = filterLevel == LogLevel.ERROR,
                    onClick = { filterLevel = if (filterLevel == LogLevel.ERROR) null else LogLevel.ERROR },
                    label = { Text("错误") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer
                    )
                )
                FilterChip(
                    selected = filterLevel == LogLevel.WARN,
                    onClick = { filterLevel = if (filterLevel == LogLevel.WARN) null else LogLevel.WARN },
                    label = { Text("警告") }
                )
                FilterChip(
                    selected = filterLevel == LogLevel.DEBUG,
                    onClick = { filterLevel = if (filterLevel == LogLevel.DEBUG) null else LogLevel.DEBUG },
                    label = { Text("调试") }
                )

                Spacer(Modifier.weight(1f))

                Text(
                    text = "显示 ${filteredLogs.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            // Logs list
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Article,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty() || filterLevel != null)
                                "没有匹配的日志"
                            else
                                "暂无日志",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = filteredLogs,
                        key = { it.id }
                    ) { log ->
                        LogItem(log = log)
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val context = LocalContext.current
    val logText = log.throwable?.let { "${log.message}\n${it}" } ?: log.message
    val backgroundColor = when (log.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        LogLevel.INFO -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        LogLevel.VERBOSE -> MaterialTheme.colorScheme.surface
    }

    val contentColor = when (log.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        LogLevel.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val icon = when (log.level) {
        LogLevel.ERROR -> Icons.Default.Error
        LogLevel.WARN -> Icons.Default.Warning
        LogLevel.INFO -> Icons.Default.Info
        LogLevel.DEBUG -> Icons.Default.BugReport
        LogLevel.VERBOSE -> Icons.Default.Chat
    }

    val iconTint = when (log.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.secondary
        LogLevel.VERBOSE -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = log.level.name,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = log.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor
                    )
                    Text(
                        text = log.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor
                )
                log.throwable?.let {
                    Text(
                        text = it.take(500) + if (it.length > 500) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("log", logText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}
