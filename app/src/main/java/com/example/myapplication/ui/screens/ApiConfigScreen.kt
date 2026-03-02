package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.api.ModelInfo
import com.example.myapplication.data.local.entities.ApiConfigEntity
import com.example.myapplication.utils.ApiProvider
import com.example.myapplication.utils.ApiProviders
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: ApiConfigViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val configs by viewModel.configs.collectAsState()
    val activeConfig by viewModel.activeConfig.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API 配置管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showEditDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "添加配置")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Info card
            item {
                InfoCard()
            }

            // Config cards
            if (configs.isEmpty()) {
                item {
                    EmptyConfigCard(
                        onAddClick = { viewModel.showEditDialog() }
                    )
                }
            } else {
                items(configs, key = { it.id }) { config ->
                    ApiConfigCard(
                        config = config,
                        isActive = config.isActive,
                        onActivate = { viewModel.setActiveConfig(config.id) },
                        onEdit = { viewModel.showEditDialog(config) },
                        onDelete = { viewModel.showDeleteConfirm(config) }
                    )
                }
            }

            // Bottom spacer
            item {
                Spacer(Modifier.height(32.dp))
            }
        }

        // Edit Dialog
        if (uiState.showEditDialog) {
            ApiConfigEditDialog(
                editingConfig = uiState.editingConfig,
                availableModels = availableModels,
                isLoadingModels = uiState.isLoadingModels,
                modelsError = uiState.modelsError,
                isTesting = uiState.isTesting,
                testResult = testResult,
                onDismiss = { viewModel.hideEditDialog() },
                onSave = { name, provider, apiKey, baseUrl, modelId ->
                    if (uiState.editingConfig != null) {
                        viewModel.updateConfig(uiState.editingConfig!!.id, name, provider, apiKey, baseUrl, modelId)
                    } else {
                        viewModel.createConfig(name, provider, apiKey, baseUrl, modelId)
                    }
                },
                onFetchModels = { provider, apiKey, baseUrl ->
                    viewModel.fetchModels(provider, apiKey, baseUrl)
                },
                onTestConnection = { provider, apiKey, baseUrl, modelId ->
                    viewModel.testConnection(provider, apiKey, baseUrl, modelId)
                },
                onClearTestResult = { viewModel.clearTestResult() }
            )
        }

        // Delete Confirmation Dialog
        if (uiState.showDeleteConfirm && uiState.configToDelete != null) {
            AlertDialog(
                onDismissRequest = { viewModel.hideDeleteConfirm() },
                title = { Text("确认删除") },
                text = { Text("确定要删除配置 \"${uiState.configToDelete!!.name}\" 吗？") },
                confirmButton = {
                    Button(
                        onClick = { viewModel.deleteConfig(uiState.configToDelete!!.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.hideDeleteConfirm() }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "管理您的 API 配置，可以添加多个配置并在它们之间切换",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun EmptyConfigCard(
    onAddClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "暂无 API 配置",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "点击下方按钮添加您的第一个 API 配置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加配置")
            }
        }
    }
}

@Composable
fun ApiConfigCard(
    config: ApiConfigEntity,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val provider = ApiProviders.getById(config.providerId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isActive)
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Active indicator
                    RadioButton(
                        selected = isActive,
                        onClick = onActivate,
                        enabled = !isActive
                    )
                    Column {
                        Text(
                            text = config.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isActive) {
                            Text(
                                text = "当前使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            HorizontalDivider()

            // Config details
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = config.modelId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (config.apiKey.isNotEmpty()) Icons.Default.Key else Icons.Default.KeyOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (config.apiKey.isNotEmpty()) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (config.apiKey.isNotEmpty()) "${config.apiKey.take(8)}..." else "未设置",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Base URL (if custom)
            if (config.baseUrl.isNotEmpty() && config.baseUrl != provider.defaultBaseUrl) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer {
                        Text(
                            text = config.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigEditDialog(
    editingConfig: ApiConfigEntity?,
    availableModels: List<ModelInfo>,
    isLoadingModels: Boolean,
    modelsError: String?,
    isTesting: Boolean,
    testResult: TestConnectionResult?,
    onDismiss: () -> Unit,
    onSave: (String, ApiProvider, String, String, String) -> Unit,
    onFetchModels: (ApiProvider, String, String) -> Unit,
    onTestConnection: (ApiProvider, String, String, String) -> Unit,
    onClearTestResult: () -> Unit
) {
    val isEditing = editingConfig != null

    // Form state
    var name by remember { mutableStateOf(editingConfig?.name ?: "") }
    var selectedProvider by remember { mutableStateOf(
        editingConfig?.providerId?.let { ApiProviders.getById(it) } ?: ApiProviders.ZHIPU
    )}
    var apiKey by remember { mutableStateOf(editingConfig?.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(editingConfig?.baseUrl ?: "") }
    var modelId by remember { mutableStateOf(editingConfig?.modelId ?: "") }
    var showApiKey by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "编辑配置" else "添加配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Configuration name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：我的OpenAI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Provider selection
                Text(
                    text = "选择提供商",
                    style = MaterialTheme.typography.labelMedium
                )

                Column(modifier = Modifier.selectableGroup()) {
                    ApiProviders.ALL.forEach { provider ->
                        val onProviderSelected = {
                            selectedProvider = provider
                            if (provider.id == "custom") {
                                baseUrl = ""
                            } else if (baseUrl.isEmpty() || ApiProviders.ALL.any { it.defaultBaseUrl == baseUrl }) {
                                baseUrl = provider.defaultBaseUrl
                            }
                            if (modelId.isEmpty() || ApiProviders.ALL.any { it.defaultModel == modelId }) {
                                modelId = provider.defaultModel
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedProvider.id == provider.id,
                                    onClick = onProviderSelected,
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedProvider.id == provider.id,
                                onClick = onProviderSelected
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (provider.id != "custom") {
                                    Text(
                                        text = provider.defaultModel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        onClearTestResult()
                    },
                    label = { Text("API Key *") },
                    placeholder = { Text("输入您的API密钥") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        onClearTestResult()
                    },
                    label = { Text("Base URL") },
                    placeholder = { Text(selectedProvider.defaultBaseUrl) },
                    singleLine = true,
                    supportingText = { Text("留空使用默认值") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Model selection
                ExposedDropdownMenuBox(
                    expanded = showModelDropdown,
                    onExpandedChange = { showModelDropdown = it }
                ) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = {
                            modelId = it
                            onClearTestResult()
                        },
                        label = { Text("模型") },
                        placeholder = { Text(selectedProvider.defaultModel) },
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModelDropdown)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    if (availableModels.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false }
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.name)
                                            if (model.ownedBy != null) {
                                                Text(
                                                    text = model.ownedBy,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        modelId = model.id
                                        showModelDropdown = false
                                        onClearTestResult()
                                    }
                                )
                            }
                        }
                    }
                }

                // Fetch models button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onFetchModels(selectedProvider, apiKey, baseUrl) },
                        enabled = apiKey.isNotBlank() && !isLoadingModels
                    ) {
                        if (isLoadingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("获取模型列表")
                    }
                }

                // Models error
                modelsError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider()

                // Test connection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onTestConnection(selectedProvider, apiKey, baseUrl, modelId) },
                        enabled = apiKey.isNotBlank() && !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("测试中...")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("测试连接")
                        }
                    }
                }

                // Test result
                testResult?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.isSuccess)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (result.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (result.isSuccess) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, selectedProvider, apiKey, baseUrl, modelId) },
                enabled = apiKey.isNotBlank()
            ) {
                Text(if (isEditing) "保存" else "添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
