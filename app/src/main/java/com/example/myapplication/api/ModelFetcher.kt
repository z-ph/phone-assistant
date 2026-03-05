package com.example.myapplication.api

import com.example.myapplication.config.ModelProvider
import com.example.myapplication.utils.Logger
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Result of fetching models
 */
data class ModelFetchResult(
    val isSuccess: Boolean,
    val models: List<ModelInfo> = emptyList(),
    val error: String? = null
)

/**
 * Model information
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val ownedBy: String? = null
)

/**
 * Fetches available models from API providers
 */
class ModelFetcher {
    private val logger = Logger("ModelFetcher")
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch models from a provider
     */
    suspend fun fetchModels(
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String
    ): ModelFetchResult = withContext(Dispatchers.IO) {
        try {
            val actualBaseUrl = baseUrl.ifEmpty { provider.defaultBaseUrl }

            when (provider.id) {
                ModelProvider.ZHIPU.id -> fetchZhipuModels(actualBaseUrl, apiKey)
                ModelProvider.QWEN.id -> fetchQwenModels(actualBaseUrl, apiKey)
                else -> fetchOpenAICompatibleModels(actualBaseUrl, apiKey)
            }
        } catch (e: Exception) {
            logger.e("Failed to fetch models: ${e.message}", e)
            ModelFetchResult(
                isSuccess = false,
                error = "获取模型列表失败：${e.message}"
            )
        }
    }

    /**
     * Fetch models from OpenAI-compatible API (OpenAI, DeepSeek, custom)
     */
    private fun fetchOpenAICompatibleModels(baseUrl: String, apiKey: String): ModelFetchResult {
        return try {
            val cleanBaseUrl = baseUrl.trimEnd('/')
            val modelsUrl = "$cleanBaseUrl/v1/models"

            logger.d("Fetching models from: $modelsUrl")

            val request = Request.Builder()
                .url(modelsUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                return ModelFetchResult(
                    isSuccess = false,
                    error = "HTTP ${response.code}: ${response.message}"
                )
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val dataArray = json.getAsJsonArray("data")

            val models = dataArray.mapNotNull { element ->
                try {
                    val modelObj = element.asJsonObject
                    val id = modelObj.get("id")?.asString ?: return@mapNotNull null
                    val name = modelObj.get("id")?.asString ?: id
                    val ownedBy = modelObj.get("owned_by")?.asString
                    ModelInfo(id, name, ownedBy)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.id }

            logger.d("Fetched ${models.size} models")
            ModelFetchResult(isSuccess = true, models = models)
        } catch (e: Exception) {
            logger.e("OpenAI compatible fetch error: ${e.message}", e)
            ModelFetchResult(
                isSuccess = false,
                error = "获取模型失败: ${e.message}"
            )
        }
    }

    /**
     * Fetch models from Zhipu AI
     * Note: Zhipu uses a different API structure
     */
    private fun fetchZhipuModels(baseUrl: String, apiKey: String): ModelFetchResult {
        return try {
            // Zhipu doesn't have a /v1/models endpoint, return known models
            val knownModels = listOf(
                ModelInfo("glm-4v", "GLM-4V (Vision)", "zhipu"),
                ModelInfo("glm-4v-plus", "GLM-4V Plus (Vision)", "zhipu"),
                ModelInfo("glm-4-plus", "GLM-4 Plus", "zhipu"),
                ModelInfo("glm-4-0520", "GLM-4 0520", "zhipu"),
                ModelInfo("glm-4-air", "GLM-4 Air", "zhipu"),
                ModelInfo("glm-4-airx", "GLM-4 AirX", "zhipu"),
                ModelInfo("glm-4-flash", "GLM-4 Flash", "zhipu"),
                ModelInfo("glm-4-long", "GLM-4 Long", "zhipu"),
                ModelInfo("glm-3-turbo", "GLM-3 Turbo", "zhipu")
            )
            ModelFetchResult(isSuccess = true, models = knownModels)
        } catch (e: Exception) {
            logger.e("Zhipu fetch error: ${e.message}", e)
            ModelFetchResult(
                isSuccess = false,
                error = "获取智谱模型失败: ${e.message}"
            )
        }
    }

    /**
     * Fetch models from Qwen (Tongyi Qianwen)
     * Note: Qwen uses a different API structure
     */
    private fun fetchQwenModels(baseUrl: String, apiKey: String): ModelFetchResult {
        return try {
            // Qwen doesn't have a standard models endpoint, return known models
            val knownModels = listOf(
                ModelInfo("qwen-vl-max", "Qwen VL Max (Vision)", "alibaba"),
                ModelInfo("qwen-vl-plus", "Qwen VL Plus (Vision)", "alibaba"),
                ModelInfo("qwen-vl-ocr", "Qwen VL OCR", "alibaba"),
                ModelInfo("qwen-max", "Qwen Max", "alibaba"),
                ModelInfo("qwen-max-longcontext", "Qwen Max Long Context", "alibaba"),
                ModelInfo("qwen-plus", "Qwen Plus", "alibaba"),
                ModelInfo("qwen-turbo", "Qwen Turbo", "alibaba"),
                ModelInfo("qwen-long", "Qwen Long", "alibaba")
            )
            ModelFetchResult(isSuccess = true, models = knownModels)
        } catch (e: Exception) {
            logger.e("Qwen fetch error: ${e.message}", e)
            ModelFetchResult(
                isSuccess = false,
                error = "获取通义千问模型失败: ${e.message}"
            )
        }
    }
}
