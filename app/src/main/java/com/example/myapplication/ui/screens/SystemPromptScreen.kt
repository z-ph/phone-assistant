package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.MyApplication
import com.example.myapplication.agent.ToolDefinition
import com.example.myapplication.agent.ToolManager
import com.example.myapplication.agent.ToolParameter

class SystemPromptViewModel : ViewModel() {

    private var toolManager: ToolManager? = null

    fun init(toolManager: ToolManager) {
        this.toolManager = toolManager
    }

    fun getSystemPrompt(): String {
        return toolManager?.generateSystemPrompt() ?: ""
    }

    fun getDefaultSystemPrompt(): String {
        return toolManager?.getDefaultSystemPrompt() ?: ""
    }

    fun saveSystemPrompt(prompt: String) {
        toolManager?.saveCustomSystemPrompt(prompt)
    }

    fun resetToDefault() {
        toolManager?.resetToDefault()
    }

    fun isUsingCustomPrompt(): Boolean {
        return toolManager?.isUsingCustomPrompt() ?: false
    }

    fun getAvailableTools(): List<com.example.myapplication.agent.ToolDefinition> {
        return toolManager?.getAvailableTools() ?: emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SystemPromptViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val toolManager = remember { ToolManager(context) }

    LaunchedEffect(Unit) {
        viewModel.init(toolManager)
    }

    var promptText by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    var showToolsHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        promptText = viewModel.getSystemPrompt()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("系统提示词") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showToolsHelp = true }) {
                        Icon(Icons.Default.Help, contentDescription = "工具说明")
                    }
                    if (hasChanges) {
                        IconButton(onClick = {
                            viewModel.saveSystemPrompt(promptText)
                            hasChanges = false
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "保存")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Status banner
            if (viewModel.isUsingCustomPrompt()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "正在使用自定义提示词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showResetConfirm = true }) {
                            Text("重置为默认", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Editor
            OutlinedTextField(
                value = promptText,
                onValueChange = {
                    promptText = it
                    hasChanges = true
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .weight(1f),
                placeholder = { Text("输入系统���示词...") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // Bottom actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("重置")
                }
                Button(
                    onClick = {
                        viewModel.saveSystemPrompt(promptText)
                        hasChanges = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = hasChanges
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
            }
        }
    }

    // Reset confirmation dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置提示词") },
            text = { Text("确定要重置为默认系统提示词吗？这将丢失您的自定义修改。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetToDefault()
                        promptText = viewModel.getSystemPrompt()
                        hasChanges = false
                        showResetConfirm = false
                    }
                ) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Tools help dialog
    if (showToolsHelp) {
        ToolsHelpDialog(
            tools = viewModel.getAvailableTools(),
            onDismiss = { showToolsHelp = false }
        )
    }
}

@Composable
private fun ToolsHelpDialog(
    tools: List<ToolDefinition>,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可用工具") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "AI可以调用以下工具来操作手机：",
                    style = MaterialTheme.typography.bodyMedium
                )

                tools.forEach { tool ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = tool.description,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (tool.parameters.isNotEmpty()) {
                                Text(
                                    text = "参数:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                tool.parameters.forEach { param ->
                                    Text(
                                        text = "  ${param.name}: ${param.description}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "示例: ${tool.example}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "调用格式：",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "```tool\n工具名(参数1, 参数2, ...)\n```",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
