package com.example.myapplication.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication.MyApplication
import com.example.myapplication.agent.LangChainAgentEngine
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.utils.Logger

private const val TAG = "MainScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    langChainAgentEngine: LangChainAgentEngine = MyApplication.getLangChainAgentEngine(),
    onNavigateToApiConfig: () -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logger = Logger(TAG)

    val agentState by langChainAgentEngine.state.collectAsState()
    val isReady = remember(agentState.state) {
        agentState.state == LangChainAgentEngine.AgentStateType.READY
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            logger.d("Screen capture permission granted")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent 设置") },
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
            item {
                StatusCard(agentState = agentState, isReady = isReady)
            }

            item {
                PermissionsCard(
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

            item {
                ApiConfigSummaryCard(onShowFullConfig = onNavigateToApiConfig)
            }

            item {
                AgentControlCard(
                    langChainAgentEngine = langChainAgentEngine,
                    isReady = isReady
                )
            }

            agentState.error?.let { errorMessage ->
                item {
                    ErrorCard(
                        message = errorMessage,
                        onDismiss = { langChainAgentEngine.cancel() }
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    agentState: LangChainAgentEngine.AgentState,
    isReady: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                agentState.state == LangChainAgentEngine.AgentStateType.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                isReady -> MaterialTheme.colorScheme.secondaryContainer
                agentState.state == LangChainAgentEngine.AgentStateType.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Agent 状态",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusIndicator(
                    label = "无障碍",
                    isReady = AutoService.isEnabled()
                )
                StatusIndicator(
                    label = "屏幕捕获",
                    isReady = ScreenCapture.isProjectionActive()
                )
                StatusIndicator(
                    label = "Agent",
                    isReady = isReady
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (agentState.state == LangChainAgentEngine.AgentStateType.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = "当前状态：${
                        when (agentState.state) {
                            LangChainAgentEngine.AgentStateType.READY -> "已就绪"
                            LangChainAgentEngine.AgentStateType.RUNNING -> "运行中"
                            LangChainAgentEngine.AgentStateType.ERROR -> "错误"
                            LangChainAgentEngine.AgentStateType.COMPLETED -> "已完成"
                            else -> agentState.state.name
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (agentState.result != null) {
                Text(
                    text = "结果：${agentState.result}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            if (!AutoService.isEnabled()) {
                PermissionItem(
                    title = "无障碍服务",
                    description = "用于自动化 UI 交互",
                    buttonText = "开启",
                    onButtonClick = onOpenAccessibility
                )
            }

            if (!ScreenCapture.isProjectionActive()) {
                PermissionItem(
                    title = "屏幕捕获",
                    description = "用于捕获屏幕内容",
                    buttonText = "授权",
                    onButtonClick = onRequestScreenCapture
                )
            }

            if (AutoService.isEnabled() && ScreenCapture.isProjectionActive()) {
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
fun ApiConfigSummaryCard(
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
                    Text("管理")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider()

            Text(
                text = "在 API 配置管理中添加和切换不同的 AI 提供商",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AgentControlCard(
    langChainAgentEngine: LangChainAgentEngine,
    isReady: Boolean
) {
    val logger = Logger("AgentControlCard")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Agent 控制",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        langChainAgentEngine.execute("分析一下当前屏幕，告诉我可以做什么") { result ->
                            logger.d("测试执行结果：${result.message}")
                        }
                    },
                    enabled = isReady,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("测试 Agent")
                }

                OutlinedButton(
                    onClick = {
                        langChainAgentEngine.cancel()
                    },
                    enabled = langChainAgentEngine.state.value.state == LangChainAgentEngine.AgentStateType.RUNNING
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止")
                }
            }

            Text(
                text = "提示：在聊天界面中使用 Agent 功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
