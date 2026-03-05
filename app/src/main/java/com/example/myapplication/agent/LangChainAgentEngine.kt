package com.example.myapplication.agent

import android.content.Context
import com.example.myapplication.agent.langchain.ModelFactory
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.mapper.ApiConfigMapper.toProviderConfig
import com.example.myapplication.utils.Logger
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.SystemMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LangChainAgentEngine(private val context: Context) {

    companion object {
        private const val TAG = "LangChainAgentEngine"
        private const val MAX_MESSAGES = 20
        
        @Volatile private var instance: LangChainAgentEngine? = null
        @Volatile private var chuckerInterceptor: okhttp3.Interceptor? = null

        fun getInstance(context: Context): LangChainAgentEngine {
            return instance ?: synchronized(this) {
                instance ?: LangChainAgentEngine(context.applicationContext).also { instance = it }
            }
        }

        fun setChuckerInterceptor(interceptor: okhttp3.Interceptor) {
            chuckerInterceptor = interceptor
        }
    }

    private val logger = Logger(TAG)
    private val apiConfigDao = AppDatabase.getDatabase(context).apiConfigDao()

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var assistant: Assistant? = null

    fun initialize(): Result<Unit> {
        return try {
            val configEntity = runCatching { 
                kotlinx.coroutines.runBlocking { 
                    apiConfigDao.getActiveConfig() 
                } 
            }.getOrNull()
            
            if (configEntity == null) {
                return Result.failure(Exception("未配置 API，请先在设置中配置"))
            }

            val chatModel = ModelFactory.createChatModel(configEntity.toProviderConfig())

            val tools = AndroidTools(context.applicationContext)

            val chatMemory = MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES)

            assistant = AiServices.builder(Assistant::class.java)
                .chatLanguageModel(chatModel)
                .tools(tools)
                .chatMemory(chatMemory)
                .build()

            _state.value = AgentState(state = AgentStateType.READY)
            logger.d("Agent 初始化成功：provider=${configEntity.providerId}, model=${configEntity.modelId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Agent 初始化失败：${e.message}", e)
            _state.value = AgentState(state = AgentStateType.ERROR, error = e.message)
            Result.failure(e)
        }
    }

    fun execute(task: String, callback: (AgentResult) -> Unit) {
        if (_state.value.state != AgentStateType.READY) {
            callback(AgentResult.error("Agent 未就绪，请先初始化"))
            return
        }

        val assistant = this.assistant ?: run {
            callback(AgentResult.error("Agent 未初始化"))
            return
        }

        _state.value = AgentState(state = AgentStateType.RUNNING)
        logger.d("开始执行任务：$task")

        try {
            val result = assistant.chat(task)

            when {
                result.startsWith("FINISH:") -> {
                    val summary = result.substringAfter("FINISH:").trim()
                    _state.value = AgentState(state = AgentStateType.COMPLETED, result = summary)
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
        } catch (e: Exception) {
            logger.e("任务执行失败：${e.message}", e)
            _state.value = AgentState(state = AgentStateType.ERROR, error = e.message)
            callback(AgentResult.error(e.message ?: "未知错误"))
        }
    }

    fun cancel() {
        logger.d("取消任务")
        _state.value = AgentState(state = AgentStateType.IDLE)
    }

    fun clearMemory() {
        initialize()
    }

    fun reconfigure(): Result<Unit> {
        return initialize()
    }

    data class AgentState(
        val state: AgentStateType = AgentStateType.IDLE,
        val result: String? = null,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class AgentStateType {
        IDLE,
        READY,
        RUNNING,
        COMPLETED,
        ERROR,
        CANCELLED
    }

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

    interface Assistant {
        @SystemMessage("""
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
        """)
        fun chat(userMessage: String): String
    }
}
