package com.example.myapplication.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.agent.models.ToolCallInfo
import com.example.myapplication.agent.models.ToolResult
import com.example.myapplication.api.model.SwipeDirection
import com.example.myapplication.config.AppConfig.ActionDelays as Delays
import com.example.myapplication.config.AppConfig.Coordinates as Coords
import com.example.myapplication.screen.Base64Encoder
import com.example.myapplication.screen.ImageCompressor
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.shell.ShellExecutor
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tool category for organization
 */
enum class ToolCategory {
    OBSERVATION,    // capture_screen, list_apps
    GESTURE,        // click, swipe, drag, type
    NAVIGATION,     // back, home, recents
    SYSTEM,         // open_notifications, lock_screen
    CLIPBOARD,      // copy, paste
    CONTROL,        // wait, finish, reply
    COMMUNICATION   // reply
}

/**
 * Tool definition with execution logic
 */
data class Tool(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val parameters: List<ToolParam> = emptyList(),
    val execute: suspend (params: Map<String, Any>, context: ToolExecutionContext) -> ToolResult
)

/**
 * Tool parameter definition
 */
data class ToolParam(
    val name: String,
    val type: String,  // "integer", "string", "boolean"
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null
)

/**
 * Context for tool execution
 */
data class ToolExecutionContext(
    val appContext: Context,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenCapture: ScreenCapture,
    val base64Encoder: Base64Encoder,
    val imageCompressor: ImageCompressor,
    val shellExecutor: ShellExecutor? = null
)

/**
 * Central tool registry - single source of truth for all tools
 *
 * Features:
 * - Unified tool definitions
 * - OpenAI-compatible schema generation
 * - Direct tool execution
 */
class ToolRegistry private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ToolRegistry"

        @Volatile
        private var instance: ToolRegistry? = null

        fun getInstance(context: Context): ToolRegistry {
            return instance ?: synchronized(this) {
                instance ?: ToolRegistry(context.applicationContext).also { instance = it }
            }
        }
    }

    private val logger = Logger(TAG)
    private val tools = mutableListOf<Tool>()

    init {
        registerAllTools()
    }

    /**
     * Register all available tools
     */
    private fun registerAllTools() {
        // === OBSERVATION TOOLS ===
        register(createCaptureScreenTool())
        register(createListAppsTool())

        // === GESTURE TOOLS ===
        register(createClickTool())
        register(createLongClickTool())
        register(createDoubleClickTool())
        register(createSwipeTool())
        register(createDragTool())
        register(createTypeTool())

        // === NAVIGATION TOOLS ===
        register(createBackTool())
        register(createHomeTool())
        register(createRecentsTool())
        register(createOpenNotificationsTool())
        register(createOpenQuickSettingsTool())
        register(createLockScreenTool())
        register(createTakeScreenshotTool())
        register(createScrollForwardTool())
        register(createScrollBackwardTool())

        // === CLIPBOARD TOOLS ===
        register(createCopyToClipboardTool())
        register(createPasteTool())

        // === CONTROL TOOLS ===
        register(createWaitTool())
        register(createOpenAppTool())
        register(createForceStopAppTool())
        register(createFinishTool())
        register(createReplyTool())

        logger.d("Registered ${tools.size} tools")
    }

    /**
     * Register a tool
     */
    fun register(tool: Tool) {
        tools.add(tool)
    }

    /**
     * Get tool by name
     */
    fun getTool(name: String): Tool? = tools.find { it.name == name }

    /**
     * Get all registered tools
     */
    fun getAllTools(): List<Tool> = tools.toList()

    /**
     * Get tools by category
     */
    fun getToolsByCategory(category: ToolCategory): List<Tool> =
        tools.filter { it.category == category }

    /**
     * Generate OpenAI-compatible function calling schema
     */
    fun toOpenAIToolsFormat(): List<Map<String, Any>> {
        return tools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to buildParametersSchema(tool.parameters)
                )
            )
        }
    }

    /**
     * Build JSON schema for parameters
     */
    private fun buildParametersSchema(params: List<ToolParam>): Map<String, Any> {
        if (params.isEmpty()) {
            return mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }

        val properties = mutableMapOf<String, Any>()
        val required = mutableListOf<String>()

        params.forEach { param ->
            val prop = mutableMapOf<String, Any>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { prop["enum"] = it }
            properties[param.name] = prop

            if (param.required) {
                required.add(param.name)
            }
        }

        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }

    /**
     * Execute a tool call
     */
    suspend fun execute(
        toolCall: ToolCallInfo,
        execContext: ToolExecutionContext
    ): ToolResult {
        val tool = getTool(toolCall.name)
        if (tool == null) {
            return ToolResult.failure("Unknown tool: ${toolCall.name}")
        }

        return try {
            logger.d("Executing tool: ${toolCall.name} with params: ${toolCall.parameters}")
            tool.execute(toolCall.parameters, execContext)
        } catch (e: Exception) {
            logger.e("Tool execution error: ${toolCall.name} - ${e.message}")
            ToolResult.failure("Tool execution failed: ${e.message}")
        }
    }

    // ==================== TOOL FACTORIES ====================

    private fun createCaptureScreenTool() = Tool(
        name = "capture_screen",
        description = "捕获当前屏幕截图。当你需要查看屏幕内容时调用此工具。",
        category = ToolCategory.OBSERVATION,
        parameters = emptyList(),
        execute = { _, ctx ->
            withContext(Dispatchers.IO) {
                try {
                    val bitmap = ctx.screenCapture.capture()
                        ?: return@withContext ToolResult.failure("屏幕捕获失败")
                    val compressed = ctx.imageCompressor.compressSmart(bitmap)
                    val base64 = ctx.base64Encoder.encode(compressed, compress = false)
                    bitmap.recycle()
                    compressed.recycle()
                    ToolResult.success("屏幕截图成功:$base64")
                } catch (e: Exception) {
                    ToolResult.failure("屏幕截图失败: ${e.message}")
                }
            }
        }
    )

    private fun createListAppsTool() = Tool(
        name = "list_apps",
        description = "列出手机上已安装的所有应用程序，返回应用名称和包名",
        category = ToolCategory.OBSERVATION,
        parameters = emptyList(),
        execute = { _, ctx ->
            try {
                // Use ShellExecutor if available for more reliable results
                val shellExecutor = ctx.shellExecutor
                if (shellExecutor != null && shellExecutor.isShellAvailable()) {
                    val result = shellExecutor.listAllApps(includeSystem = false, includeNonLaunchable = false)
                    if (result.isSuccess) {
                        val apps = result.getOrNull() ?: emptyList()
                        val appList = apps.take(50).joinToString("\n") { app ->
                            "${app.label} = ${app.packageName}"
                        }
                        return@Tool ToolResult.success("已安装的应用(${apps.size}个):\n$appList")
                    }
                }

                // Fallback to PackageManager
                val pm = ctx.appContext.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

                val appList = apps.take(50).joinToString("\n") { app ->
                    val label = pm.getApplicationLabel(app)
                    "$label = ${app.packageName}"
                }
                ToolResult.success("已安装的应用(${apps.size}个):\n$appList")
            } catch (e: Exception) {
                ToolResult.failure("获取应用列表失败: ${e.message}")
            }
        }
    )

    private fun createClickTool() = Tool(
        name = "click",
        description = "点击屏幕指定坐标位置",
        category = ToolCategory.GESTURE,
        parameters = listOf(
            ToolParam("x", "integer", "X坐标 (0-${Coords.NORMALIZED_WIDTH})"),
            ToolParam("y", "integer", "Y坐标 (0-${Coords.NORMALIZED_HEIGHT})")
        ),
        execute = { params, ctx ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")

            val x = (params["x"] as? Number)?.toFloat() ?: 0f
            val y = (params["y"] as? Number)?.toFloat() ?: 0f
            // 确保坐标在有效范围内
            val normalizedX = x.coerceIn(0f, Coords.NORMALIZED_WIDTH.toFloat())
            val normalizedY = y.coerceIn(0f, Coords.NORMALIZED_HEIGHT.toFloat())
            // 转换为实际屏幕坐标
            val realX = normalizedX * ctx.screenWidth / Coords.NORMALIZED_WIDTH.toFloat()
            val realY = normalizedY * ctx.screenHeight / Coords.NORMALIZED_HEIGHT.toFloat()

            logger.d("Click: normalized($normalizedX, $normalizedY) -> actual($realX, $realY), screen=${ctx.screenWidth}x${ctx.screenHeight}")
            service.click(realX, realY)
            ToolResult.success("点击成功 (${realX.toInt()}, ${realY.toInt()})")
        }
    )

    private fun createLongClickTool() = Tool(
        name = "long_click",
        description = "长按屏幕指定坐标",
        category = ToolCategory.GESTURE,
        parameters = listOf(
            ToolParam("x", "integer", "X坐标 (0-${Coords.NORMALIZED_WIDTH})"),
            ToolParam("y", "integer", "Y坐标 (0-${Coords.NORMALIZED_HEIGHT})"),
            ToolParam("duration", "integer", "长按时长(毫秒)，默认500", required = false)
        ),
        execute = { params, ctx ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")

            val x = (params["x"] as? Number)?.toFloat() ?: 0f
            val y = (params["y"] as? Number)?.toFloat() ?: 0f
            val duration = (params["duration"] as? Number)?.toLong() ?: Delays.LONG_DELAY_MS
            val normalizedX = x.coerceIn(0f, Coords.NORMALIZED_WIDTH.toFloat())
            val normalizedY = y.coerceIn(0f, Coords.NORMALIZED_HEIGHT.toFloat())
            val realX = normalizedX * ctx.screenWidth / Coords.NORMALIZED_WIDTH.toFloat()
            val realY = normalizedY * ctx.screenHeight / Coords.NORMALIZED_HEIGHT.toFloat()

            service.longClick(realX, realY, duration)
            ToolResult.success("长按成功 (${realX.toInt()}, ${realY.toInt()})")
        }
    )

    private fun createDoubleClickTool() = Tool(
        name = "double_click",
        description = "双击屏幕指定坐标",
        category = ToolCategory.GESTURE,
        parameters = listOf(
            ToolParam("x", "integer", "X坐标 (0-${Coords.NORMALIZED_WIDTH})"),
            ToolParam("y", "integer", "Y坐标 (0-${Coords.NORMALIZED_HEIGHT})")
        ),
        execute = { params, ctx ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")

            val x = (params["x"] as? Number)?.toFloat() ?: 0f
            val y = (params["y"] as? Number)?.toFloat() ?: 0f
            val normalizedX = x.coerceIn(0f, Coords.NORMALIZED_WIDTH.toFloat())
            val normalizedY = y.coerceIn(0f, Coords.NORMALIZED_HEIGHT.toFloat())
            val realX = normalizedX * ctx.screenWidth / Coords.NORMALIZED_WIDTH.toFloat()
            val realY = normalizedY * ctx.screenHeight / Coords.NORMALIZED_HEIGHT.toFloat()

            service.doubleClick(realX, realY)
            ToolResult.success("双击成功 (${realX.toInt()}, ${realY.toInt()})")
        }
    )

    private fun createSwipeTool() = Tool(
        name = "swipe",
        description = "在屏幕上滑动",
        category = ToolCategory.GESTURE,
        parameters = listOf(
            ToolParam("direction", "string", "滑动方向", enum = listOf("up", "down", "left", "right")),
            ToolParam("distance", "integer", "滑动距离(像素)")
        ),
        execute = { params, ctx ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")

            val direction = params["direction"] as? String ?: "up"
            val distance = (params["distance"] as? Number)?.toInt() ?: 500

            val swipeDir = when (direction.lowercase()) {
                "up" -> SwipeDirection.UP
                "down" -> SwipeDirection.DOWN
                "left" -> SwipeDirection.LEFT
                "right" -> SwipeDirection.RIGHT
                else -> SwipeDirection.UP
            }
            // 根据归一化高度缩放滑动距离
            val scaledDistance = (distance * ctx.screenHeight / Coords.NORMALIZED_HEIGHT.toFloat()).toInt()

            service.swipe(swipeDir, scaledDistance, Delays.DEFAULT_GESTURE_DURATION_MS)
            ToolResult.success("滑动成功 $direction")
        }
    )

    private fun createDragTool() = Tool(
        name = "drag",
        description = "从一个坐标拖拽到另一个坐标",
        category = ToolCategory.GESTURE,
        parameters = listOf(
            ToolParam("start_x", "integer", "起点X坐标"),
            ToolParam("start_y", "integer", "起点Y坐标"),
            ToolParam("end_x", "integer", "终点X坐标"),
            ToolParam("end_y", "integer", "终点Y坐标")
        ),
        execute = { params, ctx ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")

            val startX = (params["start_x"] as? Number)?.toFloat() ?: 0f
            val startY = (params["start_y"] as? Number)?.toFloat() ?: 0f
            val endX = (params["end_x"] as? Number)?.toFloat() ?: 0f
            val endY = (params["end_y"] as? Number)?.toFloat() ?: 0f

            val normalizedStartX = startX.coerceIn(0f, Coords.NORMALIZED_WIDTH.toFloat())
            val normalizedStartY = startY.coerceIn(0f, Coords.NORMALIZED_HEIGHT.toFloat())
            val normalizedEndX = endX.coerceIn(0f, Coords.NORMALIZED_WIDTH.toFloat())
            val normalizedEndY = endY.coerceIn(0f, Coords.NORMALIZED_HEIGHT.toFloat())

            val realStartX = normalizedStartX * ctx.screenWidth / Coords.NORMALIZED_WIDTH.toFloat()
            val realStartY = normalizedStartY * ctx.screenHeight / Coords.NORMALIZED_HEIGHT.toFloat()
            val realEndX = normalizedEndX * ctx.screenWidth / Coords.NORMALIZED_WIDTH.toFloat()
            val realEndY = normalizedEndY * ctx.screenHeight / Coords.NORMALIZED_HEIGHT.toFloat()

            service.drag(realStartX, realStartY, realEndX, realEndY)
            ToolResult.success("拖拽成功 (${realStartX.toInt()},${realStartY.toInt()} -> ${realEndX.toInt()},${realEndY.toInt()})")
        }
    )

    private fun createTypeTool() = Tool(
        name = "type",
        description = "输入文本到当前焦点输入框",
        category = ToolCategory.GESTURE,
        parameters = listOf(
            ToolParam("text", "string", "要输入的文本内容")
        ),
        execute = { params, ctx ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")

            val text = params["text"] as? String ?: ""
            service.inputText(text)
            ToolResult.success("输入成功: ${text.take(20)}")
        }
    )

    private fun createBackTool() = Tool(
        name = "back",
        description = "按下返回键",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.pressBack()
            ToolResult.success("返回成功")
        }
    )

    private fun createHomeTool() = Tool(
        name = "home",
        description = "按下Home键，回到主屏幕",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.pressHome()
            ToolResult.success("回到主页")
        }
    )

    private fun createRecentsTool() = Tool(
        name = "recents",
        description = "打开最近任务列表",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.pressRecents()
            ToolResult.success("打开最近任务")
        }
    )

    private fun createOpenNotificationsTool() = Tool(
        name = "open_notifications",
        description = "打开通知栏",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.openNotifications()
            ToolResult.success("打开通知栏")
        }
    )

    private fun createOpenQuickSettingsTool() = Tool(
        name = "open_quick_settings",
        description = "打开快速设置面板",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.openQuickSettings()
            ToolResult.success("打开快速设置")
        }
    )

    private fun createLockScreenTool() = Tool(
        name = "lock_screen",
        description = "锁定屏幕",
        category = ToolCategory.SYSTEM,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.lockScreen()
            ToolResult.success("锁屏成功")
        }
    )

    private fun createTakeScreenshotTool() = Tool(
        name = "take_screenshot",
        description = "触发系统截屏",
        category = ToolCategory.SYSTEM,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.takeScreenshot()
            ToolResult.success("触发系统截屏")
        }
    )

    private fun createScrollForwardTool() = Tool(
        name = "scroll_forward",
        description = "向前滚动(如下一页)",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.scrollForward()
            ToolResult.success("向前滚动成功")
        }
    )

    private fun createScrollBackwardTool() = Tool(
        name = "scroll_backward",
        description = "向后滚动(如上一页)",
        category = ToolCategory.NAVIGATION,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            service.scrollBackward()
            ToolResult.success("向后滚动成功")
        }
    )

    private fun createCopyToClipboardTool() = Tool(
        name = "copy_to_clipboard",
        description = "复制文本到剪贴板",
        category = ToolCategory.CLIPBOARD,
        parameters = listOf(
            ToolParam("text", "string", "要复制的文本")
        ),
        execute = { params, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            val text = params["text"] as? String ?: ""
            service.copyToClipboard(text)
            ToolResult.success("已复制到剪贴板")
        }
    )

    private fun createPasteTool() = Tool(
        name = "paste",
        description = "粘贴剪贴板内容到当前焦点输入框",
        category = ToolCategory.CLIPBOARD,
        parameters = emptyList(),
        execute = { _, _ ->
            val service = AutoService.getInstance()
                ?: return@Tool ToolResult.failure("无障碍服务未启用")
            val text = service.getClipboardText() ?: ""
            if (text.isNotEmpty()) {
                service.inputText(text)
                ToolResult.success("粘贴成功: ${text.take(20)}")
            } else {
                ToolResult.failure("剪贴板为空")
            }
        }
    )

    private fun createWaitTool() = Tool(
        name = "wait",
        description = "等待指定时间，用于等待页面加载或动画完成",
        category = ToolCategory.CONTROL,
        parameters = listOf(
            ToolParam("ms", "integer", "等待时间(毫秒)")
        ),
        execute = { params, _ ->
            val ms = (params["ms"] as? Number)?.toLong() ?: 1000L
            kotlinx.coroutines.delay(ms)
            ToolResult.success("等待 ${ms}ms")
        }
    )

    private fun createOpenAppTool() = Tool(
        name = "open_app",
        description = "打开指定的应用程序。可以输入包名(如com.tencent.mm)或应用名称(如微信)。如果找不到应用，先调用list_apps查看已安装应用",
        category = ToolCategory.CONTROL,
        parameters = listOf(
            ToolParam("package_name", "string", "应用包名或应用名称")
        ),
        execute = { params, ctx ->
            val packageName = params["package_name"] as? String ?: ""

            // Try ShellExecutor first for reliable launching
            val shellExecutor = ctx.shellExecutor
            if (shellExecutor != null) {
                // If input doesn't look like a package name, find by app name
                val actualPackageName = if (!packageName.contains(".")) {
                    shellExecutor.findAppByName(packageName) ?: packageName
                } else {
                    packageName
                }

                val result = shellExecutor.launchApp(actualPackageName)
                if (result.isSuccess) {
                    return@Tool ToolResult.success(result.getOrNull() ?: "打开应用成功")
                }
                // If ShellExecutor fails, fall through to PackageManager method
            }

            // Fallback to PackageManager
            try {
                val pm = ctx.appContext.packageManager
                var resolvedPackageName = packageName

                // If input doesn't look like a package name, try to find by app name
                if (!resolvedPackageName.contains(".")) {
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    val matchedApp = apps.find {
                        pm.getApplicationLabel(it).toString().contains(resolvedPackageName, ignoreCase = true)
                    }
                    if (matchedApp != null) {
                        resolvedPackageName = matchedApp.packageName
                    }
                }

                val intent = pm.getLaunchIntentForPackage(resolvedPackageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.appContext.startActivity(intent)
                    val label = pm.getApplicationLabel(pm.getApplicationInfo(resolvedPackageName, 0))
                    ToolResult.success("打开应用成功: $label ($resolvedPackageName)")
                } else {
                    ToolResult.failure("找不到应用: ${params["package_name"]}。使用 list_apps 查看已安装的应用列表")
                }
            } catch (e: Exception) {
                ToolResult.failure("打开应用失败: ${e.message}。使用 list_apps 查看已安装的应用列表")
            }
        }
    )

    private fun createForceStopAppTool() = Tool(
        name = "force_stop_app",
        description = "强制停止指定的应用程序。可以输入包名或应用名称。需要 Shizuku 权限。",
        category = ToolCategory.SYSTEM,
        parameters = listOf(
            ToolParam("package_name", "string", "应用包名或应用名称")
        ),
        execute = { params, ctx ->
            val shellExecutor = ctx.shellExecutor
                ?: return@Tool ToolResult.failure("Shell执行器不可用")

            if (!shellExecutor.isShellAvailable()) {
                return@Tool ToolResult.failure("Shizuku 未就绪，无法强制停止应用。请确保已安装并授权 Shizuku。")
            }

            val packageName = params["package_name"] as? String ?: ""

            // Resolve package name if app name was provided
            val actualPackageName = if (!packageName.contains(".")) {
                shellExecutor.findAppByName(packageName) ?: packageName
            } else {
                packageName
            }

            val result = shellExecutor.forceStopApp(actualPackageName)
            if (result.isSuccess) {
                ToolResult.success(result.getOrNull() ?: "强制停止成功")
            } else {
                ToolResult.failure(result.exceptionOrNull()?.message ?: "强制停止失败")
            }
        }
    )

    private fun createFinishTool() = Tool(
        name = "finish",
        description = "任务完成，结束执行并报告结果",
        category = ToolCategory.CONTROL,
        parameters = listOf(
            ToolParam("summary", "string", "任务完成总结")
        ),
        execute = { params, _ ->
            val summary = params["summary"] as? String ?: "任务完成"
            ToolResult.success("FINISH:$summary")
        }
    )

    private fun createReplyTool() = Tool(
        name = "reply",
        description = "回复用户消息。当你需要与用户交流、回答问题或报告进度时使用此工具。",
        category = ToolCategory.COMMUNICATION,
        parameters = listOf(
            ToolParam("message", "string", "要发送给用户的消息")
        ),
        execute = { params, _ ->
            val message = params["message"] as? String ?: ""
            ToolResult.success("REPLY:$message")
        }
    )
}
