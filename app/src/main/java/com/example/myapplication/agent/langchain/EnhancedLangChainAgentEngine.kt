package com.example.myapplication.agent.langchain

import android.content.Context
import android.graphics.Bitmap
import com.example.myapplication.agent.AndroidTools
import com.example.myapplication.config.ApiConfigManager
import com.example.myapplication.utils.Logger
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 增强版 LangChain4j Agent 引擎
 * 支持：多模型、视觉多模态、工具回调
 * 
 * 注：Streaming 响应在 LangChain4j 0.36.2 中 API 有变化，暂使用同步模式
 */
class EnhancedLangChainAgentEngine(private val context: Context) {

    companion object {
        private const val TAG = "EnhancedLangChainAgentEngine"
        private const val MAX_MESSAGES = 20

        @Volatile
        private var instance: EnhancedLangChainAgentEngine? = null

        fun getInstance(context: Context): EnhancedLangChainAgentEngine {
            return instance ?: synchronized(this) {
                instance ?: EnhancedLangChainAgentEngine(context.applicationContext)
                    .also { instance = it }
            }
        }

        private const val SYSTEM_PROMPT = """
            你是一个手机自动化助手，可以通过分析屏幕来执行各种操作。
            
            ## 可用工具
            
            ### 导航
            - back(): 返回
            - home(): 回到主页
            - recents(): 最近任务
            
            ### 手势
            - click(x, y): 点击坐标
            - longClick(x, y, duration): 长按
            - swipe(direction, distance): 滑动 (up/down/left/right)
            - type(text): 输入文本
            
            ### UI 元素操作 (优先使用)
            - find_nodes_by_text(text, exact): 查找控件
            - click_by_text(text, exact): 根据文本点击控件
            - scroll_to_text(text, max_swipes, direction): 滚动查找文本
            - get_ui_hierarchy(): 获取控件树
            
            ### 观察
            - capture_screen(): 截图
            
            ### 系统
            - open_app(package_name): 打开应用
            - copy_to_clipboard(text): 复制到剪贴板
            
            ### 控制
            - wait(ms): 等待
            - finish(summary): 任务完成
            - reply(message): 回复用户
            
            ## 执行原则
            
            1. **优先使用 UI 元素工具**: click_by_text 比 click 更可靠
            2. **逐步思考**: 每步完成后观察结果再继续
            3. **错误处理**: 失败时尝试替代方案
            4. **适时完成**: 任务完成后调用 finish()
            
            ## 响应格式
            
            - 正常操作：直接调用相应工具
            - 需要与用户交流：调用 reply(message)
            - 任务完成：调用 finish(summary)
        """

        private const val SYSTEM_PROMPT_VISION = """
            你是一个支持视觉的手机自动化助手。你可以查看屏幕截图并执行操作。
            
            ## 能力
            - 分析屏幕截图内容
            - 识别 UI 元素和文本
            - 执行点击、滑动等操作
            - 回答关于屏幕内容的问题
            
            ## 可用工具
            （同上）
            
            当用户发送图片时，请先分析图片内容，然后决定如何操作。
        """
    }

    private val logger = Logger(TAG)
    private val configManager = ApiConfigManager(context)

    // 工具实例
    private lateinit var tools: AndroidTools

    // 当前配置
    private var currentConfig: ApiConfigManager.ProviderConfig? = null

    // Assistant 实例（同步）
    private var assistant: Assistant? = null

    // Assistant 实例（视觉）
    private var visionAssistant: VisionAssistant? = null

    // 工具执行监听器
    private val toolExecutionListeners = mutableListOf<ToolExecutionListener>()

    /**
     * Agent 状态
     */
    data class AgentState(
        val state: State = State.IDLE,
        val result: String? = null,
        val error: String? = null,
        val currentStep: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class State {
        IDLE, READY, RUNNING, COMPLETED, ERROR, CANCELLED
    }

    /**
     * 执行结果
     */
    data class AgentResult(
        val success: Boolean,
        val message: String,
        val isReply: Boolean = false
    ) {
        companion object {
            fun success(message: String) = AgentResult(true, message)
            fun error(message: String) = AgentResult(false, message)
            fun reply(message: String) = AgentResult(true, message, isReply = true)
        }
    }

    /**
     * 流式响应数据（预留接口，当前版本使用模拟实现）
     */
    data class StreamChunk(
        val content: String = "",
        val isComplete: Boolean = false,
        val error: String? = null
    )

    /**
     * 工具执行监听器接口
     */
    interface ToolExecutionListener {
        fun onToolStart(toolName: String, parameters: Map<String, Any>)
        fun onToolComplete(toolName: String, result: String, durationMs: Long)
        fun onToolError(toolName: String, error: String)
    }

    /**
     * 初始化 Agent
     */
    fun initialize(): Result<Unit> {
        return try {
            val config = configManager.getCurrentProviderConfig()
                ?: return Result.failure(Exception("未配置 API，请先在设置中配置"))

            currentConfig = config
            tools = AndroidTools(context.applicationContext)

            // 创建同步 Assistant
            val chatModel = ModelFactory.createChatModel(config)
            val chatMemory = MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES)

            // 获取带有 @Tool 注解的方法
            val toolMethods = tools.javaClass.declaredMethods
                .filter { it.isAnnotationPresent(Tool::class.java) }
                .toTypedArray()

            assistant = AiServices.builder(Assistant::class.java)
                .chatLanguageModel(chatModel)
                .tools(tools, *toolMethods)
                .chatMemory(chatMemory)
                .build()

            // 如果支持视觉，创建 Vision Assistant
            if (ModelFactory.supportsVision(config.providerId, config.modelId)) {
                visionAssistant = AiServices.builder(VisionAssistant::class.java)
                    .chatLanguageModel(chatModel)
                    .tools(tools, *toolMethods)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES))
                    .build()
            }

            logger.d("Agent 初始化成功: provider=${config.providerId}, model=${config.modelId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Agent 初始化失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 执行普通任务（同步）
     */
    fun execute(task: String, callback: (AgentResult) -> Unit) {
        val assistant = this.assistant ?: run {
            callback(AgentResult.error("Agent 未初始化"))
            return
        }

        try {
            logger.d("开始执行任务: $task")
            val result = assistant.chat(task)
            handleResult(result, callback)
        } catch (e: Exception) {
            logger.e("任务执行失败: ${e.message}", e)
            callback(AgentResult.error(e.message ?: "未知错误"))
        }
    }

    /**
     * 执行视觉任务（支持图片）
     */
    fun executeWithImage(task: String, image: Bitmap, callback: (AgentResult) -> Unit) {
        val visionAssistant = this.visionAssistant ?: run {
            // 如果不支持视觉，转为普通任务
            logger.w("当前模型不支持视觉，转为普通任务")
            execute(task, callback)
            return
        }

        try {
            logger.d("开始执行视觉任务: $task")

            // 将 Bitmap 转为 Base64
            val base64Image = bitmapToBase64(image)

            val result = visionAssistant.chatWithImage(task, base64Image)
            handleResult(result, callback)
        } catch (e: Exception) {
            logger.e("视觉任务执行失败: ${e.message}", e)
            callback(AgentResult.error(e.message ?: "未知错误"))
        }
    }

    /**
     * 流式执行任务（模拟实现，实际使用分段返回）
     * 
     * 注意：LangChain4j 0.36.2 的 Streaming API 与 1.0.0 不同
     * 当前版本使用分段返回模拟流式效果
     */
    fun executeStreaming(task: String): Flow<StreamChunk> = flow {
        emit(StreamChunk(error = "当前版本暂不支持真正的流式响应，请使用 execute() 方法"))
    }

    /**
     * 处理执行结果
     */
    private fun handleResult(result: String, callback: (AgentResult) -> Unit) {
        when {
            result.startsWith("FINISH:") -> {
                val summary = result.substringAfter("FINISH:").trim()
                callback(AgentResult.success(summary))
            }
            result.startsWith("REPLY:") -> {
                val reply = result.substringAfter("REPLY:").trim()
                callback(AgentResult.reply(reply))
            }
            else -> {
                callback(AgentResult.success(result))
            }
        }
    }

    /**
     * Bitmap 转 Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 添加工具执行监听器
     */
    fun addToolExecutionListener(listener: ToolExecutionListener) {
        toolExecutionListeners.add(listener)
    }

    /**
     * 移除工具执行监听器
     */
    fun removeToolExecutionListener(listener: ToolExecutionListener) {
        toolExecutionListeners.remove(listener)
    }

    /**
     * 清除对话记忆
     */
    fun clearMemory() {
        initialize()
    }

    /**
     * 重新配置
     */
    fun reconfigure(): Result<Unit> {
        return initialize()
    }

    /**
     * 检查当前配置是否支持视觉
     */
    fun supportsVision(): Boolean {
        val config = currentConfig ?: return false
        return ModelFactory.supportsVision(config.providerId, config.modelId)
    }

    /**
     * 检查当前配置是否支持 Streaming
     * （当前版本返回 false，预留接口）
     */
    fun supportsStreaming(): Boolean {
        // LangChain4j 0.36.2 的 Streaming API 有变化
        // 返回 false 表示当前版本暂不支持
        return false
    }

    // ========== Assistant 接口定义 ==========

    /**
     * 普通对话 Assistant
     */
    interface Assistant {
        @SystemMessage(SYSTEM_PROMPT)
        fun chat(@UserMessage userMessage: String): String
    }

    /**
     * 视觉对话 Assistant
     */
    interface VisionAssistant {
        @SystemMessage(SYSTEM_PROMPT_VISION)
        fun chatWithImage(
            @UserMessage userMessage: String,
            base64Image: String
        ): String
    }
}
