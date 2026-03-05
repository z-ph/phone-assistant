package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.myapplication.MyApplication
import com.example.myapplication.api.ZhipuApiClient
import com.example.myapplication.config.ModelProvider
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

private const val TAG = "ApiTestScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiTestScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logger = Logger(TAG)
    val apiClient = MyApplication.getApiClient()

    var selectedProvider by remember { mutableStateOf(apiClient.currentProvider) }
    var apiKey by remember { mutableStateOf(apiClient.apiKey) }
    var baseUrl by remember { mutableStateOf(apiClient.baseUrl) }
    var modelId by remember { mutableStateOf(apiClient.modelId) }

    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API 测试 - 测试和诊断API配置") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Configuration Guide Card
            ConfigurationGuideCard()

            // Provider Selection Card
            ProviderSelectionCard(
                selectedProvider = selectedProvider,
                onProviderSelected = { provider ->
                    selectedProvider = provider
                    if (baseUrl.isEmpty() || ModelProvider.getAllProviders().any { it.defaultBaseUrl == baseUrl }) {
                        baseUrl = provider.defaultBaseUrl
                    }
                    if (modelId.isEmpty() || ModelProvider.getAllProviders().any { it.defaultModel == modelId }) {
                        modelId = provider.defaultModel
                    }
                }
            )

            // API Configuration Card
            ApiConfigurationCard(
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                baseUrl = baseUrl,
                onBaseUrlChange = { baseUrl = it },
                modelId = modelId,
                onModelIdChange = { modelId = it },
                provider = selectedProvider
            )

            // Test Button
            Button(
                onClick = {
                    scope.launch {
                        isTesting = true
                        testResult = null

                        // Save config first
                        apiClient.saveConfig(selectedProvider, apiKey, baseUrl, modelId)

                        // Run test
                        testResult = testApiConnection(
                            provider = selectedProvider,
                            apiKey = apiKey,
                            baseUrl = baseUrl.ifEmpty { selectedProvider.defaultBaseUrl },
                            modelId = modelId.ifEmpty { selectedProvider.defaultModel }
                        )

                        isTesting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTesting && apiKey.isNotBlank()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("测试中...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("测试 API 连接")
                }
            }

            // Test Result Card
            testResult?.let { result ->
                TestResultCard(result = result)
            }

            // Troubleshooting Tips
            if (testResult?.isSuccess == false) {
                TroubleshootingCard(errorType = testResult!!.errorType)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ConfigurationGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "配置说明",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = """
1. 选择您的API提供商
2. 输入API Key（从提供商官网获取）
3. Base URL通常使用默认值即可
4. 点击测试按钮验证配置
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun ProviderSelectionCard(
    selectedProvider: ModelProvider,
    onProviderSelected: (ModelProvider) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "选择 API 提供商",
                style = MaterialTheme.typography.titleMedium
            )

            Column(modifier = Modifier.selectableGroup()) {
                ModelProvider.getAllProviders().forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedProvider.id == provider.id,
                                onClick = { onProviderSelected(provider) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedProvider.id == provider.id,
                            onClick = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (provider.id != "custom") {
                                Text(
                                    text = "默认模型：${provider.defaultModel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigurationCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    provider: ModelProvider
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "API 配置",
                style = MaterialTheme.typography.titleMedium
            )

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key *") },
                placeholder = { Text("输入您的API密钥") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (apiKey.isNotEmpty()) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )

            // Base URL
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text(provider.defaultBaseUrl) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("留空使用默认值: ${provider.defaultBaseUrl}")
                }
            )

            // Model ID
            OutlinedTextField(
                value = modelId,
                onValueChange = onModelIdChange,
                label = { Text("模型 ID") },
                placeholder = { Text(provider.defaultModel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("留空使用默认模型: ${provider.defaultModel}")
                }
            )

            // Show full URL
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "完整请求URL:",
                style = MaterialTheme.typography.labelMedium
            )
            SelectionContainer {
                Text(
                    text = provider.buildApiUrl(baseUrl.ifEmpty { provider.defaultBaseUrl }),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TestResultCard(result: TestResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isSuccess)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (result.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (result.isSuccess) "测试成功!" else "测试失败",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            HorizontalDivider()

            // Response details
            Text(
                text = "响应详情:",
                style = MaterialTheme.typography.labelMedium
            )

            SelectionContainer {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (result.isSuccess)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            // Raw response if available
            result.rawResponse?.let { raw ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "原始响应 (前500字符):",
                    style = MaterialTheme.typography.labelMedium
                )
                SelectionContainer {
                    Text(
                        text = raw.take(500) + if (raw.length > 500) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TroubleshootingCard(errorType: ErrorType) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    text = "故障排除建议",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            val suggestions = when (errorType) {
                ErrorType.NETWORK -> listOf(
                    "检查网络连接是否正常",
                    "确认设备可以访问互联网",
                    "尝试使用VPN或切换网络",
                    "检查防火墙设置"
                )
                ErrorType.AUTH -> listOf(
                    "确认API Key是否正确",
                    "检查API Key是否已过期",
                    "确认API Key有足够的余额",
                    "确认API Key有访问该模型的权限"
                )
                ErrorType.MODEL -> listOf(
                    "确认模型ID是否正确",
                    "检查您的账户是否有访问该模型的权限",
                    "尝试使用提供商的默认模型"
                )
                ErrorType.URL -> listOf(
                    "确认Base URL格式正确",
                    "Base URL不应包含endpoint路径",
                    "使用提供商的默认URL尝试",
                    "检查URL是否有拼写错误"
                )
                ErrorType.TIMEOUT -> listOf(
                    "网络响应超时，请稍后重试",
                    "检查网络连接稳定性",
                    "尝试切换到更快的网络"
                )
                ErrorType.UNKNOWN -> listOf(
                    "查看上方错误信息获取详细原因",
                    "尝试使用其他API提供商",
                    "联系技术支持"
                )
            }

            suggestions.forEach { suggestion ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

enum class ErrorType {
    NETWORK, AUTH, MODEL, URL, TIMEOUT, UNKNOWN
}

data class TestResult(
    val isSuccess: Boolean,
    val message: String,
    val rawResponse: String? = null,
    val errorType: ErrorType = ErrorType.UNKNOWN
)

suspend fun testApiConnection(
    provider: ModelProvider,
    apiKey: String,
    baseUrl: String,
    modelId: String
): TestResult = withContext(Dispatchers.IO) {
    val logger = Logger(TAG)
    val gson = Gson()

    try {
        val fullUrl = provider.buildApiUrl(baseUrl)
        logger.d("Testing API: $fullUrl with model: $modelId")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        // Build request body - simple test message
        val requestBody = mapOf(
            "model" to modelId,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to "Hello, this is a test message. Please respond with 'OK'."
                )
            ),
            "max_tokens" to 50
        )

        val jsonBody = gson.toJson(requestBody)
        logger.d("Request body: $jsonBody")

        val request = Request.Builder()
            .url(fullUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        logger.d("Response code: ${response.code}")
        logger.d("Response body: $responseBody")

        when {
            response.code == 401 || response.code == 403 -> {
                val errorMsg = try {
                    val errorJson = JsonParser.parseString(responseBody).asJsonObject
                    errorJson.getAsJsonObject("error")?.get("message")?.asString ?: "认证失败"
                } catch (e: Exception) {
                    "认证失败，请检查API Key"
                }
                TestResult(
                    isSuccess = false,
                    message = "HTTP ${response.code}: $errorMsg",
                    rawResponse = responseBody,
                    errorType = ErrorType.AUTH
                )
            }
            response.code == 404 -> {
                TestResult(
                    isSuccess = false,
                    message = "HTTP 404: API endpoint不存在，请检查Base URL",
                    rawResponse = responseBody,
                    errorType = ErrorType.URL
                )
            }
            response.code >= 500 -> {
                TestResult(
                    isSuccess = false,
                    message = "HTTP ${response.code}: 服务器错误",
                    rawResponse = responseBody,
                    errorType = ErrorType.UNKNOWN
                )
            }
            !response.isSuccessful -> {
                TestResult(
                    isSuccess = false,
                    message = "HTTP ${response.code}: ${response.message}",
                    rawResponse = responseBody,
                    errorType = ErrorType.UNKNOWN
                )
            }
            else -> {
                // Parse success response
                val successMsg = try {
                    val responseJson = JsonParser.parseString(responseBody).asJsonObject
                    val model = responseJson.get("model")?.asString ?: modelId
                    val content = responseJson
                        .getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString ?: "响应解析失败"
                    "模型: $model\n响应: $content"
                } catch (e: Exception) {
                    "连接成功，响应: ${responseBody?.take(200)}"
                }
                TestResult(
                    isSuccess = true,
                    message = successMsg,
                    rawResponse = responseBody
                )
            }
        }
    } catch (e: java.net.UnknownHostException) {
        logger.e("Unknown host: ${e.message}")
        TestResult(
            isSuccess = false,
            message = "无法解析主机名，请检查Base URL和网络连接",
            errorType = ErrorType.NETWORK
        )
    } catch (e: java.net.SocketTimeoutException) {
        logger.e("Timeout: ${e.message}")
        TestResult(
            isSuccess = false,
            message = "连接超时",
            errorType = ErrorType.TIMEOUT
        )
    } catch (e: java.net.ConnectException) {
        logger.e("Connection failed: ${e.message}")
        TestResult(
            isSuccess = false,
            message = "连接失败: ${e.message}",
            errorType = ErrorType.NETWORK
        )
    } catch (e: Exception) {
        logger.e("Test error: ${e.message}", e)
        TestResult(
            isSuccess = false,
            message = "错误: ${e.javaClass.simpleName} - ${e.message}",
            errorType = ErrorType.UNKNOWN
        )
    }
}
