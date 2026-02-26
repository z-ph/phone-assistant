package com.example.myapplication.agent

import com.example.myapplication.agent.models.ToolCallInfo
import com.example.myapplication.config.AppConfig.Context as ContextConfig
import com.example.myapplication.utils.Logger
import com.google.gson.Gson

/**
 * Execution record for context history
 */
data class ExecutionRecord(
    val role: String,  // "user", "assistant", "tool"
    val content: String,
    val toolCalls: List<ToolCallInfo>? = null,
    val toolCallId: String? = null,
    val hasImage: Boolean = false
)

/**
 * Smart context manager for agent conversations
 *
 * Features:
 * - Message history management with limits
 * - Image message restrictions
 * - Automatic summarization
 * - Function calling compatible message format
 */
class ContextManager {

    companion object {
        private const val TAG = "ContextManager"
    }

    private val logger = Logger(TAG)
    private val gson = Gson()

    // Message history
    private val messages = mutableListOf<ExecutionRecord>()

    // Current image count
    private var imageCount = 0

    /**
     * Build messages for API call
     *
     * @param systemPrompt System prompt
     * @param goal Current task goal
     * @param currentScreenBase64 Optional current screen image (base64)
     * @return List of messages in OpenAI-compatible format
     */
    fun buildMessages(
        systemPrompt: String,
        goal: String,
        currentScreenBase64: String? = null
    ): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()

        // 1. Add system message
        result.add(mapOf(
            "role" to "system",
            "content" to systemPrompt
        ))

        // 2. Add history (excluding old images if needed)
        val historyToAdd = pruneHistory()
        historyToAdd.forEach { record ->
            result.add(recordToMessage(record))
        }

        // 3. Add current user goal with optional screenshot
        if (currentScreenBase64 != null) {
            result.add(buildUserMessageWithImage(goal, currentScreenBase64))
        } else {
            result.add(mapOf(
                "role" to "user",
                "content" to goal
            ))
        }

        return result
    }

    /**
     * Convert ExecutionRecord to API message format
     */
    private fun recordToMessage(record: ExecutionRecord): Map<String, Any> {
        return when (record.role) {
            "tool" -> mapOf(
                "role" to "tool",
                "tool_call_id" to (record.toolCallId ?: ""),
                "content" to record.content
            )
            "assistant" -> {
                if (record.toolCalls != null && record.toolCalls.isNotEmpty()) {
                    mapOf(
                        "role" to "assistant",
                        "content" to record.content,
                        "tool_calls" to record.toolCalls.map { tc ->
                            mapOf(
                                "id" to tc.id,
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to tc.name,
                                    "arguments" to gson.toJson(tc.parameters)
                                )
                            )
                        }
                    )
                } else {
                    mapOf(
                        "role" to "assistant",
                        "content" to record.content
                    )
                }
            }
            else -> mapOf(
                "role" to record.role,
                "content" to record.content
            )
        }
    }

    /**
     * Build user message with image
     */
    private fun buildUserMessageWithImage(text: String, base64Image: String): Map<String, Any> {
        return mapOf(
            "role" to "user",
            "content" to listOf(
                mapOf(
                    "type" to "text",
                    "text" to text
                ),
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to "data:image/jpeg;base64,$base64Image"
                    )
                )
            )
        )
    }

    /**
     * Prune history to fit within limits
     */
    private fun pruneHistory(): List<ExecutionRecord> {
        if (messages.size <= ContextConfig.MAX_MESSAGES) {
            return messages.toList()
        }

        // Keep recent messages, prefer removing old image messages
        val recent = messages.takeLast(ContextConfig.MAX_MESSAGES)

        // If still too many images, remove oldest images
        var imgCount = recent.count { it.hasImage }
        if (imgCount > ContextConfig.MAX_IMAGE_MESSAGES) {
            val pruned = mutableListOf<ExecutionRecord>()
            var imagesToKeep = ContextConfig.MAX_IMAGE_MESSAGES

            for (record in recent) {
                if (record.hasImage && imagesToKeep <= 0) {
                    // Replace image message with text-only version
                    pruned.add(record.copy(
                        hasImage = false,
                        content = "[旧截图已移除] ${record.content}"
                    ))
                } else {
                    if (record.hasImage) imagesToKeep--
                    pruned.add(record)
                }
            }
            return pruned
        }

        return recent
    }

    /**
     * Add execution record to history
     */
    fun addExecution(record: ExecutionRecord) {
        messages.add(record)
        if (record.hasImage) {
            imageCount++
        }
        logger.d("Added ${record.role} message, total: ${messages.size}, images: $imageCount")
    }

    /**
     * Add user message
     */
    fun addUserMessage(content: String, hasImage: Boolean = false) {
        addExecution(ExecutionRecord(
            role = "user",
            content = content,
            hasImage = hasImage
        ))
    }

    /**
     * Add assistant message
     */
    fun addAssistantMessage(
        content: String,
        toolCalls: List<ToolCallInfo>? = null
    ) {
        addExecution(ExecutionRecord(
            role = "assistant",
            content = content,
            toolCalls = toolCalls
        ))
    }

    /**
     * Add tool result message
     */
    fun addToolResult(toolCallId: String, result: String) {
        addExecution(ExecutionRecord(
            role = "tool",
            content = result,
            toolCallId = toolCallId
        ))
    }

    /**
     * Check if summarization is needed
     */
    fun needsSummarization(): Boolean {
        return messages.size >= ContextConfig.SUMMARY_THRESHOLD
    }

    /**
     * Summarize old messages to reduce context size
     * Returns true if summarization was performed
     */
    fun summarizeIfNeeded(): Boolean {
        if (!needsSummarization()) {
            return false
        }

        // Keep the most recent messages, summarize the rest
        val toSummarize = messages.dropLast(ContextConfig.MAX_MESSAGES / 2)
        val toKeep = messages.takeLast(ContextConfig.MAX_MESSAGES / 2)

        if (toSummarize.isEmpty()) {
            return false
        }

        // Create a summary of old messages
        val summary = buildSummary(toSummarize)

        // Clear and rebuild with summary
        messages.clear()
        imageCount = 0

        // Add summary as a system-like context message
        messages.add(ExecutionRecord(
            role = "user",
            content = "[历史摘要] $summary"
        ))

        // Add kept messages
        toKeep.forEach { record ->
            messages.add(record)
            if (record.hasImage) imageCount++
        }

        logger.d("Summarized ${toSummarize.size} old messages")
        return true
    }

    /**
     * Build a summary of messages
     */
    private fun buildSummary(records: List<ExecutionRecord>): String {
        val actions = mutableListOf<String>()
        var lastGoal = ""

        records.forEach { record ->
            when (record.role) {
                "user" -> {
                    if (!record.content.startsWith("[")) {
                        lastGoal = record.content.take(50)
                    }
                }
                "assistant" -> {
                    record.toolCalls?.forEach { tc ->
                        actions.add(tc.name)
                    }
                }
            }
        }

        val distinctActions = actions.distinct().take(10)
        return buildString {
            if (lastGoal.isNotEmpty()) {
                append("目标: $lastGoal. ")
            }
            if (distinctActions.isNotEmpty()) {
                append("执行过的操作: ${distinctActions.joinToString(", ")}")
            }
        }
    }

    /**
     * Get current message count
     */
    fun getMessageCount(): Int = messages.size

    /**
     * Get current image count
     */
    fun getImageCount(): Int = imageCount

    /**
     * Clear all messages
     */
    fun clear() {
        messages.clear()
        imageCount = 0
        logger.d("Context cleared")
    }

    /**
     * Get messages for debugging
     */
    fun getMessagesForDebug(): List<ExecutionRecord> = messages.toList()
}
