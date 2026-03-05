package com.example.myapplication.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.MyApplication
import com.example.myapplication.api.ModelFetcher
import com.example.myapplication.api.ModelFetchResult
import com.example.myapplication.api.ModelInfo
import com.example.myapplication.api.ZhipuApiClient
import com.example.myapplication.config.ModelProvider
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.ApiConfigDao
import com.example.myapplication.data.local.entities.ApiConfigEntity
import com.example.myapplication.utils.PreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for API configuration management
 */
class ApiConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyApplication
    private val apiConfigDao: ApiConfigDao = AppDatabase.getDatabase(application).apiConfigDao()
    private val modelFetcher = ModelFetcher()
    private val prefs = PreferencesManager.getInstance(application)

    // UI State
    private val _uiState = MutableStateFlow(ApiConfigUiState())
    val uiState: StateFlow<ApiConfigUiState> = _uiState.asStateFlow()

    // All configurations
    val configs: StateFlow<List<ApiConfigEntity>> = apiConfigDao.getAllConfigs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Active configuration
    val activeConfig: StateFlow<ApiConfigEntity?> = apiConfigDao.getActiveConfigFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Available models for current provider
    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    // Test result
    private val _testResult = MutableStateFlow<TestConnectionResult?>(null)
    val testResult: StateFlow<TestConnectionResult?> = _testResult.asStateFlow()

    init {
        // Check if migration from old prefs is needed
        viewModelScope.launch {
            val configCount = apiConfigDao.getConfigCount()
            if (configCount == 0 && prefs.isApiConfigured()) {
                migrateFromOldPrefs()
            }
        }
    }

    /**
     * Migrate configuration from old SharedPreferences to new database
     */
    private suspend fun migrateFromOldPrefs() {
        val provider = prefs.getCurrentProvider()
        val config = ApiConfigEntity(
            id = UUID.randomUUID().toString(),
            name = provider.name,
            providerId = provider.id,
            apiKey = prefs.apiKey,
            baseUrl = prefs.baseUrl,
            modelId = prefs.modelId,
            isActive = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        apiConfigDao.insertConfig(config)
    }

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
            val now = System.currentTimeMillis()
            val isFirst = apiConfigDao.getConfigCount() == 0

            val config = ApiConfigEntity(
                id = UUID.randomUUID().toString(),
                name = name.ifEmpty { provider.displayName },
                providerId = provider.id,
                apiKey = apiKey,
                baseUrl = baseUrl,
                modelId = modelId.ifEmpty { provider.defaultModel },
                isActive = isFirst,
                createdAt = now,
                updatedAt = now
            )
            apiConfigDao.insertConfig(config)
            _uiState.update { it.copy(showEditDialog = false, editingConfig = null) }
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
            val existing = apiConfigDao.getConfigById(configId) ?: return@launch
            val updated = existing.copy(
                name = name,
                providerId = provider.id,
                apiKey = apiKey,
                baseUrl = baseUrl,
                modelId = modelId.ifEmpty { provider.defaultModel },
                updatedAt = System.currentTimeMillis()
            )
            apiConfigDao.updateConfig(updated)
            _uiState.update { it.copy(showEditDialog = false, editingConfig = null) }
        }
    }

    /**
     * Delete a configuration
     */
    fun deleteConfig(configId: String) {
        viewModelScope.launch {
            val config = apiConfigDao.getConfigById(configId) ?: return@launch
            val wasActive = config.isActive

            apiConfigDao.deleteConfigById(configId)

            // If deleted config was active, activate another one
            if (wasActive) {
                val remaining = apiConfigDao.getAllConfigs().first()
                if (remaining.isNotEmpty()) {
                    setActiveConfig(remaining.first().id)
                }
            }
            _uiState.update { it.copy(showDeleteConfirm = false, configToDelete = null) }
        }
    }

    /**
     * Set a configuration as active
     */
    fun setActiveConfig(configId: String) {
        viewModelScope.launch {
            apiConfigDao.setActiveConfig(configId)

            // Load config and update ZhipuApiClient (which updates PreferencesManager)
            val config = apiConfigDao.getConfigById(configId) ?: return@launch
            app.zhipuApiClient.loadConfig(config)
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
