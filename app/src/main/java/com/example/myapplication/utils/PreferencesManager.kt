package com.example.myapplication.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.myapplication.config.ModelProvider

/**
 * Preferences Manager
 * Handles persistent storage of app configuration
 */
class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ai_automation_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_PROVIDER_ID = "provider_id"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // API Key
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    // Base URL (without endpoint path)
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    // Model ID
    var modelId: String
        get() = prefs.getString(KEY_MODEL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_ID, value).apply()

    // Provider ID
    var providerId: String
        get() = prefs.getString(KEY_PROVIDER_ID, ModelProvider.ZHIPU.id) ?: ModelProvider.ZHIPU.id
        set(value) = prefs.edit().putString(KEY_PROVIDER_ID, value).apply()

    // Get current provider with settings
    fun getCurrentProvider(): ModelProvider {
        val provider = ModelProvider.fromId(providerId)
        return provider
    }

    // Get current base URL (with provider default if empty)
    fun getCurrentBaseUrl(): String {
        val provider = getCurrentProvider()
        return baseUrl.ifEmpty { provider.defaultBaseUrl }
    }

    // Get current model ID (with provider default if empty)
    fun getCurrentModelId(): String {
        val provider = getCurrentProvider()
        return modelId.ifEmpty { provider.defaultModel }
    }

    // Get full API URL
    fun getFullApiUrl(): String {
        val provider = getCurrentProvider()
        val url = getCurrentBaseUrl()
        return provider.buildApiUrl(url)
    }

    // Set provider with defaults
    fun setProvider(provider: ModelProvider) {
        providerId = provider.id
        if (baseUrl.isEmpty()) {
            baseUrl = provider.defaultBaseUrl
        }
        if (modelId.isEmpty()) {
            modelId = provider.defaultModel
        }
    }

    // Check if API is configured
    fun isApiConfigured(): Boolean {
        return apiKey.isNotEmpty()
    }

    // Clear all settings
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
