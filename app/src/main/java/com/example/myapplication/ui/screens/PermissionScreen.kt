package com.example.myapplication.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.myapplication.MyApplication
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.shell.ShizukuHelper
import com.example.myapplication.config.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PermissionScreen"

// Helper function to check overlay permission (SYSTEM_ALERT_WINDOW)
fun checkOverlayPermission(context: android.content.Context): Boolean {
    return Settings.canDrawOverlays(context)
}

// Helper function to check app list permission (QUERY_ALL_PACKAGES)
fun checkAppListPermission(context: android.content.Context): Boolean {
    // QUERY_ALL_PACKAGES is a normal permission, granted at install time
    // We just need to check if the package manager can query packages
    val pm = context.packageManager
    return try {
        // If we can query intents, the permission is granted
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN), 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    onAllPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Get API client
    val apiClient = MyApplication.getZhipuApiClient()

    // Helper function to check notification permission
    fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-Android 13, notifications are enabled by default
        }
    }

    // Permission states
    var accessibilityGranted by remember { mutableStateOf(AutoService.isEnabled()) }
    var screenCaptureGranted by remember { mutableStateOf(ScreenCapture.isProjectionActive()) }
    var notificationGranted by remember { mutableStateOf(checkNotificationPermission()) }
    var apiKeyConfigured by remember { mutableStateOf(apiClient.isConfigured()) }
    var shizukuGranted by remember { mutableStateOf(ShizukuHelper.isReady()) }
    var overlayGranted by remember { mutableStateOf(checkOverlayPermission(context)) }
    var appListGranted by remember { mutableStateOf(checkAppListPermission(context)) }

    // Refresh function
    fun refreshStates() {
        accessibilityGranted = AutoService.isEnabled()
        screenCaptureGranted = ScreenCapture.isProjectionActive()
        notificationGranted = checkNotificationPermission()
        apiKeyConfigured = apiClient.isConfigured()
        shizukuGranted = ShizukuHelper.isReady()
        overlayGranted = checkOverlayPermission(context)
        appListGranted = checkAppListPermission(context)
        Log.d(TAG, "States refreshed: accessibility=$accessibilityGranted, screenCapture=$screenCaptureGranted, notification=$notificationGranted, apiKey=$apiKeyConfigured, shizuku=$shizukuGranted, overlay=$overlayGranted, appList=$appListGranted")
    }

    // Refresh on lifecycle resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // API Key dialog state
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var selectedProviderId by remember { mutableStateOf(apiClient.providerId.ifEmpty { "zhipu" }) }
    var apiKeyInput by remember { mutableStateOf("") }
    var baseUrlInput by remember { mutableStateOf("") }
    var modelIdInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    // Check if all permissions are granted
    val allGranted = accessibilityGranted && screenCaptureGranted && apiKeyConfigured && overlayGranted && appListGranted

    // Update callback when all permissions are granted
    LaunchedEffect(allGranted) {
        if (allGranted) {
            onAllPermissionsGranted()
        }
    }

    // Notification permission launcher (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    // Screen capture launcher - actually start the capture
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Screen capture result: resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            scope.launch {
                try {
                    val screenCapture = ScreenCapture.getInstance(context)
                    val success = withContext(Dispatchers.IO) {
                        screenCapture.startCapture(result.resultCode, result.data!!)
                    }
                    Log.d(TAG, "Screen capture started: $success")
                    screenCaptureGranted = success
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start screen capture: ${e.message}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("初始化设置") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            HeaderSection()

            // Permissions list
            PermissionCard(
                icon = Icons.Default.Accessibility,
                title = "无障碍服务",
                description = "用于自动化UI操作，如点击、滑动和文本输入",
                granted = accessibilityGranted,
                onGrant = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            PermissionCard(
                icon = Icons.Default.PhotoCamera,
                title = "屏幕录制",
                description = "用于捕获屏幕内容供AI分析",
                granted = screenCaptureGranted,
                onGrant = {
                    val screenCapture = ScreenCapture.getInstance(context)
                    screenCaptureLauncher.launch(screenCapture.createCaptureIntent())
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    title = "通知权限",
                    description = "用于显示任务进度和状态更新",
                    granted = notificationGranted,
                    onGrant = {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }

            // Overlay Permission (SYSTEM_ALERT_WINDOW)
            PermissionCard(
                icon = Icons.Default.PanTool,
                title = "悬浮窗权限",
                description = "用于显示浮动窗口展示任务进度",
                granted = overlayGranted,
                onGrant = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )

            // App List Permission (QUERY_ALL_PACKAGES)
            PermissionCard(
                icon = Icons.AutoMirrored.Filled.List,
                title = "应用列表权限",
                description = "用于获取和启动其他应用",
                granted = appListGranted,
                onGrant = {
                    // This permission is granted at install time
                    // If not granted, we can't do anything
                    Log.d(TAG, "App list permission status: $appListGranted")
                }
            )

            // API Key Configuration
            PermissionCard(
                icon = Icons.Default.Key,
                title = "API 配置",
                description = "配置AI服务的API密钥和模型",
                granted = apiKeyConfigured,
                onGrant = {
                    apiKeyInput = apiClient.apiKey
                    baseUrlInput = apiClient.baseUrl
                    modelIdInput = apiClient.modelId
                    selectedProviderId = apiClient.providerId.ifEmpty { "zhipu" }
                    showApiKeyDialog = true
                }
            )

            // Shizuku (Optional - for enhanced app operations)
            PermissionCard(
                icon = Icons.Default.Terminal,
                title = "Shizuku (可选)",
                description = "用于更可靠的应用列表和启动功能。需要先安装 Shizuku APP",
                granted = shizukuGranted,
                onGrant = {
                    // Open Shizuku app if installed, otherwise Play Store
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (intent != null) {
                            context.startActivity(intent)
                        } else {
                            // Open Play Store to Shizuku
                            val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("market://details?id=moe.shizuku.privileged.api")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(playStoreIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open Shizuku: ${e.message}")
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Progress indicator
            val grantedCount = listOf(accessibilityGranted, screenCaptureGranted, overlayGranted, appListGranted, apiKeyConfigured).count { it }
            val totalCount = 5

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "设置进度",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    LinearProgressIndicator(
                        progress = { grantedCount.toFloat() / totalCount },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "$grantedCount / $totalCount 项已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Continue button
            Button(
                onClick = onAllPermissionsGranted,
                enabled = allGranted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("进入主界面")
            }
        }
    }

    // API Key Dialog - Cherry Studio Style
    if (showApiKeyDialog) {
        val providers = ModelProvider.getAllProviders()
        val selectedProvider = providers.find { it.id == selectedProviderId } ?: providers.first()

        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "API 配置",
                            style = MaterialTheme.typography.titleLarge
                        )
                        IconButton(onClick = { showApiKeyDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }

                    // Provider Selection
                    Text(
                        text = "选择服务商",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Provider Cards Grid
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        providers.chunked(2).forEach { rowProviders ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowProviders.forEach { provider ->
                                    val isSelected = provider.id == selectedProviderId
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .border(
                                                width = if (isSelected) 2.dp else 0.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedProviderId = provider.id }
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = provider.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                // Fill empty slots
                                repeat(2 - rowProviders.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // API Key Input
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Base URL Input
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("Base URL (可选)") },
                        singleLine = true,
                        placeholder = { Text(selectedProvider.defaultBaseUrl) },
                        supportingText = { Text("留空使用默认值") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Model ID Input
                    OutlinedTextField(
                        value = modelIdInput,
                        onValueChange = { modelIdInput = it },
                        label = { Text("模型 (可选)") },
                        singleLine = true,
                        placeholder = { Text(selectedProvider.defaultModel) },
                        supportingText = { Text("留空使用默认模型") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showApiKeyDialog = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                if (apiKeyInput.isNotBlank()) {
                                    apiClient.saveConfig(
                                        provider = selectedProvider,
                                        key = apiKeyInput,
                                        url = baseUrlInput.ifBlank { selectedProvider.defaultBaseUrl },
                                        model = modelIdInput.ifBlank { selectedProvider.defaultModel }
                                    )
                                    apiKeyConfigured = true
                                    showApiKeyDialog = false
                                }
                            },
                            enabled = apiKeyInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderSection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "欢迎使用 AI 自动化助手",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Text(
                text = "此应用需要以下权限来自动化执行任务。请授予相关权限以继续。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                granted -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Granted",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                else -> {
                    Button(onClick = onGrant) {
                        Text("Grant")
                    }
                }
            }
        }
    }
}
