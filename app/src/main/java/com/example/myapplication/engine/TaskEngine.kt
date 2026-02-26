package com.example.myapplication.engine

import android.content.Context
import com.example.myapplication.accessibility.AutoService
import com.example.myapplication.api.ZhipuApiClient
import com.example.myapplication.api.model.UiAction
import com.example.myapplication.config.AppConfig.Retry as RetryConfig
import com.example.myapplication.config.AppConfig.Timeouts as TimeoutConfig
import com.example.myapplication.screen.Base64Encoder
import com.example.myapplication.screen.ImageCompressor
import com.example.myapplication.screen.ScreenCapture
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Task Engine
 * Main orchestrator that coordinates screen capture, API communication, and action execution
 */
class TaskEngine(context: Context) {

    companion object {
        private const val TAG = "TaskEngine"

        // Default prompts
        private const val DEFAULT_PROMPT = "分析这个屏幕，告诉我应该点击哪里、滑动哪里或者输入什么来完成用户的任务。"
    }

    private val logger = Logger(TAG)
    private val scope = CoroutineScope(Dispatchers.Main)

    // Dependencies
    private val screenCapture = ScreenCapture.getInstance(context)
    private val base64Encoder = Base64Encoder()
    private val imageCompressor = ImageCompressor()
    private val actionQueue = ActionQueue()

    // API client (should be configured with API key)
    var apiClient: ZhipuApiClient? = null

    // Engine state
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _taskStatus = MutableStateFlow(TaskStatus.IDLE)
    val taskStatus: StateFlow<TaskStatus> = _taskStatus.asStateFlow()

    private val _currentPrompt = MutableStateFlow("")
    val currentPrompt: StateFlow<String> = _currentPrompt.asStateFlow()

    private val _lastActions = MutableStateFlow<List<UiAction>>(emptyList())
    val lastActions: StateFlow<List<UiAction>> = _lastActions.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    // Current task job
    private var currentTaskJob: Job? = null

    // Screen dimensions
    private val screenWidth: Int get() = screenCapture.screenWidth
    private val screenHeight: Int get() = screenCapture.screenHeight

    /**
     * Execute a task with the given prompt
     *
     * @param prompt User prompt/instruction
     * @param maxSteps Maximum number of steps to execute
     * @param onStep Callback for each step completion
     * @return Job that can be cancelled
     */
    fun executeTask(
        prompt: String = DEFAULT_PROMPT,
        maxSteps: Int = 10,
        onStep: ((Int, String) -> Unit)? = null
    ): Job {
        // Cancel any existing task
        cancelTask()

        _isRunning.value = true
        _taskStatus.value = TaskStatus.ANALYZING
        _currentPrompt.value = prompt
        _error.value = null

        logger.d("Starting task: $prompt")

        currentTaskJob = scope.launch {
            try {
                withTimeout(TimeoutConfig.TASK_TIMEOUT_MS) {
                    executeTaskInternal(prompt, maxSteps, onStep)
                }
            } catch (e: Exception) {
                logger.e("Task execution error: ${e.message}")
                _error.value = e.message
                _taskStatus.value = TaskStatus.ERROR
            } finally {
                _isRunning.value = false
            }
        }

        return currentTaskJob!!
    }

    /**
     * Cancel the current task
     */
    fun cancelTask() {
        currentTaskJob?.cancel()
        currentTaskJob = null
        actionQueue.stop()
        actionQueue.clear()
        _isRunning.value = false
        _taskStatus.value = TaskStatus.IDLE
        logger.d("Task cancelled")
    }

    /**
     * Get current queue statistics
     */
    fun getQueueStats() = actionQueue.getStats()

    /**
     * Get current action queue state
     */
    fun getQueue() = actionQueue.queue

    /**
     * Check if engine is ready to execute tasks
     */
    fun isReady(): Boolean {
        return AutoService.isEnabled() &&
                screenCapture.isCapturing &&
                apiClient?.isConfigured() == true
    }

    /**
     * Get readiness status details
     */
    fun getReadinessStatus(): ReadinessStatus {
        return ReadinessStatus(
            isReady = isReady(),
            accessibilityServiceEnabled = AutoService.isEnabled(),
            screenCaptureActive = screenCapture.isCapturing,
            apiKeyConfigured = apiClient?.isConfigured() == true
        )
    }

    // Private methods

    private suspend fun executeTaskInternal(
        prompt: String,
        maxSteps: Int,
        onStep: ((Int, String) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        for (step in 1..maxSteps) {
            logger.d("Step $step/$maxSteps")

            // Capture screen
            _taskStatus.value = TaskStatus.CAPTURING
            onStep?.invoke(step, "Capturing screen...")

            val bitmap = captureScreenWithRetry()
            if (bitmap == null) {
                _error.value = "Failed to capture screen"
                _taskStatus.value = TaskStatus.ERROR
                return@withContext
            }

            // Compress and encode
            _taskStatus.value = TaskStatus.PROCESSING
            onStep?.invoke(step, "Processing image...")

            val compressedBitmap = imageCompressor.compressSmart(bitmap)
            val base64 = base64Encoder.encode(
                compressedBitmap,
                compress = false // Already compressed
            )

            bitmap.recycle()
            compressedBitmap.recycle()

            if (base64 == null) {
                _error.value = "Failed to encode image"
                _taskStatus.value = TaskStatus.ERROR
                return@withContext
            }

            // Call API
            _taskStatus.value = TaskStatus.ANALYZING
            onStep?.invoke(step, "Analyzing screen...")

            val currentApiClient = apiClient
            if (currentApiClient == null) {
                _error.value = "API client not configured"
                _taskStatus.value = TaskStatus.ERROR
                return@withContext
            }

            val apiResult = currentApiClient.analyzeScreen(base64, prompt)

            // Check for failure first
            if (apiResult.isFailure) {
                val exception = apiResult.exceptionOrNull()
                val errorMsg = exception?.message ?: "API request failed"
                logger.e("API request failed: $errorMsg", exception)
                _error.value = errorMsg
                _taskStatus.value = TaskStatus.ERROR
                return@withContext
            }

            val response = apiResult.getOrNull()
            if (response == null) {
                _error.value = "API response is null"
                _taskStatus.value = TaskStatus.ERROR
                return@withContext
            }

            if (response.error != null) {
                val errorMsg = response.error.message ?: "Unknown API error"
                logger.e("API returned error: $errorMsg")
                _error.value = errorMsg
                _taskStatus.value = TaskStatus.ERROR
                return@withContext
            }

            // Parse actions
            val actions = currentApiClient.parseActions(response)
            _lastActions.value = actions

            if (actions.isEmpty()) {
                logger.d("No more actions to execute")
                onStep?.invoke(step, "No more actions needed")
                _taskStatus.value = TaskStatus.COMPLETED
                return@withContext
            }

            // Map coordinates and execute
            _taskStatus.value = TaskStatus.EXECUTING
            onStep?.invoke(step, "Executing ${actions.size} actions...")

            val mappedActions = mapActionsCoordinates(actions)
            actionQueue.enqueueAll(mappedActions)

            // Wait for actions to complete
            waitForQueueCompletion()

            logger.d("Step $step complete")
            delay(500) // Brief pause between steps
        }

        _taskStatus.value = TaskStatus.COMPLETED
        logger.d("Task completed after $maxSteps steps")
    }

    private suspend fun captureScreenWithRetry(): android.graphics.Bitmap? {
        repeat(RetryConfig.MAX_RETRIES) { attempt ->
            try {
                val bitmap = withTimeout(TimeoutConfig.SCREEN_CAPTURE_TIMEOUT_MS) {
                    screenCapture.capture()
                }

                if (bitmap != null) {
                    return bitmap
                }
            } catch (e: Exception) {
                logger.w("Screen capture attempt ${attempt + 1} failed: ${e.message}")
            }

            if (attempt < RetryConfig.MAX_RETRIES - 1) {
                delay(RetryConfig.RETRY_DELAY_MS)
            }
        }

        return null
    }

    private fun mapActionsCoordinates(actions: List<UiAction>): List<UiAction> {
        return actions.map { action ->
            when (action) {
                is UiAction.Click -> {
                    val (realX, realY) = CoordinateMapper.mapToReal(
                        action.x,
                        action.y,
                        screenWidth,
                        screenHeight
                    )
                    UiAction.Click(realX, realY)
                }
                else -> action
            }
        }
    }

    private suspend fun waitForQueueCompletion() {
        while (actionQueue.isProcessing.value) {
            delay(100)
        }

        // Wait a bit more for any pending actions
        delay(500)
    }
}

/**
 * Task status enum
 */
enum class TaskStatus {
    IDLE,
    CAPTURING,
    PROCESSING,
    ANALYZING,
    EXECUTING,
    COMPLETED,
    ERROR
}

/**
 * Readiness status data class
 */
data class ReadinessStatus(
    val isReady: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val screenCaptureActive: Boolean,
    val apiKeyConfigured: Boolean
)
