package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.myapplication.network.NetworkMonitor
import com.example.myapplication.network.NetworkRequest
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "NetworkScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val networkMonitor = remember { NetworkMonitor.getInstance() }
    val requests by remember { mutableStateOf(networkMonitor.requests) }
    var selectedRequest by remember { mutableStateOf<NetworkRequest?>(null) }
    var filterStatus by remember { mutableStateOf("all") }
    var showFilterDropdown by remember { mutableStateOf(false) }

    val filteredRequests = remember(requests, filterStatus) {
        when (filterStatus) {
            "success" -> requests.filter { it.status in 200..299 }
            "error" -> requests.filter { it.status >= 400 || it.status == -1 }
            "pending" -> requests.filter { it.status == -1 && it.error == null }
            else -> requests
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Network Monitor") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showFilterDropdown = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filter")
                        }
                        DropdownMenu(
                            expanded = showFilterDropdown,
                            onDismissRequest = { showFilterDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All") },
                                onClick = {
                                    filterStatus = "all"
                                    showFilterDropdown = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.AllInclusive, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Success (2xx)") },
                                onClick = {
                                    filterStatus = "success"
                                    showFilterDropdown = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Green)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Errors (4xx/5xx)") },
                                onClick = {
                                    filterStatus = "error"
                                    showFilterDropdown = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red)
                                }
                            )
                        }
                    }
                    IconButton(onClick = {
                        networkMonitor.clear()
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Summary bar
            SummaryBar(requests = requests)

            // Requests list
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredRequests) { request ->
                    RequestListItem(
                        request = request,
                        onClick = { selectedRequest = request }
                    )
                    HorizontalDivider()
                }
                
                if (filteredRequests.isEmpty()) {
                    item {
                        EmptyState()
                    }
                }
            }
        }
    }

    // Request detail dialog
    selectedRequest?.let { request ->
        RequestDetailDialog(
            request = request,
            onDismiss = { selectedRequest = null }
        )
    }
}

@Composable
private fun SummaryBar(requests: List<NetworkRequest>) {
    val total = requests.size
    val success = requests.count { it.status in 200..299 }
    val errors = requests.count { it.status >= 400 || it.status == -1 }
    val avgDuration = requests.filter { it.duration > 0 }.map { it.duration.toLong() }.average().toLong()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryChip("Total: $total", Color.Gray)
        SummaryChip("✓ $success", Color.Green)
        SummaryChip("✗ $errors", Color.Red)
        SummaryChip("Avg: ${avgDuration}ms", Color.Blue)
    }
}

@Composable
private fun SummaryChip(label: String, color: Color) {
    Surface(
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
private fun RequestListItem(
    request: NetworkRequest,
    onClick: () -> Unit
) {
    val statusColor = when {
        request.status == -1 -> Color.Gray
        request.status in 200..299 -> Color.Green
        request.status in 300..399 -> Color.Blue
        request.status in 400..499 -> Color(0xFFFFA000)
        request.status >= 500 -> Color.Red
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status code
        Box(
            modifier = Modifier
                .size(50.dp, 36.dp)
                .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (request.status == -1) "-" else request.status.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                maxLines = 1
            )
        }

        // Method and URL
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = request.method,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (request.method) {
                        "GET" -> Color.Blue
                        "POST" -> Color.Green
                        "PUT" -> Color(0xFFFFA000)
                        "DELETE" -> Color.Red
                        else -> Color.Gray
                    }
                )
                Text(
                    text = request.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${request.duration}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatBytes(request.requestSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatBytes(request.responseSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No network requests",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "HTTP requests will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun RequestDetailDialog(
    request: NetworkRequest,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${request.method} ${request.url}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Status: ${request.status} ${request.statusText}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                // Tabs
                var selectedTab by remember { mutableStateOf(0) }
                val tabs = listOf("Request", "Response", "Details")

                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                // Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> RequestTabContent(request)
                        1 -> ResponseTabContent(request)
                        2 -> DetailsTabContent(request)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestTabContent(request: NetworkRequest) {
    SectionTitle("Headers")
    HeadersSection(request.requestHeaders)
    
    if (!request.requestBody.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("Body")
        JsonBody(request.requestBody)
    }
}

@Composable
private fun ResponseTabContent(request: NetworkRequest) {
    val response = request.response ?: run {
        Text("No response yet", color = MaterialTheme.colorScheme.error)
        return
    }

    SectionTitle("Headers")
    HeadersSection(response.headers)
    
    if (!response.body.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("Body")
        JsonBody(response.body)
    }
}

@Composable
private fun DetailsTabContent(request: NetworkRequest) {
    DetailRow("URL", request.url)
    DetailRow("Method", request.method)
    DetailRow("Status", "${request.status} ${request.statusText}")
    DetailRow("Duration", "${request.duration}ms")
    DetailRow("Request Size", formatBytes(request.requestSize))
    DetailRow("Response Size", formatBytes(request.responseSize))
    DetailRow("Timestamp", formatTimestamp(request.timestamp))
    
    if (request.error != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Error: ${request.error}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun HeadersSection(headers: Map<String, String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            headers.forEach { (key, value) ->
                Row {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun JsonBody(body: String) {
    val formattedBody = try {
        val json = com.google.gson.JsonParser.parseString(body)
        com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json)
    } catch (e: Exception) {
        body
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Text(
            text = formattedBody,
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}
