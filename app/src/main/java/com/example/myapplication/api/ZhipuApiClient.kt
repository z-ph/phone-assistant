package com.example.myapplication.api

import android.content.Context
import com.example.myapplication.api.model.*
import com.example.myapplication.config.AppConfig.Timeouts as TimeoutConfig
import com.example.myapplication.data.local.entities.ApiConfigEntity
import com.example.myapplication.config.ModelProvider
import com.example.myapplication.utils.Logger
import com.example.myapplication.utils.PreferencesManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI API Client
 * Manages HTTP communication with AI vision model APIs
 */
class ZhipuApiClient(context: Context) {

    companion object {
        private const val TAG = "ZhipuApiClient"
        private const val FAILURE_THRESHOLD = 5
        private const val RESET_TIMEOUT_MS = 60000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 1000L
    }

    private val logger = Logger(TAG)
    private val prefs = PreferencesManager.getInstance(context)
    private val context = context.applicationContext
    private val circuitBreaker = CircuitBreaker(
        failureThreshold = FAILURE_THRESHOLD,
        resetTimeoutMs = RESET_TIMEOUT_MS,
        tag = TAG
    )

    // API configuration - all properties delegate directly to PreferencesManager
    // This ensures all parts of the app see the same values
    var apiKey: String
        get() = prefs.apiKey
        set(value) {
            prefs.apiKey = value
            _service = null  // Reset service to pick up new auth
        }

    // Base URL (without endpoint)
    var baseUrl: String
        get() = prefs.baseUrl.ifEmpty { prefs.getCurrentProvider().defaultBaseUrl }
        set(value) {
            prefs.baseUrl = value
            _service = null
        }

    // Model ID
    var modelId: String
        get() = prefs.modelId.ifEmpty { prefs.getCurrentProvider().defaultModel }
        set(value) {
            prefs.modelId = value
        }

    // Provider ID
    var providerId: String
        get() = prefs.providerId
        set(value) {
            prefs.providerId = value
            _service = null
        }

    // Current provider
    val currentProvider: ModelProvider
        get() = prefs.getCurrentProvider()

    // Full API URL (computed from base URL + provider endpoint format)
    val apiUrl: String
        get() = currentProvider.buildApiUrl(baseUrl)

    // For backward compatibility - getter returns apiUrl, setter parses and saves
    var apiUrlLegacy: String
        get() = apiUrl
        set(value) {
            // Try to extract base URL from full URL
            val provider = currentProvider
            val endpoint = provider.endpointFormat
            if (value.contains(endpoint)) {
                baseUrl = value.substringBefore(endpoint)
            } else {
                baseUrl = value
            }
        }

    private var _service: ZhipuApiService? = null
    private val service: ZhipuApiService
        get() = _service ?: createService().also { _service = it }

    private val gson = Gson()

    // Check if API is configured
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    // Save configuration (unified entry point)
    fun saveConfig(provider: ModelProvider, key: String, url: String, model: String) {
        prefs.providerId = provider.id
        prefs.apiKey = key
        prefs.baseUrl = url
        prefs.modelId = model
        _service = null  // Reset service to pick up new config
    }

    /**
     * Load configuration from ApiConfigEntity
     */
    fun loadConfig(config: ApiConfigEntity) {
        prefs.providerId = config.providerId
        prefs.apiKey = config.apiKey
        prefs.baseUrl = config.baseUrl
        prefs.modelId = config.modelId
        _service = null
    }

    private fun createService(): ZhipuApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (Logger.isDebugEnabled) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(TimeoutConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TimeoutConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TimeoutConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .addInterceptor(createAuthInterceptor())
            .addInterceptor(createErrorHandlerInterceptor())
            .build()

        // Use a dummy base URL for Retrofit (required even with @Url annotation)
        // The actual URL is provided via the @Url parameter in the API methods
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.example.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        return retrofit.create(ZhipuApiService::class.java)
    }

    private fun createAuthInterceptor(): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val requestBuilder = originalRequest.newBuilder()

            // Add API key to Authorization header if available
            if (apiKey.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            // Add other required headers
            requestBuilder.header("Content-Type", "application/json")
            requestBuilder.header("Accept", "application/json")

            chain.proceed(requestBuilder.build())
        }
    }

    private fun createErrorHandlerInterceptor(): Interceptor {
        return Interceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                logger.e("Network error: ${e.message}")
                throw ApiException("Network error: ${e.message}", e)
            }
        }
    }

    /**
     * Analyze screen image with prompt and get AI response
     *
     * @param base64Image Base64 encoded image string (without data:image prefix)
     * @param prompt User prompt/question about the screen
     * @return ApiResponse containing AI's analysis and suggested actions
     */
    suspend fun analyzeScreen(base64Image: String, prompt: String = "分析这个屏幕，告诉我应该点击哪里、滑动哪里或者输入什么来完成用户的任务。"): Result<ApiResponse> = withContext(Dispatchers.IO) {
        try {
            logger.d("Analyzing screen with prompt: $prompt")
            logger.d("API Key configured: ${apiKey.isNotEmpty()}, URL: $baseUrl, Model: $modelId")
            logger.d("Image base64 length: ${base64Image.length}")

            val imageContent = ContentItem(
                type = "image_url",
                imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64Image")
            )

            val textContent = ContentItem(
                type = "text",
                text = prompt
            )

            val systemMessage = Message(
                role = "system",
                content = listOf(
                    ContentItem(
                        type = "text",
                        text = """你是一个手机自动化助手。你可以分析屏幕截图并提供操作建议。

可用的操作类型：
1. click - 点击指定坐标
2. swipe - 滑动（方向：up/down/left/right）
3. input - 输入文本
4. back - 返回
5. home - 回到主页

请用JSON格式返回操作列表，例如：
{
  "actions": [
    {"type": "click", "x": 384, "y": 683},
    {"type": "swipe", "direction": "up", "distance": 500},
    {"type": "input", "text": "Hello"},
    {"type": "back"}
  ]
}

如果不需要任何操作，请返回：
{
  "actions": [],
  "message": "说明原因"
}"""
                    )
                )
            )

            val userMessage = Message(
                role = "user",
                content = listOf(imageContent, textContent)
            )

            val request = ApiRequest(
                model = modelId,
                messages = listOf(systemMessage, userMessage),
                temperature = 0.7f,
                maxTokens = 2048
            )

            val response = service.analyzeScreen(apiUrl, "Bearer $apiKey", request)

            if (response.error != null) {
                logger.e("API error: code=${response.error.code}, type=${response.error.type}, message=${response.error.message}")
                Result.failure(ApiException(response.error.message ?: "Unknown API error"))
            } else {
                logger.d("Analysis successful, got ${response.choices?.size ?: 0} choices")
                Result.success(response)
            }
        } catch (e: JsonSyntaxException) {
            logger.e("JSON parsing error: ${e.message}", e)
            Result.failure(ApiException("Failed to parse API response: ${e.message}", e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            logger.e("HTTP error: code=${e.code()}, message=${e.message()}, body=$errorBody", e)
            Result.failure(ApiException("HTTP ${e.code()}: ${e.message()}", e))
        } catch (e: java.net.UnknownHostException) {
            logger.e("Network error: Unknown host - ${e.message}", e)
            Result.failure(ApiException("Network error: Cannot connect to server. Check URL and network.", e))
        } catch (e: java.net.SocketTimeoutException) {
            logger.e("Network timeout: ${e.message}", e)
            Result.failure(ApiException("Request timeout. Please try again.", e))
        } catch (e: java.io.IOException) {
            logger.e("IO error: ${e.message}", e)
            Result.failure(ApiException("Network error: ${e.message}", e))
        } catch (e: Exception) {
            logger.e("Analysis error: ${e.javaClass.simpleName} - ${e.message}", e)
            Result.failure(ApiException("Analysis failed: ${e.message}", e))
        }
    }

    /**
     * Parse actions from API response content
     *
     * @param response API response from AI
     * @return List of parsed UiAction objects
     */
    fun parseActions(response: ApiResponse): List<UiAction> {
        val actions = mutableListOf<UiAction>()

        try {
            val content = response.choices?.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) {
                logger.w("Empty response content")
                return actions
            }

            logger.d("Parsing actions from response: $content")

            // Try to extract JSON from the response
            val jsonStart = content.indexOf("{")
            val jsonEnd = content.lastIndexOf("}")

            if (jsonStart == -1 || jsonEnd == -1) {
                logger.w("No JSON found in response")
                return actions
            }

            val jsonString = content.substring(jsonStart, jsonEnd + 1)
            val responseObj = gson.fromJson(jsonString, Map::class.java) as? Map<String, Any>
            val actionsArray = responseObj?.get("actions") as? List<Map<String, Any>>

            actionsArray?.forEach { actionMap ->
                val type = actionMap["type"] as? String ?: return@forEach
                val action = when (type) {
                    "click" -> {
                        val x = (actionMap["x"] as? Double)?.toFloat() ?: (actionMap["x"] as? Float)
                        val y = (actionMap["y"] as? Double)?.toFloat() ?: (actionMap["y"] as? Float)
                        if (x != null && y != null) {
                            UiAction.Click(x, y)
                        } else null
                    }
                    "swipe" -> {
                        val directionStr = actionMap["direction"] as? String ?: return@forEach
                        val direction = when (directionStr.lowercase()) {
                            "up" -> SwipeDirection.UP
                            "down" -> SwipeDirection.DOWN
                            "left" -> SwipeDirection.LEFT
                            "right" -> SwipeDirection.RIGHT
                            else -> return@forEach
                        }
                        val distance = (actionMap["distance"] as? Double)?.toInt()
                            ?: (actionMap["distance"] as? Int) ?: 500
                        UiAction.Swipe(direction, distance)
                    }
                    "input" -> {
                        val text = actionMap["text"] as? String ?: return@forEach
                        UiAction.InputText(text)
                    }
                    "back" -> UiAction.Navigate(NavigationAction.BACK)
                    "home" -> UiAction.Navigate(NavigationAction.HOME)
                    "recents" -> UiAction.Navigate(NavigationAction.RECENTS)
                    else -> {
                        logger.w("Unknown action type: $type")
                        null
                    }
                }
                action?.let { actions.add(it) }
            }

            logger.d("Parsed ${actions.size} actions")
        } catch (e: Exception) {
            logger.e("Error parsing actions: ${e.message}")
        }

        return actions
    }

    /**
     * Send a simple chat message
     *
     * @param message User message
     * @return Result containing API response
     */
    suspend fun chat(message: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        try {
            val userMessage = Message(
                role = "user",
                content = listOf(
                    ContentItem(type = "text", text = message)
                )
            )

            val request = ApiRequest(
                model = modelId,
                messages = listOf(userMessage)
            )

            val response = service.chat(apiUrl, "Bearer $apiKey", request)

            if (response.error != null) {
                Result.failure(ApiException(response.error.message ?: "Unknown API error"))
            } else {
                Result.success(response)
            }
        } catch (e: Exception) {
            Result.failure(ApiException("Chat failed: ${e.message}", e))
        }
    }

    /**
     * Chat with tools using OpenAI-compatible function calling
     *
     * @param messages List of message maps
     * @param tools Optional tools definition (if null, uses default from ToolRegistry)
     * @return ApiResponse or null on failure
     */
    suspend fun chatWithTools(
        messages: List<Map<String, Any>>,
        tools: List<Map<String, Any>>? = null
    ): ApiResponse? = withContext(Dispatchers.IO) {
        // Check circuit breaker first
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            logger.w("Circuit breaker is OPEN - failing fast")
            val stats = circuitBreaker.getStats()
            logger.w("Circuit stats: state=${stats.state}, failures=${stats.failureCount}, timeSinceFailure=${stats.timeSinceLastFailure}ms")
            return@withContext null
        }

        return@withContext retryWithExponentialBackoff {
            chatWithToolsInternal(messages, tools)
        }
    }

    /**
     * Internal implementation of chatWithTools
     */
    private suspend fun chatWithToolsInternal(
        messages: List<Map<String, Any>>,
        tools: List<Map<String, Any>>? = null
    ): ApiResponse? = withContext(Dispatchers.IO) {
        try {
            logger.d("chatWithTools called")
            logger.d("API URL: $apiUrl")
            logger.d("Model: $modelId")
            logger.d("API Key: ${apiKey.take(10)}...")

            if (apiKey.isEmpty()) {
                logger.e("API Key is empty!")
                return@withContext null
            }

            // Use provided tools or get from ToolRegistry
            val effectiveTools = tools ?: run {
                logger.d("No tools provided, using default from ToolRegistry")
                com.example.myapplication.agent.ToolRegistry.getInstance(context).toOpenAIToolsFormat()
            }
            logger.d("Using ${effectiveTools.size} tools")

            val requestBody = mapOf(
                "model" to modelId,
                "messages" to messages,
                "tools" to effectiveTools,
                "tool_choice" to "auto",
                "temperature" to 0.1
            )

            val jsonBody = gson.toJson(requestBody)
            logger.d("Request body: ${jsonBody.take(800)}...")

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyObj = jsonBody.toRequestBody(mediaType)

            val request = okhttp3.Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBodyObj)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(TimeoutConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TimeoutConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            logger.d("Response code: ${response.code}")

            if (!response.isSuccessful || responseBody == null) {
                logger.e("API call failed: ${response.code} - $responseBody")
                throw ApiException("HTTP ${response.code}: ${response.message}")
            }

            logger.d("Response body: ${responseBody.take(800)}...")

            // Parse response and extract tool calls
            // Success will be recorded by retryWithExponentialBackoff wrapper
            parseToolCallResponse(responseBody)
        } catch (e: Exception) {
            logger.e("chatWithTools error: ${e.message}", e)
            throw e // Re-throw for retry logic to handle
        }
    }

    /**
     * Retry with exponential backoff
     * Uses circuit breaker to track failures
     */
    private suspend fun <T> retryWithExponentialBackoff(
        block: suspend () -> T
    ): T? {
        var lastException: Exception? = null
        var delayMs = BASE_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                return circuitBreaker.execute(block)
            } catch (e: Exception) {
                lastException = e
                logger.w("Attempt $attempt/$MAX_RETRY_ATTEMPTS failed: ${e.message}")
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    logger.d("Retrying in ${delayMs}ms...")
                    delay(delayMs)
                    delayMs *= 2 // Exponential backoff
                }
            }
        }

        logger.e("All $MAX_RETRY_ATTEMPTS attempts failed")
        circuitBreaker.getState().let { state ->
            if (state == CircuitBreaker.State.OPEN) {
                logger.w("Circuit breaker is now OPEN - will fail fast on next calls")
            }
        }
        return null
    }

    /**
     * Parse response with tool calls
     */
    private fun parseToolCallResponse(responseBody: String): ApiResponse? {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            val message = choice?.getAsJsonObject("message")

            val content = message?.get("content")?.asString
            val toolCallsArray = message?.getAsJsonArray("tool_calls")

            // Parse tool calls into structured format
            val parsedToolCalls = if (toolCallsArray != null && toolCallsArray.size() > 0) {
                toolCallsArray.mapNotNull { tc ->
                    try {
                        val tcObj = tc.asJsonObject
                        val id = tcObj.get("id")?.asString ?: "call_${System.nanoTime()}"
                        val function = tcObj.getAsJsonObject("function")
                        val name = function?.get("name")?.asString ?: return@mapNotNull null
                        val argsJson = function?.get("arguments")?.asString ?: "{}"

                        // Parse arguments JSON
                        val argsMap = gson.fromJson(argsJson, Map::class.java) as Map<String, Any>

                        com.example.myapplication.agent.ToolCall(
                            name = name,
                            parameters = argsMap,
                            rawMatch = "$name($argsJson)",
                            id = id
                        )
                    } catch (e: Exception) {
                        logger.e("Failed to parse tool call: ${e.message}")
                        null
                    }
                }
            } else emptyList()

            // Store tool calls in a way we can retrieve later
            // We'll use a special format in content to pass tool calls to AgentEngine
            val finalContent = if (parsedToolCalls.isNotEmpty()) {
                // Format: __TOOL_CALLS__ followed by JSON
                "__TOOL_CALLS__" + gson.toJson(parsedToolCalls)
            } else {
                content
            }

            ApiResponse(
                id = json.get("id")?.asString ?: "",
                created = json.get("created")?.asLong,
                model = json.get("model")?.asString,
                choices = listOf(
                    com.example.myapplication.api.model.Choice(
                        index = 0,
                        message = com.example.myapplication.api.model.ResponseMessage(
                            role = "assistant",
                            content = finalContent,
                            toolCalls = null
                        ),
                        finishReason = choice?.get("finish_reason")?.asString
                    )
                ),
                usage = null,
                error = null
            )
        } catch (e: Exception) {
            logger.e("Failed to parse response: ${e.message}")
            gson.fromJson(responseBody, ApiResponse::class.java)
        }
    }
}

/**
 * Custom exception for API errors
 */
class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
