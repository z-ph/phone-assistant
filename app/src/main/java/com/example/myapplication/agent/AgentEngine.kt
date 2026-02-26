package com.example.myapplication.agent

import android.content.Context
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.agent.models.*
import com.example.myapplication.api.ZhipuApiClient
import com.example.myapplication.config.AppConfig.Agent as AgentConfig
import com.example.myapplication.screen.Base64Encoder
import com.example.myapplication.screen.ImageCompressor
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.utils.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val gson = Gson()

    // Execution context for tools
    private val toolExecutionContext: ToolExecutionContext
        get() = ToolExecutionContext(
            appContext = appContext,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenCapture = screenCapture,
            base64Encoder = base64Encoder,
            imageCompressor = imageCompressor
        )

    // API client
    var apiClient: ZhipuApiClient? = null

    // State
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    // Current loop state (internal)
    private var loopState: AgentLoopState = AgentLoopState.Idle

    // Current screen image
    private var currentScreenBase64: String? = null

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
        currentJob?.cancel()
        currentJob = null
        loopState = AgentLoopState.Idle
        _state.value = AgentState(isRunning = false)
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
        // Check if cancelled
        ensureActive()

        logger.d("Thinking - Step ${state.context.currentStep}/${state.context.maxSteps}")

        _state.value = _state.value.copy(currentStep = state.context.currentStep)

        // Build messages with system prompt
        // Use ToolManager's system prompt (supports custom prompts from UI)
        val systemPrompt = toolManager.generateSystemPrompt()
        val messages = contextManager.buildMessages(
            systemPrompt = systemPrompt,
            goal = state.context.goal,
            currentScreenBase64 = currentScreenBase64
        )

        // Check if cancelled before API call
        ensureActive()

        // Call AI
        val response = callAI(messages)
        if (response == null) {
            return@withContext AgentLoopState.Failed("AI调用失败", recoverable = true)
        }

        // Check if cancelled after API call
        ensureActive()

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

            return@withContext AgentLoopState.Completed(response)
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
        AgentLoopState.Acting(
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
                return@withContext AgentLoopState.Acting(
                    pendingTools = state.pendingTools.drop(1),
                    executedResults = state.executedResults + ToolResult.success("REPLY:$message"),
                    context = state.context
                )
            }
            "capture_screen" -> {
                val result = captureScreenInternal()
                if (result != null) {
                    currentScreenBase64 = result

                    val step = AgentStep(
                        thinking = state.context.thinkingContent,
                        action = AgentAction.CaptureScreen,
                        observation = "屏幕截图成功",
                        isSuccess = true
                    )
                    _state.value = _state.value.copy(steps = _state.value.steps + step)
                    onStepComplete?.invoke(step)

                    return@withContext AgentLoopState.Acting(
                        pendingTools = state.pendingTools.drop(1),
                        executedResults = state.executedResults + ToolResult.success("屏幕截图成功"),
                        context = state.context
                    )
                } else {
                    return@withContext AgentLoopState.Acting(
                        pendingTools = state.pendingTools.drop(1),
                        executedResults = state.executedResults + ToolResult.failure("屏幕截图失败"),
                        context = state.context
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
        currentScreenBase64 = null

        // Continue with remaining tools or fail on error
        if (!result.success) {
            // Tool failed - stop and report
            return@withContext AgentLoopState.Acting(
                pendingTools = emptyList(),  // Stop executing remaining tools
                executedResults = state.executedResults + result,
                context = state.context
            )
        }

        // Continue with next tool
        AgentLoopState.Acting(
            pendingTools = state.pendingTools.drop(1),
            executedResults = state.executedResults + result,
            context = state.context
        )
    }

    /**
     * Process Observing state - analyze results and decide next step
     */
    private suspend fun processObserving(state: AgentLoopState.Observing): AgentLoopState {
        logger.d("Observing - Result: ${state.result.output.take(100)}")

        // Add tool result to context
        contextManager.addToolResult("tool_${System.currentTimeMillis()}", state.result.output)

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
     * Parse tool calls from AI response
     */
    private fun parseToolCalls(response: String): List<ToolCallInfo> {
        // Check for new format (function calling)
        if (response.startsWith("__TOOL_CALLS__")) {
            return try {
                val json = response.removePrefix("__TOOL_CALLS__")
                val type = object : TypeToken<List<ToolCall>>() {}.type
                val calls = gson.fromJson<List<ToolCall>>(json, type) ?: emptyList()
                calls.map { tc ->
                    ToolCallInfo(
                        id = "call_${System.nanoTime()}",
                        name = tc.name,
                        parameters = tc.parameters
                    )
                }
            } catch (e: Exception) {
                logger.e("Failed to parse tool calls: ${e.message}")
                emptyList()
            }
        }

        // Fallback: parse from text (old format)
        return toolManager.parseToolCalls(response).map { tc ->
            ToolCallInfo(
                id = "call_${System.nanoTime()}",
                name = tc.name,
                parameters = tc.parameters
            )
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
     * Extract thinking from response
     */
    private fun extractThinking(response: String): String? {
        val toolCallIndex = response.indexOfAny(listOf(
            "click(", "swipe(", "type(", "back()", "home()",
            "wait(", "finish(", "reply(", "capture_screen(", "open_app(",
            "__TOOL_CALLS__"
        ))

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
 * Agent action types (unchanged for compatibility)
 */
sealed class AgentAction {
    // 手势操作
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
    object ListApps : AgentAction()
    data class Finish(val summary: String) : AgentAction()
    data class Reply(val message: String) : AgentAction()
    object CaptureScreen : AgentAction()
    data class Unknown(val name: String) : AgentAction()

    fun toDescription(): String = when (this) {
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
