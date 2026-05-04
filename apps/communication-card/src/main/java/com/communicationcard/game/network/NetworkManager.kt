package com.communicationcard.game.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.communicationcard.game.util.DebugLogManager
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

    init {
        DebugLogManager.w(TAG, "========================================")
        DebugLogManager.w(TAG, "NetworkManager initialized")
        DebugLogManager.w(TAG, "Server URL: $serverUrl")
        DebugLogManager.w(TAG, "========================================")
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var sessionToken: String? = null
    private var reconnectAttempts = 0
    @Volatile private var manualDisconnect = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

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
        DebugLogManager.w(TAG, ">>> connect() called, current state: ${_connectionState.value}")
        DebugLogManager.w(TAG, ">>> Network available: ${isNetworkAvailable()}")

        return scope.launch {
            val state = _connectionState.value
            if (state == ConnectionState.Connected ||
                state == ConnectionState.Connecting ||
                state is ConnectionState.Reconnecting) {
                DebugLogManager.w(TAG, ">>> Already connected/connecting/reconnecting, skipping")
                return@launch
            }

            manualDisconnect = false
            sessionToken = token
            _connectionState.value = ConnectionState.Connecting
            reconnectAttempts = 0

            DebugLogManager.w(TAG, ">>> Starting connection to: $serverUrl")

            try {
                establishConnection()
            } catch (e: Exception) {
                DebugLogManager.e(TAG, ">>> Connection exception: ${e.javaClass.simpleName}: ${e.message}", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                _connectionEvents.emit(ConnectionEvent.ConnectionFailed(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun establishConnection() {
        DebugLogManager.w(TAG, ">>> establishConnection() - Building request for: $serverUrl")

        val request = Request.Builder()
            .url(serverUrl)
            .apply {
                sessionToken?.let { header("Authorization", "Bearer $it") }
            }
            .build()

        DebugLogManager.w(TAG, ">>> Request built, creating WebSocket...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogManager.w(TAG, ">>> onOpen() - WebSocket connected! Response: ${response.code}")
                scope.launch {
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0
                    startHeartbeat()
                    // 如果有保存的会话令牌（即此次是重连），在 WebSocket 真正打开后才发送
                    // Reconnect 消息。之前在 attemptReconnect 中过早发送，会因 ws 尚未 OPEN 被丢弃
                    sessionToken?.let { token ->
                        DebugLogManager.w(TAG, ">>> Sending Reconnect with token=$token")
                        send(Reconnect(token))
                    }
                    _connectionEvents.emit(ConnectionEvent.Connected)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                DebugLogManager.d(TAG, ">>> onMessage(): $text")
                scope.launch {
                    try {
                        val message = GameMessage.fromJson(text)
                        handleMessage(message)
                    } catch (e: Exception) {
                        DebugLogManager.e(TAG, "Failed to parse message: $text", e)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogManager.w(TAG, ">>> onClosing(): code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogManager.w(TAG, ">>> onClosed(): code=$code, reason=$reason")
                scope.launch {
                    handleDisconnection(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLogManager.e(TAG, ">>> onFailure(): ${t.javaClass.simpleName}: ${t.message}")
                DebugLogManager.e(TAG, ">>> Response: ${response?.code} ${response?.message}")
                t.printStackTrace()
                scope.launch {
                    handleDisconnection(-1, t.message ?: "Connection failed")
                }
            }
        })

        DebugLogManager.w(TAG, ">>> WebSocket created, waiting for callbacks...")
    }

    private suspend fun handleMessage(message: GameMessage) {
        when (message) {
            is Heartbeat -> {
                // 服务器心跳响应，无需处理
            }
            is ReconnectSuccess -> {
                DebugLogManager.d(TAG, "Reconnection successful")
                _connectionEvents.emit(ConnectionEvent.Reconnected)
                // 仍需转发给 GameSyncManager 等订阅者，以便恢复状态
                _messages.emit(message)
            }
            is ErrorMessage -> {
                DebugLogManager.e(TAG, "Server error: ${message.code} - ${message.message}")
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

        // 主动断开（用户操作）不要尝试重连
        if (manualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        // 尝试重连（除非是主动断开 code=1000）
        if (code != 1000 && reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch { attemptReconnect() }
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun attemptReconnect() {
        if (manualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        reconnectAttempts++
        val delay = RECONNECT_BASE_DELAY_MS * (1 shl (reconnectAttempts - 1))
        DebugLogManager.d(TAG, "Attempting reconnect $reconnectAttempts/$RECONNECT_MAX_ATTEMPTS in ${delay}ms")

        _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts)
        _connectionEvents.emit(ConnectionEvent.Reconnecting(reconnectAttempts))

        delay(delay)

        if (manualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (isNetworkAvailable()) {
            try {
                establishConnection()
                // 注意：Reconnect 消息现在在 onOpen 回调中发送（webSocket 打开之后），
                // 不再在这里发送，避免 send() 在 ws 尚未 OPEN 时被丢弃
            } catch (e: Exception) {
                DebugLogManager.e(TAG, "Reconnect attempt failed", e)
                if (!manualDisconnect && reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
                    attemptReconnect()
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionEvents.emit(ConnectionEvent.ReconnectFailed)
                }
            }
        } else {
            if (!manualDisconnect && reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
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
        DebugLogManager.d(TAG, "Sending: $json")
        return ws.send(json)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        // 标记为主动断开，阻止任何排队中的重连尝试
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            stopHeartbeat()
            webSocket?.close(1000, "User disconnect")
            webSocket = null
            sessionToken = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * 设置会话令牌（传 null 可以清除）
     */
    fun setSessionToken(token: String?) {
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
