package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.agent.LangChainAgentEngine
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.ui.chat.ChatScreen
import com.example.myapplication.ui.screens.ApiConfigScreen
import com.example.myapplication.ui.screens.ApiTestScreen
import com.example.myapplication.ui.screens.DebugTestScreen
import com.example.myapplication.ui.screens.LogScreen
import com.example.myapplication.ui.screens.MainScreen
import com.example.myapplication.ui.screens.PermissionScreen
import com.example.myapplication.ui.screens.TypeToolTestScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainApp(
                    hasAllPermissions = hasAllPermissions,
                    onPermissionsGranted = { hasAllPermissions = true }
                )
            }
        }

        // Check initial permission state
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private var hasAllPermissions by mutableStateOf(false)

    private fun checkPermissions() {
        val screenCaptureReady = ScreenCapture.isProjectionActive()
        val overlayReady = android.provider.Settings.canDrawOverlays(this)
        val appListReady = try {
            packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN), 0).isNotEmpty()
        } catch (e: Exception) {
            false
        }
        hasAllPermissions = AutoService.isEnabled() && screenCaptureReady && overlayReady && appListReady
    }
}

@Composable
fun MainApp(
    hasAllPermissions: Boolean,
    onPermissionsGranted: () -> Unit
) {
    var currentDestination by remember { mutableStateOf(AppDestinations.CHAT) }
    var profileSubPage by remember { mutableStateOf(ProfileSubPage.MAIN) }
    val context = LocalContext.current
    val langChainAgentEngine = MyApplication.getLangChainAgentEngine()

    // Screen capture launcher
    val screenCaptureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val screenCapture = ScreenCapture.getInstance(context)
            // Note: Actual capture setup will be handled when needed
        }
    }

    if (!hasAllPermissions) {
        PermissionScreen(
            onAllPermissionsGranted = {
                onPermissionsGranted()
                currentDestination = AppDestinations.CHAT
            },
            onNavigateToApiConfig = {
                currentDestination = AppDestinations.PROFILE
                profileSubPage = ProfileSubPage.API_CONFIG
            }
        )
    } else {
        Scaffold(
            bottomBar = {
                androidx.compose.material3.NavigationBar {
                    AppDestinations.entries.forEach { destination ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) },
                            selected = currentDestination == destination,
                            onClick = {
                                currentDestination = destination
                                if (destination == AppDestinations.PROFILE) {
                                    profileSubPage = ProfileSubPage.MAIN
                                }
                            }
                        )
                    }
                }
            }
        ) { paddingValues ->
            when (currentDestination) {
                AppDestinations.CHAT -> {
                    ChatScreen(
                        modifier = Modifier.padding(paddingValues),
                        onOpenSettings = {
                            currentDestination = AppDestinations.PROFILE
                            profileSubPage = ProfileSubPage.SETTINGS
                        }
                    )
                }
                AppDestinations.LOGS -> {
                    LogScreen(
                        modifier = Modifier.padding(paddingValues)
                    )
                }
                AppDestinations.PROFILE -> {
                    when (profileSubPage) {
                        ProfileSubPage.MAIN -> {
                            ProfileScreen(
                                modifier = Modifier.padding(paddingValues),
                                onNavigateToSettings = { profileSubPage = ProfileSubPage.SETTINGS },
                                onNavigateToApiConfig = { profileSubPage = ProfileSubPage.API_CONFIG },
                                onNavigateToApiTest = { profileSubPage = ProfileSubPage.API_TEST },
                                onNavigateToDebugTest = { profileSubPage = ProfileSubPage.DEBUG_TEST },
                                onNavigateToTypeToolTest = { profileSubPage = ProfileSubPage.TYPE_TOOL_TEST }
                            )
                        }
                        ProfileSubPage.SETTINGS -> {
                            MainScreen(
                                langChainAgentEngine = langChainAgentEngine,
                                onNavigateToApiConfig = { profileSubPage = ProfileSubPage.API_CONFIG },
                                onNavigateBack = { profileSubPage = ProfileSubPage.MAIN },
                                modifier = Modifier.padding(paddingValues)
                            )
                        }
                        ProfileSubPage.API_CONFIG -> {
                            ApiConfigScreen(
                                onNavigateBack = { profileSubPage = ProfileSubPage.MAIN },
                                modifier = Modifier.padding(paddingValues)
                            )
                        }
                        ProfileSubPage.API_TEST -> {
                            ApiTestScreen(
                                modifier = Modifier.padding(paddingValues)
                            )
                        }
                        ProfileSubPage.DEBUG_TEST -> {
                            DebugTestScreen(
                                onNavigateBack = { profileSubPage = ProfileSubPage.MAIN }
                            )
                        }
                        ProfileSubPage.TYPE_TOOL_TEST -> {
                            TypeToolTestScreen(
                                onNavigateBack = { profileSubPage = ProfileSubPage.MAIN }
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    CHAT("Chat", Icons.Default.Home),
    LOGS("Logs", Icons.AutoMirrored.Filled.List),
    PROFILE("我的", Icons.Default.AccountBox),
}

enum class ProfileSubPage {
    MAIN,
    SETTINGS,
    API_CONFIG,
    API_TEST,
    DEBUG_TEST,
    TYPE_TOOL_TEST
}

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToApiConfig: () -> Unit = {},
    onNavigateToApiTest: () -> Unit = {},
    onNavigateToDebugTest: () -> Unit = {},
    onNavigateToTypeToolTest: () -> Unit = {}
) {
    val context = LocalContext.current
    val langChainAgentEngine = MyApplication.getLangChainAgentEngine()
    val agentState by langChainAgentEngine.state.collectAsState()
    val isReady = agentState.state == LangChainAgentEngine.AgentStateType.READY || 
                  agentState.state == LangChainAgentEngine.AgentStateType.IDLE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "System Status",
                    style = MaterialTheme.typography.titleMedium
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "无障碍服务")
                        Text(
                            text = if (AutoService.isEnabled()) "已启用" else "未启用",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (AutoService.isEnabled())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Switch(
                        checked = AutoService.isEnabled(),
                        onCheckedChange = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "屏幕捕获")
                        Text(
                            text = if (ScreenCapture.isProjectionActive()) "已激活" else "未激活",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ScreenCapture.isProjectionActive())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Icon(
                        imageVector = if (ScreenCapture.isProjectionActive()) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (ScreenCapture.isProjectionActive())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Agent 状态")
                        Text(
                            text = when (agentState.state) {
                                LangChainAgentEngine.AgentStateType.READY -> "已就绪"
                                LangChainAgentEngine.AgentStateType.ERROR -> "错误：${agentState.error}"
                                LangChainAgentEngine.AgentStateType.RUNNING -> "运行中"
                                else -> agentState.state.name
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (agentState.state == LangChainAgentEngine.AgentStateType.READY)
                                MaterialTheme.colorScheme.primary
                            else if (agentState.state == LangChainAgentEngine.AgentStateType.ERROR)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (isReady) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (isReady)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Menu Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                MenuItem(
                    icon = Icons.Default.Settings,
                    title = "任务设置",
                    subtitle = "任务控制和API配置",
                    onClick = onNavigateToSettings
                )

                MenuItem(
                    icon = Icons.Default.Cloud,
                    title = "API 配置管理",
                    subtitle = "管理多个API提供商",
                    onClick = onNavigateToApiConfig
                )

                MenuItem(
                    icon = Icons.Default.BugReport,
                    title = "API 测试",
                    subtitle = "测试API连接",
                    onClick = onNavigateToApiTest
                )

                MenuItem(
                    icon = Icons.Default.Build,
                    title = "调试测试",
                    subtitle = "Shell和应用测试",
                    onClick = onNavigateToDebugTest
                )

                MenuItem(
                    icon = Icons.Default.Build,
                    title = "Type工具测试",
                    subtitle = "测试输入框文本覆盖/追加行为",
                    onClick = onNavigateToTypeToolTest
                )
            }
        }

        // Menu Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                MenuItem(
                    icon = Icons.Default.Settings,
                    title = "任务设置",
                    subtitle = "任务控制和API配置",
                    onClick = onNavigateToSettings
                )

                MenuItem(
                    icon = Icons.Default.Cloud,
                    title = "API 配置管理",
                    subtitle = "管理多个API提供商",
                    onClick = onNavigateToApiConfig
                )

                MenuItem(
                    icon = Icons.Default.BugReport,
                    title = "API 测试",
                    subtitle = "测试API连接",
                    onClick = onNavigateToApiTest
                )

                MenuItem(
                    icon = Icons.Default.Build,
                    title = "调试测试",
                    subtitle = "Shell和应用测试",
                    onClick = onNavigateToDebugTest
                )
            }
        }

        // About Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider()
                Text(
                    text = "AI Automation Assistant v1.0",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "An AI-powered mobile automation tool that uses computer vision and natural language processing to automate tasks on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainAppPreview() {
    MyApplicationTheme {
        Text("Preview")
    }
}

@Composable
fun MenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
