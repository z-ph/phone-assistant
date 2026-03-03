package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.launch

private const val TAG = "TypeToolTestScreen"

/**
 * Test screen to verify type tool behavior with pre-filled text input
 *
 * 使用说明：
 * 1. 先点击一个输入框，让它获取焦点（光标在输入框内闪烁）
 * 2. 输入要测试的文本
 * 3. 点击"直接执行type"按钮
 * 4. 观察输入框内容变化，记录是覆盖还是追加
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeToolTestScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logger = Logger(TAG)

    // Pre-filled text field states
    var inputField1 by remember { mutableStateOf("已有文本一") }
    var inputField2 by remember { mutableStateOf("已有文本二") }
    var inputField3 by remember { mutableStateOf("") }

    // Test configuration
    var testText by remember { mutableStateOf("测试输入") }

    // Test results
    var testResults by remember { mutableStateOf<List<TypeTestResult>>(emptyList()) }
    var isExecuting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Type工具测试") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Instructions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "使用说明",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "步骤1: 点击下面任意一个输入框，确保光标在输入框内（获取焦点）\n" +
                                "步骤2: 在下方输入要测试的文本\n" +
                                "步骤3: 点击【直接执行type】按钮\n" +
                                "步骤4: 观察输入框内容是被覆盖还是追加",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "测试目的: 验证 AutoService.inputText() 使用 ACTION_SET_TEXT 时是" +
                                "【覆盖】现有文本还是【追加】到现有文本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // Test Input Fields
            Text(
                text = "测试输入框（先点击获取焦点）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Field 1 - Pre-filled text
            OutlinedTextField(
                value = inputField1,
                onValueChange = { inputField1 = it },
                label = { Text("输入框 1 (已有文本)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Field 2 - Pre-filled text
            OutlinedTextField(
                value = inputField2,
                onValueChange = { inputField2 = it },
                label = { Text("输入框 2 (已有文本)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Field 3 - Empty
            OutlinedTextField(
                value = inputField3,
                onValueChange = { inputField3 = it },
                label = { Text("输入框 3 (空)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("此输入框初始为空") }
            )

            // Test Configuration
            HorizontalDivider()

            Text(
                text = "测试配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Text to input
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("要输入的测试文本") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Quick reset buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        inputField1 = "已有文本一"
                        inputField2 = "已有文本二"
                        inputField3 = ""
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重置输入框")
                }

                OutlinedButton(
                    onClick = { testResults = emptyList() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空日志")
                }
            }

            // Direct Execute Button - NO AGENT, direct call
            HorizontalDivider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "执行测试（直接调用，无需Agent）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "点击按钮直接调用 AutoService.inputText(\"$testText\")，" +
                                "无需AI参与，立即看到结果",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isExecuting = true
                                val service = AutoService.getInstance()

                                if (service == null) {
                                    testResults = testResults + TypeTestResult(
                                        name = "直接调用 type",
                                        originalText = "N/A",
                                        inputText = testText,
                                        result = "❌ 失败: 无障碍服务未启动",
                                        timestamp = System.currentTimeMillis()
                                    )
                                    isExecuting = false
                                    return@launch
                                }

                                // Get current focused field text before execution
                                val currentField1 = inputField1
                                val currentField2 = inputField2
                                val currentField3 = inputField3

                                // Record which field was likely focused (we'll check after)
                                val focusedFieldBefore = when {
                                    currentField1.isNotEmpty() -> "输入框1('$currentField1')"
                                    currentField2.isNotEmpty() -> "输入框2('$currentField2')"
                                    else -> "未知输入框"
                                }

                                // Execute type directly via AutoService
                                val success = service.inputText(testText)

                                val result = if (success) {
                                    "✅ 执行成功\n" +
                                            "目标: $focusedFieldBefore\n" +
                                            "输入: '$testText'\n" +
                                            "请检查输入框内容：是【覆盖】还是【追加】？"
                                } else {
                                    "❌ 执行失败: 请确保输入框已获取焦点（光标在闪烁）"
                                }

                                testResults = testResults + TypeTestResult(
                                    name = "直接调用 type",
                                    originalText = focusedFieldBefore,
                                    inputText = testText,
                                    result = result,
                                    timestamp = System.currentTimeMillis()
                                )

                                isExecuting = false
                            }
                        },
                        enabled = !isExecuting && testText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("执行中...")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("直接执行 type(\"$testText\")")
                        }
                    }
                }
            }

            // Additional test: Clear and type
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "额外测试: 先清除再输入",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "测试先清除输入框内容，再输入新文本的行为",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    inputField1 = ""
                                    inputField2 = ""
                                    inputField3 = ""

                                    testResults = testResults + TypeTestResult(
                                        name = "清除所有输入框",
                                        originalText = "N/A",
                                        inputText = "",
                                        result = "✅ 已清空所有输入框，现在可以测试空输入框的type行为",
                                        timestamp = System.currentTimeMillis()
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空所有")
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    val service = AutoService.getInstance()
                                    if (service != null) {
                                        // First set empty text to clear
                                        service.inputText("")
                                        // Then type the test text
                                        val success = service.inputText(testText)

                                        testResults = testResults + TypeTestResult(
                                            name = "先清除再输入",
                                            originalText = "已清除",
                                            inputText = testText,
                                            result = if (success) "✅ 先清除再输入: '$testText'" else "❌ 失败",
                                            timestamp = System.currentTimeMillis()
                                        )
                                    }
                                }
                            },
                            enabled = testText.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清除+输入")
                        }
                    }
                }
            }

            // Test Results
            if (testResults.isNotEmpty()) {
                HorizontalDivider()

                Text(
                    text = "测试结果 (${testResults.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                testResults.reversed().forEach { result ->
                    TestResultCard(result = result)
                }
            }
        }
    }
}

@Composable
fun TestResultCard(result: TypeTestResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatTime(result.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Original text
            if (result.originalText.isNotEmpty()) {
                Row {
                    Text(
                        text = "原文本/目标: ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = result.originalText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Input text
            if (result.inputText.isNotEmpty()) {
                Row {
                    Text(
                        text = "输入文本: ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "'${result.inputText}'",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Result
            Text(
                text = result.result,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

data class TypeTestResult(
    val name: String,
    val originalText: String,
    val inputText: String,
    val result: String,
    val timestamp: Long
)
