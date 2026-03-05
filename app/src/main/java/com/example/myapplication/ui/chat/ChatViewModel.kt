package com.example.myapplication.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.myapplication.MyApplication
import com.example.myapplication.agent.LangChainAgentEngine
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.ChatSession
import com.example.myapplication.data.repository.ChatRepository
import com.example.myapplication.ui.overlay.FloatingWindowService
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val KEY_CURRENT_SESSION_ID = "current_session_id"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = ChatRepository.getInstance(database)
    private val app = application as MyApplication
    private val logger = Logger(TAG)

    private val langChainAgentEngine = app.langChainAgentEngine

    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Sessions
    val sessions = repository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _currentSessionId = MutableStateFlow<String?>(
        savedStateHandle.get<String>(KEY_CURRENT_SESSION_ID)
    )
    val currentSessionId: StateFlow<String?> = _currentSessionId

    val currentMessages: StateFlow<List<ChatMessage>> = _currentSessionId
        .filterNotNull()
        .flatMapLatest { sessionId -> repository.getMessagesForSession(sessionId) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val currentSession: StateFlow<ChatSession?> = _currentSessionId
        .filterNotNull()
        .map { sessionId -> repository.getSessionById(sessionId) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val agentState: StateFlow<LangChainAgentEngine.AgentState> = langChainAgentEngine.state
    val isTaskRunning: StateFlow<Boolean> = langChainAgentEngine.state.map { 
        it.state == LangChainAgentEngine.AgentStateType.RUNNING 
    }
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
        floatingWindowJob = viewModelScope.launch {
            langChainAgentEngine.state.collect { state ->
                FloatingWindowService.getInstance()?.setLangChainAgentState(state)
            }
        }

        FloatingWindowService.onStopButtonClick = {
            cancelTask()
        }
    }

    private fun setupAgentCallbacks() {
        // LangChainAgentEngine handles callbacks through the execute method
        // Messages are added directly in executeWithAgent
    }

    fun createNewSession(title: String = "新会话") {
        viewModelScope.launch {
            langChainAgentEngine.clearMemory()
            val session = repository.createSession(title)
            _currentSessionId.value = session.id
            savedStateHandle[KEY_CURRENT_SESSION_ID] = session.id
        }
    }

    fun selectSession(sessionId: String) {
        langChainAgentEngine.clearMemory()
        _currentSessionId.value = sessionId
        savedStateHandle[KEY_CURRENT_SESSION_ID] = sessionId
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                val latestSession = repository.getLatestSession()
                _currentSessionId.value = latestSession?.id
                savedStateHandle[KEY_CURRENT_SESSION_ID] = latestSession?.id
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
            FloatingWindowService.start(getApplication())
            FloatingWindowService.getInstance()?.clearLog()
            FloatingWindowService.getInstance()?.show()

            try {
                langChainAgentEngine.execute(instruction) { result ->
                    viewModelScope.launch {
                        val message = when {
                            result.success && !result.isReply -> {
                                ChatMessage.AiMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    content = result.message,
                                    isSuccess = true
                                )
                            }
                            result.success && result.isReply -> {
                                ChatMessage.AiMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    content = result.message,
                                    isSuccess = true
                                )
                            }
                            else -> {
                                ChatMessage.AiMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    content = "❌ ${result.message}",
                                    isSuccess = false,
                                    errorMessage = result.message
                                )
                            }
                        }
                        repository.addMessage(sessionId, message)
                    }
                }

                agentState.filter { it.state != LangChainAgentEngine.AgentStateType.RUNNING }.first()

                val finalState = langChainAgentEngine.state.value

                when (finalState.state) {
                    LangChainAgentEngine.AgentStateType.COMPLETED -> {
                        val completeMessage = ChatMessage.AiMessage(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "✅ ${finalState.result ?: "完成"}",
                            isSuccess = true
                        )
                        repository.addMessage(sessionId, completeMessage)
                    }
                    LangChainAgentEngine.AgentStateType.ERROR -> {
                        val errorMessage = ChatMessage.AiMessage(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "❌ ${finalState.error ?: "未知错误"}",
                            isSuccess = false,
                            errorMessage = finalState.error
                        )
                        repository.addMessage(sessionId, errorMessage)
                    }
                    LangChainAgentEngine.AgentStateType.CANCELLED -> {
                        val cancelMessage = ChatMessage.AiMessage(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "⏹️ 任务已取消",
                            isSuccess = true
                        )
                        repository.addMessage(sessionId, cancelMessage)
                    }
                    else -> {}
                }

                kotlinx.coroutines.delay(2000)
                FloatingWindowService.getInstance()?.hide()

            } catch (e: Exception) {
                logger.e("Execution error: ${e.message}", e)
                val errorMessage = ChatMessage.AiMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "❌ 执行失败：${e.message}",
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
        langChainAgentEngine.cancel()
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

    override fun onCleared() {
        super.onCleared()
        currentTaskJob?.cancel()
        floatingWindowJob?.cancel()
        langChainAgentEngine.cancel()
        FloatingWindowService.stop(getApplication())
    }
}

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputText: String = "",
    val showSessionDrawer: Boolean = false
)
