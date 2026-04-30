package com.communicationcard.game.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * 网络连接管理器
 * 负责 WebSocket 连接、心跳、重连、消息收发
 */
class NetworkManager(
    private val context: Context,
    private val serverUrl: String
) {
    companion object {
        private const val TAG = "NetworkManager"
        private const val HEARTBEAT_INTERVAL_MS = 15000L
        private const val RECONNECT_MAX_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var sessionToken: String? = null
    private var reconnectAttempts = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    // 连接状态
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 接收到的消息流
    private val _messages = MutableSharedFlow<GameMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<GameMessage> = _messages.asSharedFlow()

    // 连接事件
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(replay = 0, extraBufferCapacity = 16)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    /**
     * 连接到服务器
     */
    fun connect(token: String? = null): Job {
        return scope.launch {
            if (_connectionState.value == ConnectionState.Connected ||
                _connectionState.value == ConnectionState.Connecting) {
                Log.d(TAG, "Already connected or connecting")
                return@launch
            }

            sessionToken = token
            _connectionState.value = ConnectionState.Connecting
            reconnectAttempts = 0

            try {
                establishConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                _connectionEvents.emit(ConnectionEvent.ConnectionFailed(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun establishConnection() {
        val request = Request.Builder()
            .url(serverUrl)
            .apply {
                sessionToken?.let { header("Authorization", "Bearer $it") }
            }
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                scope.launch {
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0
                    startHeartbeat()
                    _connectionEvents.emit(ConnectionEvent.Connected)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                scope.launch {
                    try {
                        val message = GameMessage.fromJson(text)
                        handleMessage(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse message: $text", e)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                scope.launch {
                    handleDisconnection(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                scope.launch {
                    handleDisconnection(-1, t.message ?: "Connection failed")
                }
            }
        })
    }

    private suspend fun handleMessage(message: GameMessage) {
        when (message) {
            is Heartbeat -> {
                // 服务器心跳响应，无需处理
            }
            is ReconnectSuccess -> {
                Log.d(TAG, "Reconnection successful")
                _connectionEvents.emit(ConnectionEvent.Reconnected)
            }
            is ErrorMessage -> {
                Log.e(TAG, "Server error: ${message.code} - ${message.message}")
                _connectionEvents.emit(ConnectionEvent.ServerError(message.code, message.message))
            }
            else -> {
                // 转发给订阅者
                _messages.emit(message)
            }
        }
    }

    private suspend fun handleDisconnection(code: Int, reason: String) {
        stopHeartbeat()
        webSocket = null

        if (_connectionState.value == ConnectionState.Disconnected) {
            return
        }

        _connectionEvents.emit(ConnectionEvent.Disconnected(code, reason))

        // 尝试重连（除非是主动断开）
        if (code != 1000 && reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
            attemptReconnect()
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun attemptReconnect() {
        reconnectAttempts++
        val delay = RECONNECT_BASE_DELAY_MS * (1 shl (reconnectAttempts - 1))
        Log.d(TAG, "Attempting reconnect $reconnectAttempts/$RECONNECT_MAX_ATTEMPTS in ${delay}ms")

        _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts)
        _connectionEvents.emit(ConnectionEvent.Reconnecting(reconnectAttempts))

        delay(delay)

        if (isNetworkAvailable()) {
            try {
                establishConnection()
                // 发送重连请求
                sessionToken?.let { token ->
                    send(Reconnect(token))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect attempt failed", e)
                if (reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
                    attemptReconnect()
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionEvents.emit(ConnectionEvent.ReconnectFailed)
                }
            }
        } else {
            if (reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
                attemptReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
                _connectionEvents.emit(ConnectionEvent.ReconnectFailed)
            }
        }
    }

    /**
     * 发送消息
     */
    fun send(message: GameMessage): Boolean {
        val ws = webSocket ?: return false
        val json = message.toJson()
        Log.d(TAG, "Sending: $json")
        return ws.send(json)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        scope.launch {
            stopHeartbeat()
            webSocket?.close(1000, "User disconnect")
            webSocket = null
            sessionToken = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * 设置会话令牌
     */
    fun setSessionToken(token: String) {
        sessionToken = token
    }

    /**
     * 获取当前会话令牌
     */
    fun getSessionToken(): String? = sessionToken

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_connectionState.value == ConnectionState.Connected) {
                    send(Heartbeat(System.currentTimeMillis()))
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 释放资源
     */
    fun release() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}

/**
 * 连接状态
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Error(val reason: String) : ConnectionState()
}

/**
 * 连接事件
 */
sealed class ConnectionEvent {
    object Connected : ConnectionEvent()
    data class Disconnected(val code: Int, val reason: String) : ConnectionEvent()
    data class Reconnecting(val attempt: Int) : ConnectionEvent()
    object Reconnected : ConnectionEvent()
    object ReconnectFailed : ConnectionEvent()
    data class ConnectionFailed(val reason: String) : ConnectionEvent()
    data class ServerError(val code: Int, val message: String) : ConnectionEvent()
}
