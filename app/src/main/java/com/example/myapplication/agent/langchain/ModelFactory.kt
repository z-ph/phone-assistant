package com.example.myapplication.agent.langchain

import com.example.myapplication.config.ApiConfigManager
import com.example.myapplication.utils.Logger
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.chat.StreamingChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.time.Duration

/**
 * LangChain4j 模型工厂
 * 支持多种 LLM 提供商的 Chat 和 Streaming 模型创建
 * 
 * 注意：0.36.2 版本支持的提供商：
 * - OpenAI (GPT-4, GPT-3.5)
 * - Anthropic (Claude)
 * - Ollama (本地模型)
 * - LocalAI (OpenAI 兼容接口)
 * - 其他 OpenAI 兼容端点
 */
object ModelFactory {

    private const val TAG = "ModelFactory"
    private val logger = Logger(TAG)

    /**
     * 创建同步聊天模型
     */
    fun createChatModel(config: ApiConfigManager.ProviderConfig): ChatLanguageModel {
        logger.d("创建 Chat 模型: provider=${config.providerId}, model=${config.modelId}")

        return when (config.providerId) {
            // OpenAI
            "openai" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OpenAiChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .apiKey(config.apiKey)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(3)
                    .build()
            }

            // Anthropic Claude
            "anthropic" -> AnthropicChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(config.modelId)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(3)
                .build()

            // Ollama 本地模型
            "ollama" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OllamaChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(120))
                    .build()
            }

            // LocalAI / 兼容 OpenAI 的本地服务
            "localai" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OpenAiChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .apiKey(config.apiKey.takeIf { it.isNotEmpty() } ?: "dummy-key")
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(120))
                    .maxRetries(3)
                    .build()
            }

            // 智谱 AI (使用 OpenAI 兼容接口)
            "zhipu" -> {
                val cleanBaseUrl = config.baseUrl.takeIf { it.isNotEmpty() } ?: "https://open.bigmodel.cn/api/paas/v4"
                OpenAiChatModel.builder()
                    .baseUrl(cleanBaseUrl.trimEnd('/'))
                    .apiKey(config.apiKey)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(3)
                    .build()
            }

            // 自定义 OpenAI 兼容端点
            "custom", "azure" -> {
                // LangChain4j will append /chat/completions to baseUrl
                // So baseUrl should include /v1 if needed (e.g., https://coding.dashscope.aliyuncs.com/v1)
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                
                logger.d("=== LangChain 配置 ===")
                logger.d("原始 baseUrl: ${config.baseUrl}")
                logger.d("清理后 baseUrl: $cleanBaseUrl")
                logger.d("最终请求 URL: $cleanBaseUrl/chat/completions")
                logger.d("Model: ${config.modelId}")
                logger.d("API Key: ${config.apiKey.take(10)}...${config.apiKey.takeLast(4)}")
                logger.d("====================")
                
                OpenAiChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .apiKey(config.apiKey)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(3)
                    .logRequests(true)
                    .logResponses(true)
                    .build()
            }

            else -> throw IllegalArgumentException("不支持的提供商: ${config.providerId}")
        }
    }

    /**
     * 创建流式聊天模型（用于实时响应）
     */
    fun createStreamingModel(config: ApiConfigManager.ProviderConfig): StreamingChatLanguageModel? {
        logger.d("创建 Streaming 模型: provider=${config.providerId}, model=${config.modelId}")

        return when (config.providerId) {
            // OpenAI Streaming
            "openai" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OpenAiStreamingChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .apiKey(config.apiKey)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(60))
                    .build()
            }

            // Ollama Streaming
            "ollama" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OllamaStreamingChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(120))
                    .build()
            }

            // LocalAI Streaming（使用 OpenAI 兼容接口）
            "localai" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OpenAiStreamingChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .apiKey(config.apiKey.takeIf { it.isNotEmpty() } ?: "dummy-key")
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(120))
                    .build()
            }

            // 智谱 AI Streaming (OpenAI 兼容)
            "zhipu" -> {
                val cleanBaseUrl = config.baseUrl.takeIf { it.isNotEmpty() } ?: "https://open.bigmodel.cn/api/paas/v4"
                OpenAiStreamingChatModel.builder()
                    .baseUrl(cleanBaseUrl.trimEnd('/'))
                    .apiKey(config.apiKey)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(60))
                    .build()
            }

            // 自定义/其他 OpenAI 兼容
            "custom", "azure" -> {
                val cleanBaseUrl = config.baseUrl.trimEnd('/')
                OpenAiStreamingChatModel.builder()
                    .baseUrl(cleanBaseUrl)
                    .apiKey(config.apiKey)
                    .modelName(config.modelId)
                    .timeout(Duration.ofSeconds(60))
                    .build()
            }

            // Anthropic 暂不支持 Streaming（需要特殊处理）
            "anthropic" -> {
                logger.w("Anthropic Streaming 支持需要额外配置，返回 null")
                null
            }

            else -> null
        }
    }

    /**
     * 检查提供商是否支持 Streaming
     */
    fun supportsStreaming(providerId: String): Boolean {
        return when (providerId) {
            "openai", "zhipu", "ollama", "localai", "custom", "azure" -> true
            else -> false
        }
    }

    /**
     * 检查提供商是否支持视觉（多模态）
     */
    fun supportsVision(providerId: String, modelId: String): Boolean {
        return when (providerId) {
            "openai", "zhipu", "custom", "azure" -> 
                modelId.contains("vision", ignoreCase = true) ||
                modelId.contains("gpt-4o", ignoreCase = true) ||
                modelId.contains("gpt-4-turbo", ignoreCase = true) ||
                modelId.contains("glm-4v", ignoreCase = true)
            "anthropic" -> modelId.contains("claude-3", ignoreCase = true)
            "ollama" -> modelId.contains("llava", ignoreCase = true)
            else -> false
        }
    }

    /**
     * 获取提供商的推荐模型列表
     */
    fun getRecommendedModels(providerId: String): List<String> {
        return when (providerId) {
            "zhipu" -> listOf(
                "glm-4",
                "glm-4v",  // 视觉模型
                "glm-4-flash",
                "glm-4-air"
            )
            "openai" -> listOf(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "gpt-4-vision-preview",
                "gpt-3.5-turbo"
            )
            "anthropic" -> listOf(
                "claude-3-5-sonnet-20241022",
                "claude-3-opus-20240229",
                "claude-3-sonnet-20240229",
                "claude-3-haiku-20240307"
            )
            "ollama" -> listOf(
                "llama3.2",
                "llama3.1",
                "qwen2.5",
                "mistral",
                "llava"  // 视觉模型
            )
            "localai" -> listOf(
                "gpt-4",
                "llama2",
                "mistral"
            )
            else -> emptyList()
        }
    }

    /**
     * 获取提供商的默认配置
     */
    fun getDefaultConfig(providerId: String): Triple<String, String, String> {
        return when (providerId) {
            "zhipu" -> Triple(
                "https://open.bigmodel.cn/api/paas/v4",
                "glm-4",
                ""
            )
            "openai" -> Triple(
                "https://api.openai.com/v1",
                "gpt-4o",
                ""
            )
            "anthropic" -> Triple(
                "https://api.anthropic.com/v1",
                "claude-3-5-sonnet-20241022",
                ""
            )
            "ollama" -> Triple(
                "http://localhost:11434",
                "llama3.2",
                ""
            )
            "localai" -> Triple(
                "http://localhost:8080/v1",
                "gpt-4",
                ""
            )
            else -> Triple("", "", "")
        }
    }
}
