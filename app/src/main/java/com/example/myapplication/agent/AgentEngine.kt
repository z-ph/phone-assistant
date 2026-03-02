package com.example.myapplication.agent

import android.content.Context
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.agent.models.*
import com.example.myapplication.api.ZhipuApiClient
import com.example.myapplication.config.AppConfig.Agent as AgentConfig
import com.example.myapplication.config.AppConfig.Timeouts
import com.example.myapplication.screen.Base64Encoder
import com.example.myapplication.screen.ImageCompressor
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.shell.ShellExecutor
import com.example.myapplication.utils.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Agent Engine - AI controls the entire flow using state machine pattern
 *
 * State flow:
 * Idle -> Thinking -> Acting -> Observing -> Thinking -> ... -> Completed/Failed
 *
 * Features:
 * - State machine based execution
 * - Multi-tool sequential execution
 * - Function calling support
 * - Smart context management
 */
class AgentEngine(context: Context) {

    companion object {
        private const val TAG = "AgentEngine"
        private const val TOOL_RESULT_FINISH = "FINISH:"
        private const val TOOL_RESULT_REPLY = "REPLY:"
        private const val SCREENSHOT_PREFIX = "屏幕截图成功:"
    }

    private val logger = Logger(TAG)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val appContext = context.applicationContext

    // Dependencies
    private val screenCapture = ScreenCapture.getInstance(context)
    private val base64Encoder = Base64Encoder()
    private val imageCompressor = ImageCompressor()
    private val toolManager = ToolManager(context)
    private val toolRegistry = ToolRegistry.getInstance(context)
    private val contextManager = ContextManager()
    private val shellExecutor = ShellExecutor(context)
    private val gson = Gson()

    // Execution context for tools
    private val toolExecutionContext: ToolExecutionContext
        get() = ToolExecutionContext(
            appContext = appContext,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenCapture = screenCapture,
            base64Encoder = base64Encoder,
            imageCompressor = imageCompressor,
            shellExecutor = shellExecutor
        )

    // API client
    var apiClient: ZhipuApiClient? = null

    // State
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    // Current loop state (internal)
    private var loopState: AgentLoopState = AgentLoopState.Idle

    // Current screen image (thread-safe for concurrent access)
    private val currentScreenBase64 = AtomicReference<String?>(null)

    // Callbacks
    var onStepComplete: ((AgentStep) -> Unit)? = null
    var onThinking: ((String) -> Unit)? = null
    var onToolCall: ((ToolCall) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null

    // Current job
    private var currentJob: Job? = null

    // Screen dimensions
    private val screenWidth: Int get() = screenCapture.screenWidth
    private val screenHeight: Int get() = screenCapture.screenHeight

    /**
     * Clear conversation context
     */
    fun clearContext() {
        contextManager.clear()
        logger.d("Conversation context cleared")
    }

    /**
     * Get current context size
     */
    fun getContextSize(): Int = contextManager.getMessageCount()

    /**
     * Get system prompt
     */
    fun getSystemPrompt(): String {
        return toolManager.generateSystemPrompt()
    }

    /**
     * Get ToolManager for editing system prompt
     */
    fun getToolManager(): ToolManager = toolManager

    /**
     * Execute a task - AI controls the flow
     */
    fun execute(instruction: String, maxSteps: Int = AgentConfig.DEFAULT_MAX_STEPS): Job {
        cancel()

        _state.value = AgentState(isRunning = true, maxSteps = maxSteps)
        loopState = AgentLoopState.Idle
        logger.d("Starting agent task: $instruction")

        currentJob = scope.launch {
            try {
                runStateMachine(instruction, maxSteps)
            } catch (e: CancellationException) {
                logger.d("Agent task cancelled")
                _state.value = _state.value.copy(isRunning = false, error = "任务已取消")
            } catch (e: Exception) {
                logger.e("Agent error: ${e.message}", e)
                _state.value = _state.value.copy(isRunning = false, error = e.message)
            }
        }

        return currentJob!!
    }

    /**
     * Cancel current task
     */
    fun cancel() {
        logger.d("cancel() called")
        // 首先取消协程 Job
        currentJob?.cancel()
        // 重置循环状态
        loopState = AgentLoopState.Idle
        // 清空当前任务 Job 引用
        currentJob = null
        // 更新 UI 状态
        _state.value = AgentState(isRunning = false)
        // 清空屏幕缓存
        currentScreenBase64.set(null)
    }

    /**
     * Main state machine loop
     */
    private suspend fun runStateMachine(instruction: String, maxSteps: Int) = withContext(Dispatchers.IO) {
        // Add user instruction to context
        contextManager.addUserMessage(instruction)

        // Initialize thinking state
        loopState = AgentLoopState.Thinking(
            context = LoopContext(
                currentStep = 1,
                maxSteps = maxSteps,
                goal = instruction
            )
        )

        while (isActive) {
            loopState = when (val state = loopState) {
                is AgentLoopState.Idle -> {
                    // Should not happen during execution
                    logger.w("Unexpected Idle state during execution")
                    break
                }

                is AgentLoopState.Thinking -> {
                    processThinking(state)
                }

                is AgentLoopState.Acting -> {
                    processActing(state)
                }

                is AgentLoopState.Observing -> {
                    processObserving(state)
                }

                is AgentLoopState.Completed -> {
                    logger.d("Task completed: ${state.summary}")
                    finalizeSuccess(state.summary)
                    break
                }

                is AgentLoopState.Failed -> {
                    logger.e("Task failed: ${state.error}")
                    finalizeFailure(state.error, state.recoverable)
                    break
                }
            }
        }
    }

    /**
     * Process Thinking state - call AI and get tool calls
     */
    private suspend fun processThinking(state: AgentLoopState.Thinking): AgentLoopState = withContext(Dispatchers.IO) {
        // Apply step timeout to prevent infinite loops
        try {
            withTimeout(Timeouts.STEP_TIMEOUT_MS) {
                processThinkingInternal(state)
            }
        } catch (e: TimeoutCancellationException) {
            logger.e("Step timeout exceeded after ${Timeouts.STEP_TIMEOUT_MS}ms")
            AgentLoopState.Failed("步骤超时(${Timeouts.STEP_TIMEOUT_MS / 1000}秒)", recoverable = true)
        }
    }

    /**
     * Internal thinking processing logic
     */
    private suspend fun processThinkingInternal(state: AgentLoopState.Thinking): AgentLoopState {
        // Check if cancelled - use coroutineContext.ensureActive() instead
        coroutineContext.ensureActive()

        logger.d("Thinking - Step ${state.context.currentStep}/${state.context.maxSteps}")

        _state.value = _state.value.copy(currentStep = state.context.currentStep)

        // Build messages with system prompt
        // Use ToolManager's system prompt (supports custom prompts from UI)
        val systemPrompt = toolManager.generateSystemPrompt()
        val messages = contextManager.buildMessages(
            systemPrompt = systemPrompt,
            goal = state.context.goal,
            currentScreenBase64 = currentScreenBase64.get()
        )

        // Check if cancelled before API call
        coroutineContext.ensureActive()

        // Call AI
        val response = callAI(messages)
        if (response == null) {
            return AgentLoopState.Failed("AI调用失败", recoverable = true)
        }

        // Check if cancelled after API call
        coroutineContext.ensureActive()

        logger.d("AI response: ${response.take(300)}...")

        // Parse tool calls
        val toolCalls = parseToolCalls(response)

        // Extract thinking content
        val thinking = extractThinking(response)
        thinking?.let { onThinking?.invoke(it) }

        // Save assistant response to context
        contextManager.addAssistantMessage(
            content = thinking ?: "调用工具",
            toolCalls = toolCalls
        )

        if (toolCalls.isEmpty()) {
            // No tool call - treat as pure chat response, end the task
            onReply?.invoke(response)

            return AgentLoopState.Completed(response)
        }

        // 检查是否只调用了 reply/finish 工具 - 如果是，直接结束任务
        val hasOnlyCommunicationTools = toolCalls.all { it.name == "reply" || it.name == "finish" }
        if (hasOnlyCommunicationTools) {
            // 执行 reply/finish 工具然后结束
            toolCalls.forEach { tc ->
                if (tc.name == "reply") {
                    val message = tc.parameters["message"] as? String ?: ""
                    onReply?.invoke(message)
                } else if (tc.name == "finish") {
                    val summary = tc.parameters["summary"] as? String ?: "任务完成"
                    return AgentLoopState.Completed(summary)
                }
            }
            // 如果只有 reply 没有 finish，也结束任务
            return AgentLoopState.Completed("已回复用户")
        }

        // Notify tool calls
        toolCalls.forEach { tc ->
            onToolCall?.invoke(ToolCall(
                name = tc.name,
                parameters = tc.parameters,
                rawMatch = "${tc.name}(${gson.toJson(tc.parameters)})"
            ))
        }

        // Transition to Acting state with tool queue
        return AgentLoopState.Acting(
            pendingTools = toolCalls,
            executedResults = emptyList(),
            context = state.context.copy(thinkingContent = thinking)
        )
    }

    /**
     * Process Acting state - execute tools sequentially
     */
    private suspend fun processActing(state: AgentLoopState.Acting): AgentLoopState = withContext(Dispatchers.Main) {
        if (state.pendingTools.isEmpty()) {
            // All tools executed, transition to Observing
            val combinedSuccess = state.executedResults.all { it.success }
            val combinedOutput = state.executedResults.joinToString("\n") { it.output }

            return@withContext AgentLoopState.Observing(
                result = ToolResult(
                    success = combinedSuccess,
                    output = combinedOutput
                ),
                context = state.context
            )
        }

        // Execute first tool in queue
        val tool = state.pendingTools.first()
        logger.d("Executing tool: ${tool.name}")

        // Check for special tools that end or pause execution
        when (tool.name) {
            "finish" -> {
                val summary = tool.parameters["summary"] as? String ?: "任务完成"
                return@withContext AgentLoopState.Completed(summary)
            }
            "reply" -> {
                val message = tool.parameters["message"] as? String ?: ""
                onReply?.invoke(message)

                // Create step record
                val step = AgentStep(
                    thinking = state.context.thinkingContent,
                    action = AgentAction.Reply(message),
                    observation = "消息已发送",
                    isSuccess = true
                )
                _state.value = _state.value.copy(steps = _state.value.steps + step)
                onStepComplete?.invoke(step)

                // Continue with remaining tools
                val toolResult = ToolResult.success("REPLY:$message")
                return@withContext AgentLoopState.Acting(
                    pendingTools = state.pendingTools.drop(1),
                    executedResults = state.executedResults + toolResult,
                    context = state.context.copy(
                        executedToolCallIds = state.context.executedToolCallIds + tool.getOrCreateId(),
                        executedToolResults = state.context.executedToolResults + toolResult
                    )
                )
            }
            "capture_screen" -> {
                val result = captureScreenInternal()
                if (result != null) {
                    currentScreenBase64.set(result)

                    val step = AgentStep(
                        thinking = state.context.thinkingContent,
                        action = AgentAction.CaptureScreen,
                        observation = "屏幕截图成功",
                        isSuccess = true
                    )
                    _state.value = _state.value.copy(steps = _state.value.steps + step)
                    onStepComplete?.invoke(step)

                    val toolResult = ToolResult.success("屏幕截图成功")
                    return@withContext AgentLoopState.Acting(
                        pendingTools = state.pendingTools.drop(1),
                        executedResults = state.executedResults + toolResult,
                        context = state.context.copy(
                            executedToolCallIds = state.context.executedToolCallIds + tool.getOrCreateId(),
                            executedToolResults = state.context.executedToolResults + toolResult
                        )
                    )
                } else {
                    val toolResult = ToolResult.failure("屏幕截图失败")
                    return@withContext AgentLoopState.Acting(
                        pendingTools = state.pendingTools.drop(1),
                        executedResults = state.executedResults + toolResult,
                        context = state.context.copy(
                            executedToolCallIds = state.context.executedToolCallIds + tool.getOrCreateId(),
                            executedToolResults = state.context.executedToolResults + toolResult
                        )
                    )
                }
            }
        }

        // Execute regular tool via ToolRegistry
        val result = toolRegistry.execute(tool, toolExecutionContext)

        // Create action for step record
        val action = convertToolCallToAction(tool)
        val step = AgentStep(
            thinking = state.context.thinkingContent,
            action = action,
            observation = result.output,
            isSuccess = result.success
        )
        _state.value = _state.value.copy(steps = _state.value.steps + step)
        onStepComplete?.invoke(step)

        // Clear screen cache after action (screen may have changed)
        currentScreenBase64.set(null)

        // Continue with remaining tools or fail on error
        if (!result.success) {
            // Tool failed - stop and report
            return@withContext AgentLoopState.Acting(
                pendingTools = emptyList(),  // Stop executing remaining tools
                executedResults = state.executedResults + result,
                context = state.context.copy(
                    executedToolCallIds = state.context.executedToolCallIds + tool.getOrCreateId(),
                    executedToolResults = state.context.executedToolResults + result
                )
            )
        }

        // Continue with next tool
        return@withContext AgentLoopState.Acting(
            pendingTools = state.pendingTools.drop(1),
            executedResults = state.executedResults + result,
            context = state.context.copy(
                executedToolCallIds = state.context.executedToolCallIds + tool.getOrCreateId(),
                executedToolResults = state.context.executedToolResults + result
            )
        )
    }

    /**
     * Process Observing state - analyze results and decide next step
     */
    private suspend fun processObserving(state: AgentLoopState.Observing): AgentLoopState {
        logger.d("Observing - Result: ${state.result.output.take(100)}")

        // Add tool results to context with proper tool_call_id pairing
        // Each executed tool gets its own result using stored executedToolResults
        val toolCallIds = state.context.executedToolCallIds
        val toolResults = state.context.executedToolResults

        if (toolCallIds.isEmpty()) {
            logger.w("executedToolCallIds is empty - no tool results to add")
        } else {
            // For each executed tool, add its corresponding result
            toolCallIds.forEachIndexed { index, toolCallId ->
                // Use individual result if available, otherwise use combined result
                val resultContent = if (index < toolResults.size) {
                    toolResults[index].output
                } else {
                    // Fallback to combined result if results list is shorter
                    state.result.output
                }
                contextManager.addToolResult(toolCallId.ifEmpty { "unknown" }, resultContent)
            }
        }

        // Check if we've exceeded max steps
        val nextStep = state.context.currentStep + 1
        if (nextStep > state.context.maxSteps) {
            return AgentLoopState.Failed("达到最大步骤数(${state.context.maxSteps})", recoverable = false)
        }

        // Summarize context if needed
        contextManager.summarizeIfNeeded()

        // Add observation as user message
        contextManager.addUserMessage("执行结果：${state.result.output}")

        // Continue to next thinking step
        delay(AgentConfig.STEP_DELAY_MS)

        return AgentLoopState.Thinking(
            context = state.context.copy(
                currentStep = nextStep
            )
        )
    }

    /**
     * Parse tool calls from AI response (function calling format only)
     *
     * Expects response in format: __TOOL_CALLS__[{"name": "...", "parameters": {...}}, ...]
     * This is the only supported format - no fallback to text parsing.
     */
    private fun parseToolCalls(response: String): List<ToolCallInfo> {
        // Check for __TOOL_CALLS__ marker
        if (!response.contains("__TOOL_CALLS__")) {
            logger.w("No tool calls found in response (missing __TOOL_CALLS__ marker)")
            return emptyList()
        }

        return try {
            // Extract JSON array after marker
            val jsonStartIndex = response.indexOf("__TOOL_CALLS__") + "__TOOL_CALLS__".length
            val jsonContent = response.substring(jsonStartIndex).trim()

            // Parse JSON array
            val type = object : TypeToken<List<ToolCall>>() {}.type
            val calls = gson.fromJson<List<ToolCall>>(jsonContent, type) ?: emptyList()

            calls.map { tc ->
                ToolCallInfo(
                    id = tc.id.ifEmpty { "call_${System.nanoTime()}" },
                    name = tc.name,
                    parameters = tc.parameters
                )
            }
        } catch (e: Exception) {
            logger.e("Failed to parse tool calls: ${e.message}")
            emptyList()
        }
    }

    /**
     * Convert ToolCallInfo to AgentAction for step record
     */
    private fun convertToolCallToAction(tc: ToolCallInfo): AgentAction {
        return when (tc.name) {
            "click" -> {
                val x = (tc.parameters["x"] as? Number)?.toFloat() ?: 0f
                val y = (tc.parameters["y"] as? Number)?.toFloat() ?: 0f
                AgentAction.Click(x, y)
            }
            "long_click" -> {
                val x = (tc.parameters["x"] as? Number)?.toFloat() ?: 0f
                val y = (tc.parameters["y"] as? Number)?.toFloat() ?: 0f
                val duration = (tc.parameters["duration"] as? Number)?.toLong() ?: 500L
                AgentAction.LongClick(x, y, duration)
            }
            "double_click" -> {
                val x = (tc.parameters["x"] as? Number)?.toFloat() ?: 0f
                val y = (tc.parameters["y"] as? Number)?.toFloat() ?: 0f
                AgentAction.DoubleClick(x, y)
            }
            "swipe" -> {
                val direction = tc.parameters["direction"] as? String ?: "up"
                val distance = (tc.parameters["distance"] as? Number)?.toInt() ?: 500
                AgentAction.Swipe(direction, distance)
            }
            "drag" -> {
                val startX = (tc.parameters["start_x"] as? Number)?.toFloat() ?: 0f
                val startY = (tc.parameters["start_y"] as? Number)?.toFloat() ?: 0f
                val endX = (tc.parameters["end_x"] as? Number)?.toFloat() ?: 0f
                val endY = (tc.parameters["end_y"] as? Number)?.toFloat() ?: 0f
                AgentAction.Drag(startX, startY, endX, endY)
            }
            "type" -> {
                val text = tc.parameters["text"] as? String ?: ""
                AgentAction.Type(text)
            }
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "recents" -> AgentAction.Recents
            "open_notifications" -> AgentAction.OpenNotifications
            "open_quick_settings" -> AgentAction.OpenQuickSettings
            "lock_screen" -> AgentAction.LockScreen
            "take_screenshot" -> AgentAction.TakeScreenshot
            "scroll_forward" -> AgentAction.ScrollForward
            "scroll_backward" -> AgentAction.ScrollBackward
            "copy_to_clipboard" -> {
                val text = tc.parameters["text"] as? String ?: ""
                AgentAction.CopyToClipboard(text)
            }
            "paste" -> AgentAction.Paste
            "wait" -> {
                val ms = (tc.parameters["ms"] as? Number)?.toLong() ?: 1000L
                AgentAction.Wait(ms)
            }
            "open_app" -> {
                val packageName = tc.parameters["package_name"] as? String ?: ""
                AgentAction.OpenApp(packageName)
            }
            "force_stop_app" -> {
                val packageName = tc.parameters["package_name"] as? String ?: ""
                AgentAction.ForceStopApp(packageName)
            }
            "list_apps" -> AgentAction.ListApps
            "finish" -> {
                val summary = tc.parameters["summary"] as? String ?: "任务完成"
                AgentAction.Finish(summary)
            }
            "reply" -> {
                val message = tc.parameters["message"] as? String ?: ""
                AgentAction.Reply(message)
            }
            "capture_screen" -> AgentAction.CaptureScreen
            else -> AgentAction.Unknown(tc.name)
        }
    }

    /**
     * Capture screen (internal)
     */
    private suspend fun captureScreenInternal(): String? = withContext(Dispatchers.IO) {
        try {
            val bitmap = screenCapture.capture() ?: return@withContext null
            val compressed = imageCompressor.compressSmart(bitmap)
            val base64 = base64Encoder.encode(compressed, compress = false)
            bitmap.recycle()
            compressed.recycle()
            base64
        } catch (e: Exception) {
            logger.e("Screen capture error: ${e.message}")
            null
        }
    }

    /**
     * Call AI API
     */
    private suspend fun callAI(messages: List<Map<String, Any>>): String? = withContext(Dispatchers.IO) {
        val client = apiClient ?: return@withContext null
        try {
            client.chatWithTools(messages, toolRegistry.toOpenAIToolsFormat())
                ?.choices?.firstOrNull()?.message?.content
        } catch (e: Exception) {
            logger.e("AI call error: ${e.message}")
            null
        }
    }

    /**
     * Extract thinking content from response (before __TOOL_CALLS__ marker)
     */
    private fun extractThinking(response: String): String? {
        val toolCallIndex = response.indexOf("__TOOL_CALLS__")
        if (toolCallIndex < 0) return null

        val thinking = response.substring(0, toolCallIndex).trim()
        return if (thinking.isNotBlank()) thinking else null
    }

    /**
     * Finalize successful execution
     */
    private fun finalizeSuccess(summary: String) {
        val step = AgentStep(
            thinking = null,
            action = AgentAction.Finish(summary),
            observation = "任务完成",
            isSuccess = true
        )
        _state.value = _state.value.copy(
            isRunning = false,
            isFinished = true,
            finalSummary = summary,
            steps = _state.value.steps + step
        )
        onStepComplete?.invoke(step)
    }

    /**
     * Finalize failed execution
     */
    private fun finalizeFailure(error: String, recoverable: Boolean) {
        _state.value = _state.value.copy(
            isRunning = false,
            error = error
        )
    }

    fun isReady(): Boolean {
        return AutoService.isEnabled() &&
                screenCapture.isCapturing &&
                apiClient?.isConfigured() == true
    }
}

/**
 * Agent action types - including UI element based actions (primary) and gesture based actions (fallback)
 */
sealed class AgentAction {
    // 基于控件树的操作（推荐）
    data class ClickByText(val text: String, val exact: Boolean) : AgentAction()
    data class LongClickByText(val text: String, val exact: Boolean, val duration: Long = 500) : AgentAction()
    data class FindNodesByText(val text: String, val exact: Boolean) : AgentAction()
    data class ScrollToText(val text: String, val maxSwipes: Int, val direction: String) : AgentAction()
    object GetUiHierarchy : AgentAction()

    // 手势操作（后备方案）
    data class Click(val x: Float, val y: Float) : AgentAction()
    data class LongClick(val x: Float, val y: Float, val duration: Long = 500) : AgentAction()
    data class DoubleClick(val x: Float, val y: Float) : AgentAction()
    data class Swipe(val direction: String, val distance: Int) : AgentAction()
    data class Drag(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : AgentAction()
    data class Type(val text: String) : AgentAction()

    // 全局操作
    object Back : AgentAction()
    object Home : AgentAction()
    object Recents : AgentAction()
    object OpenNotifications : AgentAction()
    object OpenQuickSettings : AgentAction()
    object LockScreen : AgentAction()
    object TakeScreenshot : AgentAction()

    // 滚动操作
    object ScrollForward : AgentAction()
    object ScrollBackward : AgentAction()

    // 剪贴板操作
    data class CopyToClipboard(val text: String) : AgentAction()
    object Paste : AgentAction()

    // 其他
    data class Wait(val duration: Long) : AgentAction()
    data class OpenApp(val packageName: String) : AgentAction()
    data class ForceStopApp(val packageName: String) : AgentAction()
    object ListApps : AgentAction()
    data class Finish(val summary: String) : AgentAction()
    data class Reply(val message: String) : AgentAction()
    object CaptureScreen : AgentAction()
    data class Unknown(val name: String) : AgentAction()

    fun toDescription(): String = when (this) {
        // 基于控件树的操作
        is ClickByText -> "ClickByText(\"$text\", exact=$exact)"
        is LongClickByText -> "LongClickByText(\"$text\", exact=$exact, ${duration}ms)"
        is FindNodesByText -> "FindNodesByText(\"$text\", exact=$exact)"
        is ScrollToText -> "ScrollToText(\"$text\", maxSwipes=$maxSwipes, dir=$direction)"
        is GetUiHierarchy -> "GetUiHierarchy()"

        // 手势操作
        is Click -> "Click(${x.toInt()}, ${y.toInt()})"
        is LongClick -> "LongClick(${x.toInt()}, ${y.toInt()}, ${duration}ms)"
        is DoubleClick -> "DoubleClick(${x.toInt()}, ${y.toInt()})"
        is Swipe -> "Swipe($direction, $distance)"
        is Drag -> "Drag(${startX.toInt()},${startY.toInt()} -> ${endX.toInt()},${endY.toInt()})"
        is Type -> "Type(\"${text.take(20)}\")"
        is Back -> "Back()"
        is Home -> "Home()"
        is Recents -> "Recents()"
        is OpenNotifications -> "OpenNotifications()"
        is OpenQuickSettings -> "OpenQuickSettings()"
        is LockScreen -> "LockScreen()"
        is TakeScreenshot -> "TakeScreenshot()"
        is ScrollForward -> "ScrollForward()"
        is ScrollBackward -> "ScrollBackward()"
        is CopyToClipboard -> "CopyToClipboard(\"${text.take(20)}\")"
        is Paste -> "Paste()"
        is Wait -> "Wait(${duration}ms)"
        is OpenApp -> "OpenApp($packageName)"
        is ForceStopApp -> "ForceStopApp($packageName)"
        is ListApps -> "ListApps()"
        is Finish -> "Finish(\"$summary\")"
        is Reply -> "Reply(\"${message.take(30)}\")"
        is CaptureScreen -> "CaptureScreen()"
        is Unknown -> "Unknown($name)"
    }
}

data class AgentState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val maxSteps: Int = 50,
    val steps: List<AgentStep> = emptyList(),
    val isFinished: Boolean = false,
    val finalSummary: String? = null,
    val error: String? = null
)

data class AgentStep(
    val thinking: String? = null,
    val action: AgentAction,
    val observation: String? = null,
    val isSuccess: Boolean = true
)
