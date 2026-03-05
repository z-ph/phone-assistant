package com.example.myapplication.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.api.ZhipuApiClient
import com.example.myapplication.config.ModelProvider
import com.example.myapplication.engine.TaskEngine
import com.example.myapplication.engine.TaskStatus
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.delay

private const val TAG = "MainScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    taskEngine: TaskEngine,
    apiClient: ZhipuApiClient,
    onNavigateToApiConfig: () -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logger = Logger(TAG)

    // State
    val isRunning by taskEngine.isRunning.collectAsState()
    val taskStatus by taskEngine.taskStatus.collectAsState()
    val readinessStatus by remember { derivedStateOf { taskEngine.getReadinessStatus() } }
    val lastActions by taskEngine.lastActions.collectAsState()
    val error by taskEngine.error.collectAsState()

    var promptInput by remember { mutableStateOf("") }


    // Screen capture permission launcher
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.let {
                logger.d("Screen capture permission granted")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API 设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToApiConfig) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            item {
                StatusCard(
                    readinessStatus = readinessStatus,
                    taskStatus = taskStatus,
                    isRunning = isRunning
                )
            }

            // Permissions Card
            item {
                PermissionsCard(
                    readinessStatus = readinessStatus,
                    onOpenAccessibility = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    onRequestScreenCapture = {
                        val screenCapture = ScreenCapture.getInstance(context)
                        screenCaptureLauncher.launch(screenCapture.createCaptureIntent())
                    }
                )
            }

            // API Configuration Card
            item {
                ApiConfigCard(
                    apiClient = apiClient,
                    onShowFullConfig = onNavigateToApiConfig
                )
            }

            // Task Control Card
            item {
                TaskControlCard(
                    promptInput = promptInput,
                    onPromptChange = { promptInput = it },
                    isRunning = isRunning,
                    readinessStatus = readinessStatus,
                    onStartTask = {
                        if (promptInput.isNotBlank()) {
                            taskEngine.executeTask(promptInput)
                        }
                    },
                    onCancelTask = {
                        taskEngine.cancelTask()
                    }
                )
            }

            // Actions Card
            if (lastActions.isNotEmpty()) {
                item {
                    ActionsCard(actions = lastActions)
                }
            }

            // Error Card
            error?.let { errorMessage ->
                item {
                    ErrorCard(
                        message = errorMessage,
                        onDismiss = { taskEngine.clearError() }
                    )
                }
            }
        }
    }

}

@Composable
fun ApiConfigCard(
    apiClient: ZhipuApiClient,
    onShowFullConfig: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "API 配置",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onShowFullConfig) {
                    Text("详细设置")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider()

            // Current provider info
            val provider = apiClient.currentProvider
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        text = "Provider: ${provider.name}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Model: ${apiClient.modelId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // API Key status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (apiClient.apiKey.isNotEmpty()) Icons.Default.Key else Icons.Default.KeyOff,
                    contentDescription = null,
                    tint = if (apiClient.apiKey.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (apiClient.apiKey.isNotEmpty()) "API Key: ${apiClient.apiKey.take(8)}..." else "API Key: 未设置",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Base URL
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = apiClient.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatusCard(
    readinessStatus: com.example.myapplication.engine.ReadinessStatus,
    taskStatus: TaskStatus,
    isRunning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isRunning -> MaterialTheme.colorScheme.primaryContainer
                readinessStatus.isReady -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "状态",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusIndicator(
                    label = "无障碍",
                    isReady = readinessStatus.accessibilityServiceEnabled
                )
                StatusIndicator(
                    label = "屏幕捕获",
                    isReady = readinessStatus.screenCaptureActive
                )
                StatusIndicator(
                    label = "API Key",
                    isReady = readinessStatus.apiKeyConfigured
                )
            }

            if (isRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "任务状态: ${taskStatus.name}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(
    label: String,
    isReady: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (isReady) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun PermissionsCard(
    readinessStatus: com.example.myapplication.engine.ReadinessStatus,
    onOpenAccessibility: () -> Unit,
    onRequestScreenCapture: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "权限设置",
                style = MaterialTheme.typography.titleMedium
            )

            if (!readinessStatus.accessibilityServiceEnabled) {
                PermissionItem(
                    title = "无障碍服务",
                    description = "用于自动化UI交互",
                    buttonText = "开启",
                    onButtonClick = onOpenAccessibility
                )
            }

            if (!readinessStatus.screenCaptureActive) {
                PermissionItem(
                    title = "屏幕捕获",
                    description = "用于捕获屏幕内容",
                    buttonText = "授权",
                    onButtonClick = onRequestScreenCapture
                )
            }

            if (readinessStatus.accessibilityServiceEnabled && readinessStatus.screenCaptureActive) {
                Text(
                    text = "所有权限已授予!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(onClick = onButtonClick) {
            Text(buttonText)
        }
    }
}

@Composable
fun TaskControlCard(
    promptInput: String,
    onPromptChange: (String) -> Unit,
    isRunning: Boolean,
    readinessStatus: com.example.myapplication.engine.ReadinessStatus,
    onStartTask: () -> Unit,
    onCancelTask: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "任务控制",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = promptInput,
                onValueChange = onPromptChange,
                label = { Text("输入任务指令") },
                placeholder = { Text("例如: '打开设置并进入WiFi页面'") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning,
                minLines = 3,
                maxLines = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartTask,
                    enabled = promptInput.isNotBlank() && !isRunning && readinessStatus.isReady,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始任务")
                }

                OutlinedButton(
                    onClick = onCancelTask,
                    enabled = isRunning
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("取消")
                }
            }
        }
    }
}

@Composable
fun ActionsCard(actions: List<com.example.myapplication.api.model.UiAction>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "动作列表 (${actions.size})",
                style = MaterialTheme.typography.titleMedium
            )

            actions.forEach { action ->
                ActionItem(action = action)
            }
        }
    }
}

@Composable
fun ActionItem(action: com.example.myapplication.api.model.UiAction) {
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val description: String

    when (action) {
        is com.example.myapplication.api.model.UiAction.Click -> {
            icon = Icons.Default.AddLocation
            description = "点击 (${action.x.toInt()}, ${action.y.toInt()})"
        }
        is com.example.myapplication.api.model.UiAction.Swipe -> {
            icon = Icons.Default.Swipe
            description = "滑动 ${action.direction} (${action.distance}px)"
        }
        is com.example.myapplication.api.model.UiAction.InputText -> {
            icon = Icons.Default.Edit
            description = "输入: ${if (action.text.length > 30) action.text.take(30) + "..." else action.text}"
        }
        is com.example.myapplication.api.model.UiAction.ClickNode -> {
            icon = Icons.Default.AddLocation
            description = "点击节点: ${action.nodeId}"
        }
        is com.example.myapplication.api.model.UiAction.Navigate -> {
            icon = Icons.Default.ArrowBack
            description = "导航: ${action.action}"
        }
        is com.example.myapplication.api.model.UiAction.Wait -> {
            icon = Icons.Default.Schedule
            description = "等待: ${action.durationMs}ms"
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}
