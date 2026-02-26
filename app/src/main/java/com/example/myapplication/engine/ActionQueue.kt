package com.example.myapplication.engine

import com.example.myapplication.accessibility.ActionExecutor
import com.example.myapplication.accessibility.ActionResult
import com.example.myapplication.api.model.UiAction
import com.example.myapplication.config.AppConfig.ActionDelays as Delays
import com.example.myapplication.config.AppConfig.Retry as RetryConfig
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
 * Action Queue
 * Manages queued actions with async execution and proper sequencing
 */
class ActionQueue {

    companion object {
        private const val TAG = "ActionQueue"
    }

    private val logger = Logger(TAG)
    private val executor = ActionExecutor()
    private val scope = CoroutineScope(Dispatchers.Main)

    // Queue state
    private val _queue = MutableStateFlow<List<QueuedAction>>(emptyList())
    val queue: StateFlow<List<QueuedAction>> = _queue.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentAction = MutableStateFlow<QueuedAction?>(null)
    val currentAction: StateFlow<QueuedAction?> = _currentAction.asStateFlow()

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount.asStateFlow()

    private val _failedCount = MutableStateFlow(0)
    val failedCount: StateFlow<Int> = _failedCount.asStateFlow()

    // Processing job
    private var processingJob: Job? = null

    /**
     * Add a single action to the queue
     */
    fun enqueue(action: UiAction, priority: ActionPriority = ActionPriority.NORMAL): String {
        val id = generateActionId()
        val queuedAction = QueuedAction(
            id = id,
            action = action,
            priority = priority,
            status = ActionStatus.PENDING
        )

        val currentQueue = _queue.value.toMutableList()
        if (priority == ActionPriority.HIGH) {
            // Insert high priority actions at the front
            val insertIndex = currentQueue.indexOfFirst { it.priority != ActionPriority.HIGH }
            if (insertIndex >= 0) {
                currentQueue.add(insertIndex, queuedAction)
            } else {
                currentQueue.add(queuedAction)
            }
        } else {
            currentQueue.add(queuedAction)
        }

        _queue.value = currentQueue
        logger.d("Enqueued action $id: ${action.javaClass.simpleName}")

        // Start processing if not already running
        if (!isProcessing.value) {
            startProcessing()
        }

        return id
    }

    /**
     * Add multiple actions to the queue
     */
    fun enqueueAll(actions: List<UiAction>, priority: ActionPriority = ActionPriority.NORMAL): List<String> {
        return actions.map { action ->
            enqueue(action, priority)
        }
    }

    /**
     * Remove an action from the queue
     */
    fun remove(actionId: String): Boolean {
        val currentQueue = _queue.value.toMutableList()
        val removed = currentQueue.removeIf { it.id == actionId }

        if (removed) {
            _queue.value = currentQueue
            logger.d("Removed action $actionId")
        }

        return removed
    }

    /**
     * Clear all pending actions
     */
    fun clear() {
        _queue.value = emptyList()
        _completedCount.value = 0
        _failedCount.value = 0
        logger.d("Queue cleared")
    }

    /**
     * Stop processing the queue
     */
    fun stop() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false
        _currentAction.value = null
        logger.d("Queue processing stopped")
    }

    /**
     * Get queue statistics
     */
    fun getStats(): QueueStats {
        val queue = _queue.value
        return QueueStats(
            total = queue.size,
            pending = queue.count { it.status == ActionStatus.PENDING },
            running = queue.count { it.status == ActionStatus.RUNNING },
            completed = queue.count { it.status == ActionStatus.COMPLETED },
            failed = queue.count { it.status == ActionStatus.FAILED },
            completedCount = _completedCount.value,
            failedCount = _failedCount.value
        )
    }

    // Private methods

    private fun startProcessing() {
        if (_isProcessing.value) {
            logger.d("Already processing queue")
            return
        }

        _isProcessing.value = true
        logger.d("Starting queue processing")

        processingJob = scope.launch {
            while (_isProcessing.value) {
                val pendingActions = _queue.value.filter { it.status == ActionStatus.PENDING }

                if (pendingActions.isEmpty()) {
                    // Check if there are any actions left at all
                    if (_queue.value.isEmpty() || _queue.value.all { it.status == ActionStatus.COMPLETED || it.status == ActionStatus.FAILED }) {
                        logger.d("Queue processing complete")
                        stop()
                        break
                    }

                    delay(Delays.QUEUE_PROCESS_INTERVAL_MS)
                    continue
                }

                // Get next action (high priority first)
                val nextAction = pendingActions.minByOrNull { it.priority.ordinal }

                if (nextAction != null) {
                    executeAction(nextAction)
                }

                delay(Delays.QUEUE_PROCESS_INTERVAL_MS)
            }
        }
    }

    private suspend fun executeAction(queuedAction: QueuedAction) {
        // Update status to running
        updateActionStatus(queuedAction.id, ActionStatus.RUNNING)
        _currentAction.value = queuedAction

        val result = withContext(Dispatchers.IO) {
            try {
                withTimeout(Delays.ACTION_TIMEOUT_MS) {
                    executor.executeAction(queuedAction.action)
                }
            } catch (e: Exception) {
                logger.e("Action execution timeout or error: ${e.message}")
                false
            }
        }

        if (result) {
            updateActionStatus(queuedAction.id, ActionStatus.COMPLETED)
            _completedCount.value++
            logger.d("Action ${queuedAction.id} completed successfully")

            // Remove from queue after a delay
            scope.launch {
                delay(1000)
                remove(queuedAction.id)
            }
        } else {
            // Retry logic
            val currentAttempts = queuedAction.attempts + 1

            if (currentAttempts <= RetryConfig.DEFAULT_RETRY_ATTEMPTS) {
                updateAction(queuedAction.copy(attempts = currentAttempts))
                updateActionStatus(queuedAction.id, ActionStatus.PENDING)
                logger.d("Action ${queuedAction.id} failed, retrying ($currentAttempts/${RetryConfig.DEFAULT_RETRY_ATTEMPTS})")
                delay(Delays.LONG_DELAY_MS)
            } else {
                updateActionStatus(queuedAction.id, ActionStatus.FAILED)
                _failedCount.value++
                logger.w("Action ${queuedAction.id} failed after $currentAttempts attempts")
            }
        }

        _currentAction.value = null
    }

    private fun updateActionStatus(actionId: String, status: ActionStatus) {
        val currentQueue = _queue.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == actionId }

        if (index >= 0) {
            currentQueue[index] = currentQueue[index].copy(status = status)
            _queue.value = currentQueue
        }
    }

    private fun updateAction(queuedAction: QueuedAction) {
        val currentQueue = _queue.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == queuedAction.id }

        if (index >= 0) {
            currentQueue[index] = queuedAction
            _queue.value = currentQueue
        }
    }

    private fun generateActionId(): String {
        return "action_${System.currentTimeMillis()}_${(0..999).random()}"
    }
}

/**
 * Queued action with metadata
 */
data class QueuedAction(
    val id: String,
    val action: UiAction,
    val priority: ActionPriority = ActionPriority.NORMAL,
    val status: ActionStatus = ActionStatus.PENDING,
    val attempts: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Action priority
 */
enum class ActionPriority {
    HIGH,
    NORMAL,
    LOW
}

/**
 * Action status
 */
enum class ActionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Queue statistics
 */
data class QueueStats(
    val total: Int,
    val pending: Int,
    val running: Int,
    val completed: Int,
    val failed: Int,
    val completedCount: Int,
    val failedCount: Int
)
