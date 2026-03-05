package com.example.myapplication.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.toDescription
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message bubble component for displaying different message types
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timestamp = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = when (message) {
            is ChatMessage.UserMessage -> Arrangement.End
            else -> Arrangement.Start
        }
    ) {
        when (message) {
            is ChatMessage.UserMessage -> UserMessageBubble(message, timestamp)
            is ChatMessage.AiMessage -> AiMessageBubble(message, timestamp)
            is ChatMessage.ToolCallMessage -> ToolCallBubble(message, timestamp)
            is ChatMessage.ScreenshotMessage -> ScreenshotBubble(message, timestamp)
            is ChatMessage.StatusMessage -> StatusBubble(message, timestamp)
        }
    }
}

@Composable
private fun UserMessageBubble(message: ChatMessage.UserMessage, timestamp: String) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Attached image if present
        message.attachedImageBase64?.let { base64 ->
            Base64Image(
                base64 = base64,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .heightIn(max = 150.dp)
                    .clip(RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp))
            )
        }

        // Text content
        Surface(
            shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("message", message.content)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AiMessageBubble(message: ChatMessage.AiMessage, timestamp: String) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
            color = if (message.isSuccess)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (message.isSuccess) Icons.Default.AutoAwesome else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (message.isSuccess)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (message.isSuccess) "AI Response" else "Error",
                            fontWeight = FontWeight.Medium,
                            color = if (message.isSuccess)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("message", message.content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier.size(18.dp),
                            tint = if (message.isSuccess)
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = message.content,
                    color = if (message.isSuccess)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer
                )

                message.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolCallBubble(message: ChatMessage.ToolCallMessage, timestamp: String) {
    val context = LocalContext.current
    val toolCallText = "${message.toolName}: ${message.result ?: ""}"
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (message.isSuccess)
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        else
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when (message.toolName.lowercase()) {
                    "click" -> Icons.Default.AddLocation
                    "swipe" -> Icons.Default.Swipe
                    "input" -> Icons.Default.Edit
                    "navigate" -> Icons.Default.Navigation
                    else -> Icons.Default.Build
                },
                contentDescription = null,
                tint = if (message.isSuccess)
                    MaterialTheme.colorScheme.onTertiaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.toolName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = if (message.isSuccess)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer
                )
                message.result?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = if (message.isSuccess)
                            MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }
            }

            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("message", toolCallText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(16.dp),
                    tint = if (message.isSuccess)
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = if (message.isSuccess) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (message.isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ScreenshotBubble(message: ChatMessage.ScreenshotMessage, timestamp: String) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Base64Image(
                    base64 = message.imageBase64,
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                if (message.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusBubble(message: ChatMessage.StatusMessage, timestamp: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (message.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = message.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Base64Image(
    base64: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(base64) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    bitmap?.let {
        androidx.compose.foundation.Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } ?: Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = "Failed to load image",
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
