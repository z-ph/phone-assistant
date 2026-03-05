package com.example.myapplication.data.repository

import com.example.myapplication.config.ModelProvider
import com.example.myapplication.data.local.dao.ApiConfigDao
import com.example.myapplication.data.local.entities.ApiConfigEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository for API configuration management
 */
class ApiConfigRepository(private val dao: ApiConfigDao) {

    val allConfigs: Flow<List<ApiConfigEntity>> = dao.getAllConfigs()

    val activeConfigFlow: Flow<ApiConfigEntity?> = dao.getActiveConfigFlow()

    suspend fun getActiveConfig(): ApiConfigEntity? = dao.getActiveConfig()

    suspend fun getConfigById(configId: String): ApiConfigEntity? = dao.getConfigById(configId)

    suspend fun createConfig(
        name: String,
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String
    ): Result<ApiConfigEntity> {
        return try {
            val configCount = dao.getConfigCount()
            val isActive = configCount == 0 // First config is automatically active

            val config = ApiConfigEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                providerId = provider.id,
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { provider.defaultBaseUrl },
                modelId = modelId.ifEmpty { provider.defaultModel },
                isActive = isActive,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            dao.insertConfig(config)
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConfig(
        configId: String,
        name: String,
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String
    ): Result<Unit> {
        return try {
            val existing = dao.getConfigById(configId) ?: return Result.failure(Exception("Config not found"))

            val updated = existing.copy(
                name = name,
                providerId = provider.id,
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { provider.defaultBaseUrl },
                modelId = modelId.ifEmpty { provider.defaultModel },
                updatedAt = System.currentTimeMillis()
            )

            dao.updateConfig(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteConfig(configId: String): Result<Unit> {
        return try {
            val config = dao.getConfigById(configId) ?: return Result.failure(Exception("Config not found"))
            val wasActive = config.isActive

            dao.deleteConfigById(configId)

            // If deleted config was active, activate another one
            if (wasActive) {
                val remainingConfigs = dao.getAllConfigs()
                // Note: We can't directly get first item from Flow here
                // The UI should handle setting a new active config
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setActiveConfig(configId: String): Result<Unit> {
        return try {
            dao.setActiveConfig(configId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
