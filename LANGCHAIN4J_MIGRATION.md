# LangChain4j 集成迁移指南

## 概述

本项目已集成 LangChain4j 0.36.2，提供多模型支持、视觉多模态和 RAG（检索增强生成）能力。

## 主要功能

### 1. 多模型支持

支持的 LLM 提供商：
- **OpenAI** (GPT-4, GPT-3.5)
- **Anthropic** (Claude 系列)
- **Ollama** (Llama, Mistral 等本地模型)
- **智谱 AI** (通过 OpenAI 兼容接口)
- **Azure OpenAI** (通过 OpenAI 兼容接口)
- **自定义端点** (任何 OpenAI 兼容 API)

### 2. 核心组件

#### ModelFactory（模型工厂）
位置：`agent/langchain/ModelFactory.kt`

功能：
- 统一管理所有 LLM 提供商的模型创建
- 自动检测视觉支持
- 提供商能力查询

```kotlin
// 创建 Chat 模型
val chatModel = ModelFactory.createChatModel(config)

// 检查能力
val supportsVision = ModelFactory.supportsVision("openai", "gpt-4o")
```

#### EnhancedLangChainAgentEngine（增强 Agent）
位置：`agent/langchain/EnhancedLangChainAgentEngine.kt`

功能：
- 支持多模型切换
- 视觉/多模态输入
- 工具执行监听

```kotlin
// 初始化
val agent = EnhancedLangChainAgentEngine.getInstance(context)
agent.initialize()

// 普通对话
agent.execute("打开微信") { result ->
    // 处理结果
}

// 视觉任务（支持截图分析）
val screenshot = screenCapture.capture()
agent.executeWithImage("点击这个按钮", screenshot) { result ->
    // 处理结果
}
```

### 智谱 AI (Zhipu)
```kotlin
ProviderConfig(
    providerId = "zhipu",
    apiKey = "your-api-key",
    baseUrl = "https://open.bigmodel.cn/api/paas/v4",
    modelId = "glm-4"
)
```

### OpenAI
```kotlin
ProviderConfig(
    providerId = "openai",
    apiKey = "sk-...",
    baseUrl = "https://api.openai.com/v1",
    modelId = "gpt-4o"
)
```

### Ollama（本地）
```kotlin
ProviderConfig(
    providerId = "ollama",
    apiKey = "", // 不需要
    baseUrl = "http://localhost:11434",
    modelId = "llama3.2"
)
```

## 使用示例

### 基础使用

```kotlin
val agent = EnhancedLangChainAgentEngine.getInstance(context)
agent.initialize()

agent.execute("打开微信") { result ->
    if (result.success) {
        println("任务完成: ${result.message}")
    } else {
        println("任务失败: ${result.message}")
    }
}
```

### 视觉功能

```kotlin
// 检查是否支持视觉
if (agent.supportsVision()) {
    val screenshot = screenCapture.capture()
    agent.executeWithImage("分析这个界面", screenshot) { result ->
        // 处理结果
    }
}
```

### 工具执行监听

```kotlin
agent.addToolExecutionListener(object : ToolExecutionListener {
    override fun onToolStart(toolName: String, parameters: Map<String, Any>) {
        Log.d(TAG, "开始执行: $toolName")
    }

    override fun onToolComplete(toolName: String, result: String, durationMs: Long) {
        Log.d(TAG, "完成: $toolName")
    }

    override fun onToolError(toolName: String, error: String) {
        Log.e(TAG, "错误: $toolName - $error")
    }
})
```

## 注意事项

1. **Streaming 响应**：当前版本（0.36.2）的 Streaming API 有变化，暂不支持真正的流式响应，后续版本将添加
2. **视觉模型**：需要选择支持视觉的模型（如 gpt-4o, claude-3, glm-4v）
3. **网络权限**：确保应用有网络访问权限

## API 参考

- [LangChain4j 官方文档](https://docs.langchain4j.dev/)
- [LangChain4j GitHub](https://github.com/langchain4j/langchain4j)

## 文件结构

```
app/src/main/java/com/example/myapplication/agent/langchain/
├── ModelFactory.kt                    # 模型工厂
├── EnhancedLangChainAgentEngine.kt    # 增强 Agent 引擎
├── RAGManager.kt                      # RAG 管理器
└── RAGAgent.kt                        # RAG Agent

app/src/main/java/com/example/myapplication/examples/
└── LangChain4jExamples.kt             # 使用示例

LANGCHAIN4J_MIGRATION.md               # 本迁移文档
```

## 后续计划

- [ ] 升级到 LangChain4j 1.0.0+ 获得完整 Streaming 支持
- [ ] 添加更多向量存储后端（Chroma、Milvus）
- [ ] Web Search 工具集成
- [ ] 代码执行工具
- [ ] 更高级的 Agent 工作流
