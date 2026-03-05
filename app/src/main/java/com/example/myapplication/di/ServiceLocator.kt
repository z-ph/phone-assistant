package com.example.myapplication.di

import android.content.Context
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.preferences.AppPreferences
import com.example.myapplication.data.repository.ApiConfigRepository
import com.example.myapplication.data.repository.ChatRepository

/**
 * Simple Service Locator for dependency management
 * Provides singleton instances of repositories and services
 *
 * Note: Consider migrating to Hilt/Koin for more complex dependency injection needs
 */
object ServiceLocator {

    @Volatile
    private var database: AppDatabase? = null

    @Volatile
    private var chatRepository: ChatRepository? = null

    @Volatile
    private var apiConfigRepository: ApiConfigRepository? = null

    @Volatile
    private var appPreferences: AppPreferences? = null

    /**
     * Initialize the service locator with application context
     * Should be called in Application.onCreate()
     */
    fun init(context: Context) {
        if (database == null) {
            synchronized(this) {
                if (database == null) {
                    database = AppDatabase.getDatabase(context)
                }
            }
        }
    }

    /**
     * Get AppDatabase instance
     */
    fun getDatabase(): AppDatabase {
        return database ?: throw IllegalStateException("ServiceLocator not initialized. Call init() first.")
    }

    /**
     * Get ChatRepository instance (singleton)
     */
    fun getChatRepository(): ChatRepository {
        return chatRepository ?: synchronized(this) {
            chatRepository ?: ChatRepository.getInstance(getDatabase()).also { chatRepository = it }
        }
    }

    /**
     * Get ApiConfigRepository instance (singleton)
     */
    fun getApiConfigRepository(): ApiConfigRepository {
        return apiConfigRepository ?: synchronized(this) {
            apiConfigRepository ?: ApiConfigRepository(getDatabase().apiConfigDao()).also { apiConfigRepository = it }
        }
    }

    /**
     * Get AppPreferences instance (singleton)
     */
    fun getAppPreferences(context: Context): AppPreferences {
        return appPreferences ?: synchronized(this) {
            appPreferences ?: AppPreferences.getInstance(context).also { appPreferences = it }
        }
    }

    /**
     * Reset all instances (for testing purposes only)
     */
    fun reset() {
        synchronized(this) {
            database = null
            chatRepository = null
            apiConfigRepository = null
            appPreferences = null
        }
    }
}
