package com.example.myapplication.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger utility for consistent logging throughout the application
 */
class Logger(private val tag: String) {

    companion object {
        const val DEFAULT_TAG = "MyApplication"
        const val MAX_LOG_ENTRIES = 500

        // Log levels
        var isDebugEnabled = true
        var isVerboseEnabled = false

        // In-memory log buffer for UI display
        private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
        val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

        // File logging
        private var logFile: File? = null
        private var fileWriter: FileWriter? = null

        /**
         * Enable file logging
         */
        fun enableFileLogging(directory: File) {
            try {
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                logFile = File(directory, "app_${getDateString()}.log")
                fileWriter = FileWriter(logFile, true)
            } catch (e: Exception) {
                Log.e(DEFAULT_TAG, "Failed to enable file logging", e)
            }
        }

        /**
         * Disable file logging
         */
        fun disableFileLogging() {
            try {
                fileWriter?.close()
                fileWriter = null
                logFile = null
            } catch (e: Exception) {
                Log.e(DEFAULT_TAG, "Failed to disable file logging", e)
            }
        }

        /**
         * Get log file
         */
        fun getLogFile(): File? = logFile

        /**
         * Clear all logs
         */
        fun clearLogs() {
            _logEntries.value = emptyList()
        }

        /**
         * Export logs as plain text string (for clipboard)
         */
        fun exportLogsAsString(): String {
            val sb = StringBuilder()
            sb.appendLine("=== App Logs Export ===")
            sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            sb.appendLine("Entries: ${_logEntries.value.size}")
            sb.appendLine()

            _logEntries.value.forEach { entry ->
                val levelStr = when (entry.level) {
                    LogLevel.ERROR -> "E"
                    LogLevel.WARN -> "W"
                    LogLevel.INFO -> "I"
                    LogLevel.DEBUG -> "D"
                    LogLevel.VERBOSE -> "V"
                }
                sb.appendLine("${entry.timestamp} $levelStr/${entry.tag}: ${entry.message}")
                entry.throwable?.let {
                    sb.appendLine("  Throwable: $it")
                }
            }

            return sb.toString()
        }

        private fun getDateString(): String {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            return sdf.format(Date())
        }

        private fun getTimeString(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date())
        }

        private fun addLogEntry(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
            val entry = LogEntry(
                timestamp = getTimeString(),
                tag = tag,
                level = level,
                message = message,
                throwable = throwable?.stackTraceToString()
            )

            val currentList = _logEntries.value.toMutableList()
            currentList.add(entry)

            // Keep only last MAX_LOG_ENTRIES
            if (currentList.size > MAX_LOG_ENTRIES) {
                _logEntries.value = currentList.takeLast(MAX_LOG_ENTRIES)
            } else {
                _logEntries.value = currentList
            }
        }

        private fun writeToFile(level: String, tag: String, message: String) {
            try {
                val writer = fileWriter ?: return
                val logLine = "${getTimeString()} $level/$tag: $message\n"
                writer.write(logLine)
                writer.flush()
            } catch (e: Exception) {
                // Ignore file logging errors
            }
        }
    }

    /**
     * Log verbose message
     */
    fun v(message: String, throwable: Throwable? = null) {
        if (isVerboseEnabled) {
            if (throwable != null) {
                Log.v(tag, message, throwable)
            } else {
                Log.v(tag, message)
            }
            addLogEntry(LogLevel.VERBOSE, tag, message, throwable)
            writeToFile("V", tag, message + if (throwable != null) ": ${throwable.message}" else "")
        }
    }

    /**
     * Log debug message
     */
    fun d(message: String, throwable: Throwable? = null) {
        if (isDebugEnabled) {
            if (throwable != null) {
                Log.d(tag, message, throwable)
            } else {
                Log.d(tag, message)
            }
            addLogEntry(LogLevel.DEBUG, tag, message, throwable)
            writeToFile("D", tag, message + if (throwable != null) ": ${throwable.message}" else "")
        }
    }

    /**
     * Log info message
     */
    fun i(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
        addLogEntry(LogLevel.INFO, tag, message, throwable)
        writeToFile("I", tag, message + if (throwable != null) ": ${throwable.message}" else "")
    }

    /**
     * Log warning message
     */
    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        addLogEntry(LogLevel.WARN, tag, message, throwable)
        writeToFile("W", tag, message + if (throwable != null) ": ${throwable.message}" else "")
    }

    /**
     * Log error message
     */
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        addLogEntry(LogLevel.ERROR, tag, message, throwable)
        writeToFile("E", tag, message + if (throwable != null) ": ${throwable.message}" else "")
    }

    /**
     * Log assertion message
     */
    fun wtf(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.wtf(tag, message, throwable)
        } else {
            Log.wtf(tag, message)
        }
        addLogEntry(LogLevel.ERROR, tag, message, throwable)
        writeToFile("WTF", tag, message + if (throwable != null) ": ${throwable.message}" else "")
    }
}

/**
 * Log level enum
 */
enum class LogLevel {
    ERROR, WARN, INFO, DEBUG, VERBOSE
}

/**
 * Data class representing a log entry
 */
data class LogEntry(
    val id: String = "${System.currentTimeMillis()}_${(0..9999).random()}",
    val timestamp: String,
    val tag: String,
    val level: LogLevel,
    val message: String,
    val throwable: String? = null
)

/**
 * Extension functions for logging with default tag
 */
fun logV(message: String, tag: String = Logger.DEFAULT_TAG) {
    Logger(tag).v(message)
}

fun logD(message: String, tag: String = Logger.DEFAULT_TAG) {
    Logger(tag).d(message)
}

fun logI(message: String, tag: String = Logger.DEFAULT_TAG) {
    Logger(tag).i(message)
}

fun logW(message: String, tag: String = Logger.DEFAULT_TAG) {
    Logger(tag).w(message)
}

fun logE(message: String, tag: String = Logger.DEFAULT_TAG, throwable: Throwable? = null) {
    Logger(tag).e(message, throwable)
}
