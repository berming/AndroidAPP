package com.communicationcard.game.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 文字聊天管理器
 */
class TextChatManager(
    private val networkManager: NetworkManager,
    private val roomManager: RoomManager
) {
    companion object {
        private const val MAX_MESSAGE_LENGTH = 200
        private const val MAX_HISTORY_SIZE = 100
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 聊天消息历史
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // 未读消息数
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    // 聊天事件
    private val _chatEvents = MutableSharedFlow<ChatEvent>(replay = 0, extraBufferCapacity = 16)
    val chatEvents: SharedFlow<ChatEvent> = _chatEvents.asSharedFlow()

    init {
        // 监听网络消息
        scope.launch {
            networkManager.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private suspend fun handleMessage(message: GameMessage) {
        when (message) {
            is TextChatMessage -> {
                val chatMessage = ChatMessage(
                    id = "${message.senderId}_${message.timestamp}",
                    senderId = message.senderId,
                    senderName = message.senderName,
                    text = message.text,
                    timestamp = message.timestamp,
                    isTeamOnly = message.isTeamOnly,
                    isQuickMessage = false
                )
                addMessage(chatMessage)
                _chatEvents.emit(ChatEvent.NewMessage(chatMessage))
            }
            is QuickChatMessage -> {
                val chatMessage = ChatMessage(
                    id = "${message.senderId}_${message.timestamp}",
                    senderId = message.senderId,
                    senderName = message.senderName,
                    text = message.quickType.text,
                    timestamp = message.timestamp,
                    isTeamOnly = false,
                    isQuickMessage = true,
                    quickType = message.quickType
                )
                addMessage(chatMessage)
                _chatEvents.emit(ChatEvent.NewMessage(chatMessage))
            }
            else -> { /* 其他消息不处理 */ }
        }
    }

    private fun addMessage(message: ChatMessage) {
        val currentList = _messages.value.toMutableList()
        currentList.add(message)
        // 保持历史记录在限制内
        if (currentList.size > MAX_HISTORY_SIZE) {
            currentList.removeAt(0)
        }
        _messages.value = currentList
        _unreadCount.value++
    }

    /**
     * 发送文字消息
     */
    fun sendMessage(text: String, isTeamOnly: Boolean = false): Boolean {
        if (text.isBlank() || text.length > MAX_MESSAGE_LENGTH) {
            return false
        }

        val localPlayer = roomManager.getLocalPlayer() ?: return false
        val message = TextChatMessage(
            senderId = localPlayer.id,
            senderName = localPlayer.name,
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            isTeamOnly = isTeamOnly
        )
        return networkManager.send(message)
    }

    /**
     * 发送快捷消息
     */
    fun sendQuickMessage(type: QuickMessageType): Boolean {
        val localPlayer = roomManager.getLocalPlayer() ?: return false
        val message = QuickChatMessage(
            senderId = localPlayer.id,
            senderName = localPlayer.name,
            quickType = type,
            timestamp = System.currentTimeMillis()
        )
        return networkManager.send(message)
    }

    /**
     * 标记已读
     */
    fun markAsRead() {
        _unreadCount.value = 0
    }

    /**
     * 清空历史
     */
    fun clearHistory() {
        _messages.value = emptyList()
        _unreadCount.value = 0
    }

    /**
     * 添加系统消息
     */
    fun addSystemMessage(text: String) {
        val message = ChatMessage(
            id = "system_${System.currentTimeMillis()}",
            senderId = "system",
            senderName = "系统",
            text = text,
            timestamp = System.currentTimeMillis(),
            isTeamOnly = false,
            isQuickMessage = false,
            isSystemMessage = true
        )
        addMessage(message)
    }

    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
        _messages.value = emptyList()
        _unreadCount.value = 0
    }
}

/**
 * 聊天消息
 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isTeamOnly: Boolean = false,
    val isQuickMessage: Boolean = false,
    val quickType: QuickMessageType? = null,
    val isSystemMessage: Boolean = false
)

/**
 * 聊天事件
 */
sealed class ChatEvent {
    data class NewMessage(val message: ChatMessage) : ChatEvent()
}
