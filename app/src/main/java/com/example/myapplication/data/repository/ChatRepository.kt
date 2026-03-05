package com.example.myapplication.data.repository

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.MessageDao
import com.example.myapplication.data.local.dao.SessionDao
import com.example.myapplication.data.local.entities.MessageEntity
import com.example.myapplication.data.local.entities.SessionEntity
import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.ChatSession
import com.example.myapplication.data.model.MessageType
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Repository for chat data operations
 */
class ChatRepository(database: AppDatabase) {

    private val sessionDao: SessionDao = database.sessionDao()
    private val messageDao: MessageDao = database.messageDao()
    private val gson = Gson()

    companion object {
        @Volatile
        private var instance: ChatRepository? = null

        fun getInstance(database: AppDatabase): ChatRepository {
            return instance ?: synchronized(this) {
                instance ?: ChatRepository(database).also { instance = it }
            }
        }
    }

    // Session operations
    fun getAllSessions(): Flow<List<ChatSession>> {
        return sessionDao.getAllSessions().map { entities ->
            entities.map { it.toChatSession() }
        }
    }

    suspend fun getSessionById(sessionId: String): ChatSession? {
        return sessionDao.getSessionById(sessionId)?.toChatSession()
    }

    suspend fun getLatestSession(): ChatSession? {
        return sessionDao.getLatestSession()?.toChatSession()
    }

    suspend fun createSession(title: String = "New Chat"): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insertSession(session.toSessionEntity())
        return session
    }

    suspend fun updateSession(session: ChatSession) {
        sessionDao.updateSession(session.toSessionEntity())
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title, System.currentTimeMillis())
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionById(sessionId)
    }

    // Message operations
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesForSession(sessionId).map { entities ->
            entities.mapNotNull { it.toChatMessage() }
        }
    }

    suspend fun getMessagesForSessionSync(sessionId: String): List<ChatMessage> {
        return messageDao.getMessagesForSessionSync(sessionId)
            .mapNotNull { it.toChatMessage() }
    }

    suspend fun addMessage(sessionId: String, message: ChatMessage) {
        val maxSortOrder = messageDao.getMaxSortOrder(sessionId) ?: -1
        val entity = message.toMessageEntity(sessionId, maxSortOrder + 1)
        messageDao.insertMessage(entity)

        // Update session's updatedAt time
        val session = sessionDao.getSessionById(sessionId)
        if (session != null) {
            sessionDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun addMessages(sessionId: String, messages: List<ChatMessage>) {
        val maxSortOrder = messageDao.getMaxSortOrder(sessionId) ?: -1
        val entities = messages.mapIndexed { index, message ->
            message.toMessageEntity(sessionId, maxSortOrder + index + 1)
        }
        messageDao.insertMessages(entities)

        // Update session's updatedAt time
        val session = sessionDao.getSessionById(sessionId)
        if (session != null) {
            sessionDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessageById(messageId)
    }

    suspend fun clearSessionMessages(sessionId: String) {
        messageDao.deleteMessagesForSession(sessionId)
    }

    // Extension functions for mapping
    private fun SessionEntity.toChatSession() = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ChatSession.toSessionEntity() = SessionEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun MessageEntity.toChatMessage(): ChatMessage? {
        return try {
            when (type) {
                MessageType.USER.name -> {
                    val data = gson.fromJson(contentJson, UserMessageData::class.java)
                    ChatMessage.UserMessage(
                        id = id,
                        timestamp = timestamp,
                        content = data.content,
                        attachedImageBase64 = data.attachedImageBase64
                    )
                }
                MessageType.AI.name -> {
                    val data = gson.fromJson(contentJson, AiMessageData::class.java)
                    ChatMessage.AiMessage(
                        id = id,
                        timestamp = timestamp,
                        content = data.content,
                        isSuccess = data.isSuccess,
                        errorMessage = data.errorMessage
                    )
                }
                MessageType.TOOL_CALL.name -> {
                    val data = gson.fromJson(contentJson, ToolCallData::class.java)
                    ChatMessage.ToolCallMessage(
                        id = id,
                        timestamp = timestamp,
                        toolName = data.toolName,
                        parameters = data.parameters,
                        result = data.result,
                        isSuccess = data.isSuccess
                    )
                }
                MessageType.SCREENSHOT.name -> {
                    val data = gson.fromJson(contentJson, ScreenshotData::class.java)
                    ChatMessage.ScreenshotMessage(
                        id = id,
                        timestamp = timestamp,
                        imageBase64 = data.imageBase64,
                        description = data.description
                    )
                }
                MessageType.STATUS.name -> {
                    val data = gson.fromJson(contentJson, StatusData::class.java)
                    ChatMessage.StatusMessage(
                        id = id,
                        timestamp = timestamp,
                        status = data.status,
                        isRunning = data.isRunning
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ChatMessage.toMessageEntity(sessionId: String, sortOrder: Int): MessageEntity {
        val type: String
        val contentJson: String

        when (this) {
            is ChatMessage.UserMessage -> {
                type = MessageType.USER.name
                contentJson = gson.toJson(UserMessageData(content, attachedImageBase64))
            }
            is ChatMessage.AiMessage -> {
                type = MessageType.AI.name
                contentJson = gson.toJson(AiMessageData(content, isSuccess, errorMessage))
            }
            is ChatMessage.ToolCallMessage -> {
                type = MessageType.TOOL_CALL.name
                contentJson = gson.toJson(ToolCallData(toolName, parameters, result, isSuccess))
            }
            is ChatMessage.ScreenshotMessage -> {
                type = MessageType.SCREENSHOT.name
                contentJson = gson.toJson(ScreenshotData(imageBase64, description))
            }
            is ChatMessage.StatusMessage -> {
                type = MessageType.STATUS.name
                contentJson = gson.toJson(StatusData(status, isRunning))
            }
        }

        return MessageEntity(
            id = id,
            sessionId = sessionId,
            type = type,
            contentJson = contentJson,
            timestamp = timestamp,
            sortOrder = sortOrder
        )
    }

    // Data classes for JSON serialization
    private data class UserMessageData(
        val content: String,
        val attachedImageBase64: String?
    )

    private data class AiMessageData(
        val content: String,
        val isSuccess: Boolean,
        val errorMessage: String?
    )

    private data class ToolCallData(
        val toolName: String,
        val parameters: Map<String, Any>,
        val result: String?,
        val isSuccess: Boolean
    )

    private data class ScreenshotData(
        val imageBase64: String,
        val description: String
    )

    private data class StatusData(
        val status: String,
        val isRunning: Boolean
    )
}
