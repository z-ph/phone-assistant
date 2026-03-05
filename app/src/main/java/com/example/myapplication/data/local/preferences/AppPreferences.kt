package com.example.myapplication.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Preferences Manager for simple app settings
 * NOTE: Business data (API configs, chat sessions) should use Room Database
 * This class only handles simple preferences like theme, language, etc.
 */
class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "app_preferences"

        // Keys
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_LAST_SESSION_ID = "last_session_id"

        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    // Theme Mode (0: System, 1: Light, 2: Dark)
    private val _themeMode = MutableStateFlow(getInt(KEY_THEME_MODE, 0))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    fun setThemeMode(mode: Int) {
        putInt(KEY_THEME_MODE, mode)
        _themeMode.value = mode
    }

    // Language
    private val _language = MutableStateFlow(getString(KEY_LANGUAGE, "system") ?: "system")
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(lang: String) {
        putString(KEY_LANGUAGE, lang)
        _language.value = lang
    }

    // First Launch
    var isFirstLaunch: Boolean
        get() = getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = putBoolean(KEY_FIRST_LAUNCH, value)

    // Last Session ID (for restoring session)
    var lastSessionId: String?
        get() = getString(KEY_LAST_SESSION_ID, null)
        set(value) = putString(KEY_LAST_SESSION_ID, value)

    // Helper functions
    private fun getString(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    private fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    private fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    private fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    private fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    // Clear all preferences (use with caution)
    fun clearAll() {
        prefs.edit().clear().apply()
        _themeMode.value = 0
        _language.value = "system"
    }
}
