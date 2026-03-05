package com.example.myapplication.data.repository

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.MessageDao
import com.example.myapplication.data.local.dao.SessionDao
import com.example.myapplication.data.local.entities.MessageEntity
import com.example.myapplication.data.local.entities.SessionEntity
import com.example.myapplication.data.mapper.ChatMapper.toChatMessage
import com.example.myapplication.data.mapper.ChatMapper.toChatSession
import com.example.myapplication.data.mapper.ChatMapper.toChatSessions
import com.example.myapplication.data.mapper.ChatMapper.toChatMessages
import com.example.myapplication.data.mapper.ChatMapper.toMessageEntity
import com.example.myapplication.data.mapper.ChatMapper.toSessionEntity
import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.ChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Repository for chat data operations
 * Uses Room Database for persistence
 */
class ChatRepository(database: AppDatabase) {

    private val sessionDao: SessionDao = database.sessionDao()
    private val messageDao: MessageDao = database.messageDao()

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
            entities.toChatSessions()
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
            entities.toChatMessages()
        }
    }

    suspend fun getMessagesForSessionSync(sessionId: String): List<ChatMessage> {
        return messageDao.getMessagesForSessionSync(sessionId).toChatMessages()
    }

    suspend fun addMessage(sessionId: String, message: ChatMessage) {
        val maxSortOrder = messageDao.getMaxSortOrder(sessionId) ?: -1
        val entity = message.toMessageEntity(sessionId, maxSortOrder + 1)
        messageDao.insertMessage(entity)
        updateSessionTimestamp(sessionId)
    }

    suspend fun addMessages(sessionId: String, messages: List<ChatMessage>) {
        val maxSortOrder = messageDao.getMaxSortOrder(sessionId) ?: -1
        val entities = messages.mapIndexed { index, message ->
            message.toMessageEntity(sessionId, maxSortOrder + index + 1)
        }
        messageDao.insertMessages(entities)
        updateSessionTimestamp(sessionId)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessageById(messageId)
    }

    suspend fun clearSessionMessages(sessionId: String) {
        messageDao.deleteMessagesForSession(sessionId)
    }

    private suspend fun updateSessionTimestamp(sessionId: String) {
        val session = sessionDao.getSessionById(sessionId)
        if (session != null) {
            sessionDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}
