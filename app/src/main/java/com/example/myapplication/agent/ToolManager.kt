package com.example.myapplication.agent

import android.content.Context
import com.example.myapplication.config.AppConfig.Coordinates as Coords
import com.example.myapplication.utils.Logger
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Tool definition - describes a tool that AI can call
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val example: String
)

/**
 * Tool parameter definition
 */
data class ToolParameter(
    val name: String,
    val type: String,  // "int", "float", "string", "boolean"
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null
)

/**
 * Parsed tool call from AI output
 */
data class ToolCall(
    val name: String,
    val parameters: Map<String, Any>,
    val rawMatch: String
)

/**
 * Manages tool definitions and system prompt generation
 * AI decides when to call tools, not the program
 */
class ToolManager(private val context: Context) {

    companion object {
        private const val TAG = "ToolManager"
        private const val PREFS_NAME = "tool_manager_prefs"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"

        // Default tools - AI decides when to use them
        val DEFAULT_TOOLS = listOf(
            // === 手势操作 ===
            ToolDefinition(
                name = "click",
                description = "点击屏幕指定坐标位置",
                parameters = listOf(
                    ToolParameter("x", "int", "X坐标 (0-${Coords.NORMALIZED_WIDTH})", true),
                    ToolParameter("y", "int", "Y坐标 (0-${Coords.NORMALIZED_HEIGHT})", true)
                ),
                example = "click(540, 1200)"
            ),
            ToolDefinition(
                name = "long_click",
                description = "长按屏幕指定坐标",
                parameters = listOf(
                    ToolParameter("x", "int", "X坐标 (0-${Coords.NORMALIZED_WIDTH})", true),
                    ToolParameter("y", "int", "Y坐标 (0-${Coords.NORMALIZED_HEIGHT})", true),
                    ToolParameter("duration", "int", "长按时长(毫秒)，默认500", false)
                ),
                example = "long_click(540, 1200)"
            ),
            ToolDefinition(
                name = "double_click",
                description = "双击屏幕指定坐标",
                parameters = listOf(
                    ToolParameter("x", "int", "X坐标 (0-${Coords.NORMALIZED_WIDTH})", true),
                    ToolParameter("y", "int", "Y坐标 (0-${Coords.NORMALIZED_HEIGHT})", true)
                ),
                example = "double_click(540, 1200)"
            ),
            ToolDefinition(
                name = "swipe",
                description = "在屏幕上滑动",
                parameters = listOf(
                    ToolParameter("direction", "string", "滑动方向", true, enum = listOf("up", "down", "left", "right")),
                    ToolParameter("distance", "int", "滑动距离(像素)", true)
                ),
                example = "swipe(\"up\", 500)"
            ),
            ToolDefinition(
                name = "drag",
                description = "从一个坐标拖拽到另一个坐标",
                parameters = listOf(
                    ToolParameter("start_x", "int", "起点X坐标", true),
                    ToolParameter("start_y", "int", "起点Y坐标", true),
                    ToolParameter("end_x", "int", "终点X坐标", true),
                    ToolParameter("end_y", "int", "终点Y坐标", true)
                ),
                example = "drag(100, 500, 100, 1500)"
            ),
            ToolDefinition(
                name = "type",
                description = "输入文本到当前焦点输入框",
                parameters = listOf(
                    ToolParameter("text", "string", "要输入的文本内容", true)
                ),
                example = "type(\"你好世界\")"
            ),

            // === 全局操作 ===
            ToolDefinition(
                name = "back",
                description = "按下返回键",
                parameters = emptyList(),
                example = "back()"
            ),
            ToolDefinition(
                name = "home",
                description = "按下Home键，回到主屏幕",
                parameters = emptyList(),
                example = "home()"
            ),
            ToolDefinition(
                name = "recents",
                description = "打开最近任务列表",
                parameters = emptyList(),
                example = "recents()"
            ),
            ToolDefinition(
                name = "open_notifications",
                description = "打开通知栏",
                parameters = emptyList(),
                example = "open_notifications()"
            ),
            ToolDefinition(
                name = "open_quick_settings",
                description = "打开快速设置面板",
                parameters = emptyList(),
                example = "open_quick_settings()"
            ),
            ToolDefinition(
                name = "lock_screen",
                description = "锁定屏幕",
                parameters = emptyList(),
                example = "lock_screen()"
            ),
            ToolDefinition(
                name = "take_screenshot",
                description = "触发系统截屏",
                parameters = emptyList(),
                example = "take_screenshot()"
            ),

            // === 滚动操作 ===
            ToolDefinition(
                name = "scroll_forward",
                description = "向前滚动(如下一页)",
                parameters = emptyList(),
                example = "scroll_forward()"
            ),
            ToolDefinition(
                name = "scroll_backward",
                description = "向后滚动(如上一页)",
                parameters = emptyList(),
                example = "scroll_backward()"
            ),

            // === 剪贴板操作 ===
            ToolDefinition(
                name = "copy_to_clipboard",
                description = "复制文本到剪贴板",
                parameters = listOf(
                    ToolParameter("text", "string", "要复制的文本", true)
                ),
                example = "copy_to_clipboard(\"复制的内容\")"
            ),
            ToolDefinition(
                name = "paste",
                description = "粘贴剪贴板内容到当前焦点输入框",
                parameters = emptyList(),
                example = "paste()"
            ),

            // === 控制操作 ===
            ToolDefinition(
                name = "capture_screen",
                description = "捕获当前屏幕截图。当你需要查看屏幕内容时调用此工具。",
                parameters = emptyList(),
                example = "capture_screen()"
            ),
            ToolDefinition(
                name = "wait",
                description = "等待指定时间，用于等待页面加载或动画完成",
                parameters = listOf(
                    ToolParameter("ms", "int", "等待时间(毫秒)", true)
                ),
                example = "wait(1000)"
            ),
            ToolDefinition(
                name = "open_app",
                description = "打开指定的应用程序。可以输入包名(如com.tencent.mm)或应用名称(如微信)。如果找不到应用，先调用list_apps查看已安装应用",
                parameters = listOf(
                    ToolParameter("package_name", "string", "应用包名或应用名称", true)
                ),
                example = "open_app(\"微信\")"
            ),
            ToolDefinition(
                name = "list_apps",
                description = "列出手机上已安装的所有应用程序，返回应用名称和包名",
                parameters = emptyList(),
                example = "list_apps()"
            ),

            // === 交互操作 ===
            ToolDefinition(
                name = "reply",
                description = "回复用户消息。当你需要与用户交流、回答问题或报告进度时使用此工具。",
                parameters = listOf(
                    ToolParameter("message", "string", "要发送给用户的消息", true)
                ),
                example = "reply(\"好的，我来帮你打开微信\")"
            ),
            ToolDefinition(
                name = "finish",
                description = "任务完成，结束执行并报告结果",
                parameters = listOf(
                    ToolParameter("summary", "string", "任务完成总结", true)
                ),
                example = "finish(\"已成功打开微信并进入聊天界面\")"
            )
        )
    }

    private val logger = Logger(TAG)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val systemPromptBuilder = SystemPromptBuilder()

    // Tool call patterns - supports multiple formats
    private val toolBlockPattern = Pattern.compile(
        "```tool\\s*([\\s\\S]*?)\\s*```",
        Pattern.MULTILINE
    )
    private val toolCallPattern = Pattern.compile(
        """(\w+)\s*\(([^)]*)\)""",
        Pattern.MULTILINE
    )

    /**
     * Get all available tools
     */
    fun getAvailableTools(): List<ToolDefinition> {
        return DEFAULT_TOOLS
    }

    /**
     * Generate system prompt with tool definitions
     */
    fun generateSystemPrompt(): String {
        val customPrompt = prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, null)
        return if (customPrompt.isNullOrBlank()) {
            getDefaultSystemPrompt()
        } else {
            customPrompt
        }
    }

    /**
     * Get default system prompt with tool definitions
     */
    fun getDefaultSystemPrompt(): String {
        return systemPromptBuilder.buildPrompt()
    }

    /**
     * Build the tools section of system prompt
     */
    private fun buildToolsSection(): String {
        val sb = StringBuilder("## 可用工具：\n\n")

        DEFAULT_TOOLS.forEach { tool ->
            sb.append("### ${tool.name}\n")
            sb.append("${tool.description}\n")

            if (tool.parameters.isNotEmpty()) {
                sb.append("参数：\n")
                tool.parameters.forEach { param ->
                    val required = if (param.required) "必填" else "可选"
                    val enumInfo = param.enum?.let { " 可选值: ${it.joinToString(",")}" } ?: ""
                    sb.append("- ${param.name} (${param.type}, $required): ${param.description}$enumInfo\n")
                }
            }
            sb.append("示例: `${tool.example}`\n\n")
        }

        return sb.toString()
    }

    /**
     * Parse tool calls from AI response
     */
    fun parseToolCalls(response: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()

        // First try to find tool blocks: ```tool ... ```
        val blockMatcher = toolBlockPattern.matcher(response)
        while (blockMatcher.find()) {
            val toolContent = blockMatcher.group(1)?.trim() ?: continue
            parseSingleToolCall(toolContent)?.let { calls.add(it) }
        }

        // If no tool blocks found, try inline tool calls
        if (calls.isEmpty()) {
            val matcher = toolCallPattern.matcher(response)
            while (matcher.find()) {
                val toolName = matcher.group(1) ?: continue
                val paramsStr = matcher.group(2) ?: ""

                // Only parse known tools
                if (DEFAULT_TOOLS.any { it.name == toolName }) {
                    val params = parseParameters(paramsStr, toolName)
                    calls.add(ToolCall(
                        name = toolName,
                        parameters = params,
                        rawMatch = matcher.group(0) ?: ""
                    ))
                }
            }
        }

        return calls
    }

    /**
     * Parse a single tool call
     */
    private fun parseSingleToolCall(content: String): ToolCall? {
        val matcher = toolCallPattern.matcher(content.trim())
        if (matcher.find()) {
            val toolName = matcher.group(1) ?: return null
            val paramsStr = matcher.group(2) ?: ""

            if (DEFAULT_TOOLS.any { it.name == toolName }) {
                val params = parseParameters(paramsStr, toolName)
                return ToolCall(
                    name = toolName,
                    parameters = params,
                    rawMatch = content
                )
            }
        }
        return null
    }

    /**
     * Parse parameters from parameter string
     */
    private fun parseParameters(paramsStr: String, toolName: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        if (paramsStr.isBlank()) return params

        val tool = DEFAULT_TOOLS.find { it.name == toolName } ?: return params
        val parts = splitParameters(paramsStr)

        tool.parameters.forEachIndexed { index, paramDef ->
            if (index < parts.size) {
                val value = parts[index].trim()
                params[paramDef.name] = parseValue(value, paramDef.type)
            }
        }

        return params
    }

    /**
     * Split parameter string respecting quotes
     */
    private fun splitParameters(paramsStr: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '"'

        for (char in paramsStr) {
            when {
                char == '"' || char == '\'' -> {
                    if (!inQuotes) {
                        inQuotes = true
                        quoteChar = char
                    } else if (char == quoteChar) {
                        inQuotes = false
                    } else {
                        current.append(char)
                    }
                }
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    /**
     * Parse a single value based on type
     */
    private fun parseValue(value: String, type: String): Any {
        return when (type) {
            "int" -> value.trim().toIntOrNull() ?: 0
            "float" -> value.trim().toFloatOrNull() ?: 0f
            "boolean" -> value.trim().lowercase() == "true"
            "string" -> {
                val trimmed = value.trim()
                if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                    (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                    trimmed.substring(1, trimmed.length - 1)
                } else {
                    trimmed
                }
            }
            else -> value.trim()
        }
    }

    /**
     * Save custom system prompt
     */
    fun saveCustomSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, prompt).apply()
        logger.d("Saved custom system prompt")
    }

    /**
     * Reset to default system prompt
     */
    fun resetToDefault() {
        prefs.edit().remove(KEY_CUSTOM_SYSTEM_PROMPT).apply()
        logger.d("Reset to default system prompt")
    }

    /**
     * Check if using custom prompt
     */
    fun isUsingCustomPrompt(): Boolean {
        return !prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, null).isNullOrBlank()
    }
}
