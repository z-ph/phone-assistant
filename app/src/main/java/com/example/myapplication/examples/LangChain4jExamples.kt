package com.example.myapplication.examples

import android.content.Context
import android.graphics.Bitmap
import com.example.myapplication.agent.langchain.EnhancedLangChainAgentEngine
import com.example.myapplication.config.ApiConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * LangChain4j 集成使用示例
 */
class LangChain4jExamples(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)

    // ========== 示例 1: 基础使用 ==========

    /**
     * 基础对话示例
     */
    fun example1_BasicChat() {
        val agent = EnhancedLangChainAgentEngine.getInstance(context)

        // 初始化（使用默认配置）
        agent.initialize()

        // 执行简单任务
        agent.execute("打开微信") { result ->
            if (result.success) {
                println("任务完成: ${result.message}")
            } else {
                println("任务失败: ${result.message}")
            }
        }
    }

    // ========== 示例 2: 流式响应（当前版本暂不支持，预留接口） ==========

    /**
     * 流式对话示例（当前版本返回提示信息）
     */
    fun example2_StreamingChat() {
        val agent = EnhancedLangChainAgentEngine.getInstance(context)
        agent.initialize()

        // 检查是否支持流式响应
        if (!agent.supportsStreaming()) {
            println("当前版本暂不支持流式响应，将使用普通模式")
            // 回退到普通模式
            agent.execute("帮我设置明天早上 8 点的闹钟") { result ->
                println("结果: ${result.message}")
            }
            return
        }
    }

    // ========== 示例 3: 视觉多模态 ==========

    /**
     * 视觉任务示例（截图分析）
     */
    fun example3_VisionTask(screenshot: Bitmap) {
        val agent = EnhancedLangChainAgentEngine.getInstance(context)
        agent.initialize()

        // 检查是否支持视觉
        if (!agent.supportsVision()) {
            println("当前模型不支持视觉功能")
            return
        }

        // 使用截图执行任务
        agent.executeWithImage(
            "请分析这个界面，找到设置按钮并点击它",
            screenshot
        ) { result ->
            if (result.success) {
                println("视觉分析完成: ${result.message}")
            } else {
                println("分析失败: ${result.message}")
            }
        }
    }

    // ========== 示例 4: 工具执行监听 ==========

    /**
     * 带工具执行监听的示例
     */
    fun example4_ToolExecutionListener() {
        val agent = EnhancedLangChainAgentEngine.getInstance(context)
        agent.initialize()

        // 添加监听器
        agent.addToolExecutionListener(object :
            EnhancedLangChainAgentEngine.ToolExecutionListener {
            override fun onToolStart(toolName: String, parameters: Map<String, Any>) {
                println("🛠️ 开始执行: $toolName")
                println("   参数: $parameters")
            }

            override fun onToolComplete(toolName: String, result: String, durationMs: Long) {
                println("✅ 完成: $toolName (${durationMs}ms)")
                println("   结果: ${result.take(100)}...")
            }

            override fun onToolError(toolName: String, error: String) {
                println("❌ 错误: $toolName - $error")
            }
        })

        // 执行任务
        agent.execute("打开设置页面，进入关于手机") { result ->
            println("最终结果: ${result.message}")
        }
    }

    // ========== 示例 5: 多模型切换 ==========

    /**
     * 多模型配置示例
     */
    fun example5_MultiModelConfig() {
        // 配置 1: 使用智谱 AI（性价比高）
        val zhipuConfig = ApiConfigManager.ProviderConfig(
            providerId = "zhipu",
            apiKey = "your-zhipu-key",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelId = "glm-4"
        )

        // 配置 2: 使用 OpenAI（功能强大）
        val openaiConfig = ApiConfigManager.ProviderConfig(
            providerId = "openai",
            apiKey = "sk-...",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-4o"
        )

        // 配置 3: 使用 Ollama（本地免费）
        val ollamaConfig = ApiConfigManager.ProviderConfig(
            providerId = "ollama",
            apiKey = "",
            baseUrl = "http://localhost:11434",
            modelId = "llama3.2"
        )

        // 保存配置
        val configManager = ApiConfigManager(context)
        configManager.saveProviderConfig(zhipuConfig)
        configManager.saveProviderConfig(openaiConfig)
        configManager.saveProviderConfig(ollamaConfig)

        // 切换模型
        configManager.switchProvider("openai")

        // 重新初始化 Agent 使用新模型
        val agent = EnhancedLangChainAgentEngine.getInstance(context)
        agent.reconfigure()
    }

    // ========== 示例 6: 错误处理和重试 ==========

    /**
     * 带错误处理的任务执行
     */
    fun example6_ErrorHandling() {
        val agent = EnhancedLangChainAgentEngine.getInstance(context)

        val result = agent.initialize()
        result.onSuccess {
            println("✅ Agent 初始化成功")
        }.onFailure { error ->
            when (error) {
                is IllegalArgumentException -> {
                    println("❌ 配置错误: ${error.message}")
                    // 提示用户检查 API 配置
                }
                else -> {
                    println("❌ 初始化失败: ${error.message}")
                    // 显示重试按钮
                }
            }
        }

        // 执行任务（带重试）
        executeWithRetry(agent, "打开蓝牙", maxRetries = 3)
    }

    private fun executeWithRetry(
        agent: EnhancedLangChainAgentEngine,
        task: String,
        maxRetries: Int,
        currentRetry: Int = 0
    ) {
        agent.execute(task) { result ->
            if (result.success) {
                println("✅ 任务成功")
            } else if (currentRetry < maxRetries) {
                println("⚠️ 重试 ${currentRetry + 1}/$maxRetries...")
                executeWithRetry(agent, task, maxRetries, currentRetry + 1)
            } else {
                println("❌ 任务失败，已达到最大重试次数")
            }
        }
    }

    // ========== 示例 7: 清除记忆 ==========

    /**
     * 清除对话记忆示例
     */
    fun example7_ClearMemory() {
        val agent = EnhancedLangChainAgentEngine.getInstance(context)
        agent.initialize()

        // 执行一些对话
        agent.execute("你好") { }
        agent.execute("帮我打开微信") { }

        // 清除记忆（开始新对话）
        agent.clearMemory()
        println("对话记忆已清除")
    }
}
