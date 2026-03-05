package com.example.myapplication.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.MyApplication
import com.example.myapplication.api.ModelFetcher
import com.example.myapplication.api.ModelInfo
import com.example.myapplication.config.ModelProvider
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entities.ApiConfigEntity
import com.example.myapplication.data.repository.ApiConfigRepository
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for API configuration management
 */
class ApiConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyApplication
    private val database = AppDatabase.getDatabase(application)
    private val repository = ApiConfigRepository(database.apiConfigDao())
    private val modelFetcher = ModelFetcher()
    private val logger = Logger("ApiConfigViewModel")

    // UI State
    private val _uiState = MutableStateFlow(ApiConfigUiState())
    val uiState: StateFlow<ApiConfigUiState> = _uiState.asStateFlow()

    // All configurations
    val configs: StateFlow<List<ApiConfigEntity>> = repository.allConfigs
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Active configuration
    val activeConfig: StateFlow<ApiConfigEntity?> = repository.activeConfigFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Available models for current provider
    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    // Test result
    private val _testResult = MutableStateFlow<TestConnectionResult?>(null)
    val testResult: StateFlow<TestConnectionResult?> = _testResult.asStateFlow()

    /**
     * Create a new configuration
     */
    fun createConfig(
        name: String,
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String
    ) {
        viewModelScope.launch {
            val result = repository.createConfig(name, provider, apiKey, baseUrl, modelId)
            
            result.onSuccess { config ->
                _uiState.update { it.copy(showEditDialog = false, editingConfig = null) }
                
                if (config.isActive) {
                    app.langChainAgentEngine.reconfigure()
                }
            }.onFailure { exception ->
                logger.e("创建配置失败：${exception.message}")
            }
        }
    }

    /**
     * Update an existing configuration
     */
    fun updateConfig(
        configId: String,
        name: String,
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String
    ) {
        viewModelScope.launch {
            val existing = repository.getConfigById(configId) ?: return@launch
            val wasActive = existing.isActive
            
            val result = repository.updateConfig(configId, name, provider, apiKey, baseUrl, modelId)
            
            result.onSuccess {
                _uiState.update { it.copy(showEditDialog = false, editingConfig = null) }
                
                if (wasActive) {
                    app.langChainAgentEngine.reconfigure()
                }
            }.onFailure { exception ->
                logger.e("更新配置失败：${exception.message}")
            }
        }
    }

    /**
     * Delete a configuration
     */
    fun deleteConfig(configId: String) {
        viewModelScope.launch {
            val result = repository.deleteConfig(configId)
            
            result.onSuccess {
                _uiState.update { it.copy(showDeleteConfirm = false, configToDelete = null) }
            }.onFailure { exception ->
                logger.e("删除配置失败：${exception.message}")
            }
        }
    }

    /**
     * Set a configuration as active
     */
    fun setActiveConfig(configId: String) {
        viewModelScope.launch {
            val result = repository.setActiveConfig(configId)
            
            result.onSuccess {
                val initResult = app.langChainAgentEngine.reconfigure()
                if (initResult.isSuccess) {
                    logger.d("LangChainAgentEngine reinitialized successfully")
                } else {
                    logger.w("LangChainAgentEngine reinitialize failed: ${initResult.exceptionOrNull()?.message}")
                }
            }.onFailure { exception ->
                logger.e("设置活跃配置失败：${exception.message}")
            }
        }
    }

    /**
     * Fetch available models from the API
     */
    fun fetchModels(provider: ModelProvider, apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true) }

            val result = modelFetcher.fetchModels(provider, apiKey, baseUrl)

            if (result.isSuccess) {
                _availableModels.value = result.models
                _uiState.update { it.copy(isLoadingModels = false, modelsError = null) }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingModels = false,
                        modelsError = result.error ?: "获取模型列表失败"
                    )
                }
            }
        }
    }

    /**
     * Test API connection
     */
    fun testConnection(
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true) }
            _testResult.value = null

            val result = testApiConnection(provider, apiKey, baseUrl, modelId)

            // Convert TestResult to TestConnectionResult
            _testResult.value = TestConnectionResult(
                isSuccess = result.isSuccess,
                message = result.message,
                details = result.rawResponse
            )
            _uiState.update { it.copy(isTesting = false) }
        }
    }

    /**
     * Clear test result
     */
    fun clearTestResult() {
        _testResult.value = null
    }

    /**
     * Test API connection
     */
    private suspend fun testApiConnection(
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String
    ): TestResult {
        return try {
            val result = modelFetcher.fetchModels(provider, apiKey, baseUrl)
            if (result.isSuccess) {
                TestResult(
                    isSuccess = true,
                    message = "连接成功！获取到 ${result.models.size} 个模型",
                    rawResponse = "Models: ${result.models.joinToString { it.name }}"
                )
            } else {
                TestResult(
                    isSuccess = false,
                    message = result.error ?: "连接失败",
                    rawResponse = null
                )
            }
        } catch (e: Exception) {
            TestResult(
                isSuccess = false,
                message = "测试失败：${e.message}",
                rawResponse = null
            )
        }
    }

    /**
     * Show edit dialog for creating/editing a config
     */
    fun showEditDialog(config: ApiConfigEntity? = null) {
        _uiState.update {
            it.copy(
                showEditDialog = true,
                editingConfig = config
            )
        }
    }

    /**
     * Hide edit dialog
     */
    fun hideEditDialog() {
        _uiState.update {
            it.copy(
                showEditDialog = false,
                editingConfig = null
            )
        }
    }

    /**
     * Show delete confirmation dialog
     */
    fun showDeleteConfirm(config: ApiConfigEntity) {
        _uiState.update {
            it.copy(
                showDeleteConfirm = true,
                configToDelete = config
            )
        }
    }

    /**
     * Hide delete confirmation dialog
     */
    fun hideDeleteConfirm() {
        _uiState.update {
            it.copy(
                showDeleteConfirm = false,
                configToDelete = null
            )
        }
    }

    /**
     * Clear available models
     */
    fun clearModels() {
        _availableModels.value = emptyList()
        _uiState.update { it.copy(modelsError = null) }
    }
}

/**
 * UI State for API configuration screen
 */
data class ApiConfigUiState(
    val showEditDialog: Boolean = false,
    val editingConfig: ApiConfigEntity? = null,
    val showDeleteConfirm: Boolean = false,
    val configToDelete: ApiConfigEntity? = null,
    val isLoadingModels: Boolean = false,
    val modelsError: String? = null,
    val isTesting: Boolean = false
)

/**
 * Result of testing API connection
 */
data class TestConnectionResult(
    val isSuccess: Boolean,
    val message: String,
    val details: String? = null
)

/**
 * Internal test result data class
 */
private data class TestResult(
    val isSuccess: Boolean,
    val message: String,
    val rawResponse: String? = null
)
