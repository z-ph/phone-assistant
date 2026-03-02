package com.example.myapplication.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.MyApplication
import com.example.myapplication.agent.*
import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.ChatSession
import com.example.myapplication.data.repository.ChatRepository
import com.example.myapplication.engine.ReadinessStatus
import com.example.myapplication.engine.TaskEngine
import com.example.myapplication.ui.overlay.FloatingWindowService
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ChatViewModel"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository.getInstance(application)
    private val app = application as MyApplication
    private val logger = Logger(TAG)

    // Agent engine
    private val agentEngine = AgentEngine(application).apply {
        apiClient = app.zhipuApiClient
    }

    private val taskEngine = app.taskEngine

    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Sessions
    val sessions = repository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    val currentMessages: StateFlow<List<ChatMessage>> = _currentSessionId
        .filterNotNull()
        .flatMapLatest { sessionId -> repository.getMessagesForSession(sessionId) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val currentSession: StateFlow<ChatSession?> = _currentSessionId
        .filterNotNull()
        .map { sessionId -> repository.getSessionById(sessionId) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val agentState: StateFlow<AgentState> = agentEngine.state
    val isTaskRunning: StateFlow<Boolean> = agentEngine.state.map { it.isRunning }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var currentTaskJob: Job? = null
    private var floatingWindowJob: Job? = null

    init {
        viewModelScope.launch {
            val latestSession = repository.getLatestSession()
            if (latestSession != null) {
                _currentSessionId.value = latestSession.id
            } else {
                createNewSession()
            }
        }

        setupAgentCallbacks()
        setupFloatingWindowSync()
    }

    private fun setupFloatingWindowSync() {
        // Sync agent state to floating window
        floatingWindowJob = viewModelScope.launch {
            agentEngine.state.collect { state ->
                FloatingWindowService.getInstance()?.setAgentState(state)
            }
        }

        // Set up stop button callback
        FloatingWindowService.onStopButtonClick = {
            cancelTask()
        }
    }

    private fun setupAgentCallbacks() {
        // AI replies to user
        agentEngine.onReply = { message ->
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                viewModelScope.launch {
                    val aiMessage = ChatMessage.AiMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = message,
                        isSuccess = true
                    )
                    repository.addMessage(sessionId, aiMessage)
                }
            }
        }

        // AI thinking
        agentEngine.onThinking = { thinking ->
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                viewModelScope.launch {
                    val aiMessage = ChatMessage.AiMessage(
                        id = "thinking_${System.currentTimeMillis()}",
                        timestamp = System.currentTimeMillis(),
                        content = "💭 $thinking",
                        isSuccess = true
                    )
                    repository.addMessage(sessionId, aiMessage)
                }
            }
        }

        // Tool call
        agentEngine.onToolCall = { toolCall ->
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                viewModelScope.launch {
                    val toolMessage = ChatMessage.ToolCallMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        toolName = toolCall.name,
                        parameters = toolCall.parameters,
                        result = toolCall.rawMatch,
                        isSuccess = true
                    )
                    repository.addMessage(sessionId, toolMessage)
                }
            }
        }

        // Step complete
        agentEngine.onStepComplete = { step ->
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                viewModelScope.launch {
                    if (step.observation != null && step.action !is AgentAction.Reply) {
                        val obsMessage = ChatMessage.StatusMessage(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            status = "📋 ${step.observation}",
                            isRunning = false
                        )
                        repository.addMessage(sessionId, obsMessage)
                    }
                }
            }
        }
    }

    fun createNewSession(title: String = "新会话") {
        viewModelScope.launch {
            // Clear agent context when creating new session
            agentEngine.clearContext()
            val session = repository.createSession(title)
            _currentSessionId.value = session.id
        }
    }

    fun selectSession(sessionId: String) {
        // Clear agent context when switching sessions
        agentEngine.clearContext()
        _currentSessionId.value = sessionId
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                val latestSession = repository.getLatestSession()
                _currentSessionId.value = latestSession?.id
                if (latestSession == null) createNewSession()
            }
        }
    }

    fun updateSessionTitle(title: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch { repository.updateSessionTitle(sessionId, title) }
    }

    /**
     * Send message - AI decides what to do
     */
    fun sendMessage(content: String) {
        val sessionId = _currentSessionId.value ?: return

        viewModelScope.launch {
            // Save user message
            val userMessage = ChatMessage.UserMessage(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = content,
                attachedImageBase64 = null
            )
            repository.addMessage(sessionId, userMessage)

            // Update title if first message
            if (currentMessages.value.size <= 1) {
                val title = content.take(30).let { if (content.length > 30) "$it..." else it }
                repository.updateSessionTitle(sessionId, title)
            }

            // Let AI handle it - AI decides whether to chat or automate
            executeWithAgent(sessionId, content)
        }
    }

    private fun executeWithAgent(sessionId: String, instruction: String) {
        currentTaskJob = viewModelScope.launch {
            // Start floating window service
            FloatingWindowService.start(getApplication())
            FloatingWindowService.getInstance()?.clearLog()
            FloatingWindowService.getInstance()?.show()

            try {
                agentEngine.execute(instruction)  // Uses default maxSteps

                // Wait for completion
                agentEngine.state.filter { !it.isRunning }.first()

                val finalState = agentEngine.state.value

                // Check if last step was a Reply action (already sent via onReply callback)
                val lastStep = finalState.steps.lastOrNull()
                val wasReplySent = lastStep?.action is AgentAction.Reply

                // Check if this was a pure chat response (no tool calls, only Finish step)
                // In this case, onReply was already triggered in AgentEngine.processThinking
                val wasPureChatResponse = finalState.steps.size == 1 &&
                    finalState.steps.first().action is AgentAction.Finish &&
                    finalState.isFinished

                // Only add completion message if it wasn't already a reply or pure chat
                if (!wasReplySent && !wasPureChatResponse) {
                    val completeMessage = when {
                        finalState.isFinished -> {
                            ChatMessage.AiMessage(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = "✅ ${finalState.finalSummary ?: "完成"}",
                                isSuccess = true
                            )
                        }
                        finalState.error != null -> {
                            ChatMessage.AiMessage(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = "❌ ${finalState.error}",
                                isSuccess = false,
                                errorMessage = finalState.error
                            )
                        }
                        else -> {
                            ChatMessage.AiMessage(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = "⏹️ 任务已停止",
                                isSuccess = true
                            )
                        }
                    }

                    repository.addMessage(sessionId, completeMessage)
                }

                // Hide floating window after a delay
                kotlinx.coroutines.delay(2000)
                FloatingWindowService.getInstance()?.hide()

            } catch (e: Exception) {
                logger.e("Execution error: ${e.message}", e)
                val errorMessage = ChatMessage.AiMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "❌ 执行失败: ${e.message}",
                    isSuccess = false,
                    errorMessage = e.message
                )
                repository.addMessage(sessionId, errorMessage)

                FloatingWindowService.getInstance()?.hide()
            }
        }
    }

    fun cancelTask() {
        logger.d("cancelTask called")
        // 首先取消 agentEngine 的 job
        agentEngine.cancel()
        // 然后取消 ViewModel 的协程 job
        currentTaskJob?.cancel()
        currentTaskJob = null

        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val statusMessage = ChatMessage.StatusMessage(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                status = "⏹️ 任务已取消",
                isRunning = false
            )
            repository.addMessage(sessionId, statusMessage)
        }
    }

    fun clearCurrentSession() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch { repository.clearSessionMessages(sessionId) }
    }

    fun getReadinessStatus(): ReadinessStatus = taskEngine.getReadinessStatus()

    fun getToolManager(): ToolManager = agentEngine.getToolManager()

    override fun onCleared() {
        super.onCleared()
        currentTaskJob?.cancel()
        floatingWindowJob?.cancel()
        agentEngine.cancel()
        FloatingWindowService.stop(getApplication())
    }
}

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputText: String = "",
    val showSessionDrawer: Boolean = false
)
