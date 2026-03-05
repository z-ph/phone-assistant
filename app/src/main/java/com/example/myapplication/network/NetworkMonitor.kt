package com.example.myapplication.network

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.utils.Logger
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NetworkMonitor"

class NetworkMonitor private constructor() {

    companion object {
        @Volatile
        private var instance: NetworkMonitor? = null

        fun getInstance(): NetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkMonitor().also { instance = it }
            }
        }
    }

    private val logger = Logger(TAG)
    private val _requests = mutableStateListOf<NetworkRequest>()
    private val requestMap = ConcurrentHashMap<String, NetworkRequest>()

    val requests: List<NetworkRequest> get() = _requests.toList()

    fun addRequest(request: NetworkRequest) {
        requestMap[request.id] = request
        _requests.add(0, request)
        
        // Keep only last 100 requests to avoid memory issues
        if (_requests.size > 100) {
            val removed = _requests.removeAt(_requests.size - 1)
            requestMap.remove(removed.id)
        }
        
        logger.d("Added request: ${request.method} ${request.url} - Status: ${request.response?.status}")
    }

    fun updateRequest(id: String, update: (NetworkRequest) -> NetworkRequest) {
        requestMap[id]?.let { existing ->
            val updated = update(existing)
            requestMap[id] = updated
            
            val index = _requests.indexOfFirst { it.id == id }
            if (index >= 0) {
                _requests[index] = updated
            }
        }
    }

    fun clear() {
        _requests.clear()
        requestMap.clear()
        logger.d("Network monitor cleared")
    }

    fun getInterceptor(): Interceptor {
        return NetworkInterceptor(this)
    }

    private class NetworkInterceptor(
        private val monitor: NetworkMonitor
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.currentTimeMillis()
            
            val requestBodyString = request.body?.getBodyAsString()
            
            val networkRequest = NetworkRequest(
                id = generateRequestId(),
                url = request.url.toString(),
                method = request.method,
                requestHeaders = request.headers.toMap(),
                requestSize = request.body?.contentLength() ?: 0,
                requestBody = requestBodyString
            )

            try {
                val response: Response
                try {
                    response = chain.proceed(request)
                } catch (e: IOException) {
                    val endTime = System.currentTimeMillis()
                    monitor.addRequest(
                        networkRequest.copy(
                            duration = endTime - startTime,
                            response = NetworkResponse(
                                status = -1,
                                statusText = "Network Error: ${e.message}",
                                headers = emptyMap(),
                                body = null
                            ),
                            timestamp = System.currentTimeMillis(),
                            error = e.message
                        )
                    )
                    throw e
                }

                val endTime = System.currentTimeMillis()
                val contentType = response.body?.contentType()
                val bodyString = response.body?.let { body ->
                    val buffer = Buffer()
                    body.source().use { source ->
                        source.request(Long.MAX_VALUE)
                        buffer.writeAll(source)
                    }
                    buffer.readString(body.contentType()?.charset() ?: Charsets.UTF_8)
                }

                val networkResponse = NetworkResponse(
                    status = response.code,
                    statusText = response.message,
                    headers = response.headers.toMap(),
                    body = bodyString,
                    contentType = contentType?.toString()
                )

                monitor.addRequest(
                    networkRequest.copy(
                        duration = endTime - startTime,
                        response = networkResponse,
                        timestamp = System.currentTimeMillis(),
                        responseSize = response.body?.contentLength() ?: 0
                    )
                )

                return response
            } catch (e: Exception) {
                throw e
            }
        }

        private fun generateRequestId(): String {
            return System.currentTimeMillis().toString() + "_" + (1000..9999).random()
        }

        private fun okhttp3.Headers.toMap(): Map<String, String> {
            return this
                .toList()
                .associate { it.first to it.second }
        }

        private fun okhttp3.RequestBody?.getBodyAsString(): String? {
            this ?: return null
            val buffer = Buffer()
            try {
                writeTo(buffer)
                return buffer.readUtf8()
            } catch (e: Exception) {
                return null
            }
        }

        private fun ResponseBody?.getBodyAsString(): String? {
            this ?: return null
            val source = source()
            source.request(Long.MAX_VALUE)
            val buffer = source.buffer
            
            val charset = this.contentType()?.charset() ?: Charsets.UTF_8
            return buffer.clone().readString(charset)
        }
    }
}

data class NetworkRequest(
    val id: String,
    val url: String,
    val method: String,
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Long = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val requestSize: Long = 0,
    val response: NetworkResponse? = null,
    val responseSize: Long = 0,
    val error: String? = null
) {
    val status: Int get() = response?.status ?: -1
    val statusText: String get() = response?.statusText ?: error ?: "Pending"
    
    fun getStatusColor(): String {
        return when {
            status == -1 -> "error"
            status in 200..299 -> "success"
            status in 300..399 -> "redirect"
            status in 400..499 -> "client_error"
            status >= 500 -> "server_error"
            else -> "pending"
        }
    }
}

data class NetworkResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: String?,
    val contentType: String? = null
)
