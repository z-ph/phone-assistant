package com.example.myapplication.config

enum class ModelProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val endpointFormat: String,
    val authHeaderFormat: String
) {
    ZHIPU(
        id = "zhipu",
        displayName = "智谱 AI",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4",
        endpointFormat = "/chat/completions",
        authHeaderFormat = "Bearer {token}"
    ),

    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o",
        endpointFormat = "/chat/completions",
        authHeaderFormat = "Bearer {token}"
    ),

    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        endpointFormat = "/chat/completions",
        authHeaderFormat = "Bearer {token}"
    ),

    QWEN(
        id = "qwen",
        displayName = "通义千问 (Qwen)",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/v1",
        defaultModel = "qwen-plus",
        endpointFormat = "/chat/completions",
        authHeaderFormat = "Bearer {token}"
    ),

    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic (Claude)",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-3-5-sonnet-20241022",
        endpointFormat = "/messages",
        authHeaderFormat = "Bearer {token}"
    ),

    OLLAMA(
        id = "ollama",
        displayName = "Ollama (自部署)",
        defaultBaseUrl = "http://localhost:11434",
        defaultModel = "llama3.2-vision",
        endpointFormat = "/api/chat",
        authHeaderFormat = ""
    ),

    AZURE_OPENAI(
        id = "azure-openai",
        displayName = "Azure OpenAI",
        defaultBaseUrl = "https://{location}.openai.azure.com/openai/deployments/{deployment}",
        defaultModel = "gpt-4o",
        endpointFormat = "/chat/completions?api-version=2024-02-15-preview",
        authHeaderFormat = "Bearer {token}"
    ),

    GOOGLE_VERTEX(
        id = "google-vertex",
        displayName = "Google Vertex AI",
        defaultBaseUrl = "https://{location}-aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/publishers/google/models",
        defaultModel = "gemini-1.5-pro",
        endpointFormat = "/predict",
        authHeaderFormat = "Bearer {token}"
    ),

    MISTRAL(
        id = "mistral",
        displayName = "Mistral AI",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-large-latest",
        endpointFormat = "/chat/completions",
        authHeaderFormat = "Bearer {token}"
    ),

    HUGGING_FACE(
        id = "huggingface",
        displayName = "Hugging Face Inference",
        defaultBaseUrl = "https://api-inference.huggingface.co/models",
        defaultModel = "mistralai/Mixtral-8x7B-Instruct-v0.1",
        endpointFormat = "",
        authHeaderFormat = "Bearer {token}"
    ),

    CUSTOM(
        id = "custom",
        displayName = "自定义 API",
        defaultBaseUrl = "",
        defaultModel = "",
        endpointFormat = "/chat/completions",
        authHeaderFormat = "Bearer {token}"
    );

    fun buildApiUrl(baseUrl: String, customEndpoint: String? = null): String {
        val endpoint = customEndpoint ?: endpointFormat
        return if (baseUrl.endsWith("/")) {
            "${baseUrl}${endpoint.trimStart('/')}"
        } else {
            "$baseUrl$endpoint"
        }
    }

    fun buildAuthHeader(apiKey: String): Map<String, String> {
        return if (authHeaderFormat.isEmpty()) {
            emptyMap()
        } else {
            mapOf("Authorization" to authHeaderFormat.replace("{token}", apiKey))
        }
    }

    companion object {
        fun fromId(id: String): ModelProvider = entries.find { it.id == id } ?: ZHIPU

        fun getAllProviders(): List<ModelProvider> = entries.toList()

        fun getProvidersWithFunctionCalling(): List<ModelProvider> = listOf(
            ZHIPU, OPENAI, ANTHROPIC, AZURE_OPENAI, GOOGLE_VERTEX, MISTRAL
        )
    }
}
