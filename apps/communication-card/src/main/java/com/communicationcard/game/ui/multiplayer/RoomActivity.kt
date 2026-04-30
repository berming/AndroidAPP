package com.communicationcard.game.ui.multiplayer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.communicationcard.game.R
import com.communicationcard.game.network.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 多人游戏房间
 */
class RoomActivity : AppCompatActivity() {

    private lateinit var networkManager: NetworkManager
    private lateinit var roomManager: RoomManager
    private lateinit var textChatManager: TextChatManager

    // UI
    private lateinit var tvRoomCode: TextView
    private lateinit var tvPlayerCount: TextView
    private lateinit var btnLeave: Button
    private lateinit var btnReady: Button
    private lateinit var btnStart: Button
    private lateinit var btnAddAI: Button
    private lateinit var teamAPlayers: LinearLayout
    private lateinit var teamBPlayers: LinearLayout
    private lateinit var fabChat: View
    private lateinit var tvUnreadCount: TextView
    private lateinit var chatPanel: View

    // 聊天相关
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnCloseChat: View
    private var isChatVisible = false

    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)

        initManagers()
        initViews()
        setupListeners()
        observeState()
    }

    private fun initManagers() {
        // 获取共享的NetworkManager和RoomManager实例
        // 在实际应用中，这些应该通过依赖注入或Application级别单例管理
        networkManager = NetworkManagerHolder.instance
            ?: throw IllegalStateException("NetworkManager not initialized")
        roomManager = RoomManagerHolder.instance
            ?: throw IllegalStateException("RoomManager not initialized")
        textChatManager = TextChatManager(networkManager, roomManager)
    }

    private fun initViews() {
        tvRoomCode = findViewById(R.id.tvRoomCode)
        tvPlayerCount = findViewById(R.id.tvPlayerCount)
        btnLeave = findViewById(R.id.btnLeave)
        btnReady = findViewById(R.id.btnReady)
        btnStart = findViewById(R.id.btnStart)
        btnAddAI = findViewById(R.id.btnAddAI)
        teamAPlayers = findViewById(R.id.teamAPlayers)
        teamBPlayers = findViewById(R.id.teamBPlayers)
        fabChat = findViewById(R.id.fabChat)
        tvUnreadCount = findViewById(R.id.tvUnreadCount)
        chatPanel = findViewById(R.id.chatPanel)

        // 聊天面板
        rvMessages = chatPanel.findViewById(R.id.rvMessages)
        etMessage = chatPanel.findViewById(R.id.etMessage)
        btnSend = chatPanel.findViewById(R.id.btnSend)
        btnCloseChat = chatPanel.findViewById(R.id.btnCloseChat)

        rvMessages.layoutManager = LinearLayoutManager(this)

        // 根据是否房主显示不同按钮
        updateHostControls()
    }

    private fun setupListeners() {
        btnLeave.setOnClickListener {
            showLeaveConfirmDialog()
        }

        btnReady.setOnClickListener {
            toggleReady()
        }

        btnStart.setOnClickListener {
            startGame()
        }

        btnAddAI.setOnClickListener {
            val roomId = roomManager.currentRoom.value?.roomId ?: return@setOnClickListener
            networkManager.send(AddAI(roomId))
        }

        fabChat.setOnClickListener {
            toggleChat()
        }

        btnCloseChat.setOnClickListener {
            hideChat()
        }

        btnSend.setOnClickListener {
            sendMessage()
        }

        // 快捷消息
        chatPanel.findViewById<View>(R.id.btnQuick1)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.NICE_PLAY)
        }
        chatPanel.findViewById<View>(R.id.btnQuick2)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.PASS)
        }
        chatPanel.findViewById<View>(R.id.btnQuick3)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.HELP_TEAMMATE)
        }
        chatPanel.findViewById<View>(R.id.btnQuick4)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.GOOD_GAME)
        }
    }

    private fun observeState() {
        // 监听房间状态
        lifecycleScope.launch {
            roomManager.currentRoom.collectLatest { room ->
                room?.let { updateRoomUI(it) }
            }
        }

        // 监听房间事件
        lifecycleScope.launch {
            roomManager.roomEvents.collectLatest { event ->
                handleRoomEvent(event)
            }
        }

        // 监听聊天消息
        lifecycleScope.launch {
            textChatManager.messages.collectLatest { messages ->
                updateChatList(messages)
            }
        }

        // 监听未读数
        lifecycleScope.launch {
            textChatManager.unreadCount.collectLatest { count ->
                updateUnreadBadge(count)
            }
        }
    }

    private fun updateRoomUI(room: RoomInfo) {
        tvRoomCode.text = room.roomCode
        tvPlayerCount.text = "${room.players.size}/${room.maxPlayers} 玩家"

        // 更新玩家列表
        updatePlayerLists(room)

        // 更新按钮状态
        updateHostControls()
        btnStart.isEnabled = roomManager.canStartGame()
    }

    private fun updatePlayerLists(room: RoomInfo) {
        teamAPlayers.removeAllViews()
        teamBPlayers.removeAllViews()

        val teamA = room.players.filter { it.team == "TEAM_A" || (it.seatIndex % 2 == 0) }
        val teamB = room.players.filter { it.team == "TEAM_B" || (it.seatIndex % 2 == 1) }

        teamA.forEach { player ->
            addPlayerView(teamAPlayers, player, room.hostId)
        }

        teamB.forEach { player ->
            addPlayerView(teamBPlayers, player, room.hostId)
        }

        // 添加空位
        val emptyA = (room.maxPlayers / 2) - teamA.size
        val emptyB = (room.maxPlayers / 2) - teamB.size

        repeat(emptyA) {
            addEmptySlot(teamAPlayers)
        }
        repeat(emptyB) {
            addEmptySlot(teamBPlayers)
        }
    }

    private fun addPlayerView(container: LinearLayout, player: RoomPlayer, hostId: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_room_player, container, false)

        val tvSeatNumber = view.findViewById<TextView>(R.id.tvSeatNumber)
        val tvPlayerName = view.findViewById<TextView>(R.id.tvPlayerName)
        val tvPlayerStatus = view.findViewById<TextView>(R.id.tvPlayerStatus)
        val tvTag = view.findViewById<TextView>(R.id.tvTag)
        val ivReadyStatus = view.findViewById<ImageView>(R.id.ivReadyStatus)

        tvSeatNumber.text = (player.seatIndex + 1).toString()
        tvPlayerName.text = player.name

        // 状态
        tvPlayerStatus.text = when {
            !player.isConnected -> "离线"
            player.isReady -> "已准备"
            else -> "等待中"
        }

        // 标签
        when {
            player.id == hostId -> {
                tvTag.visibility = View.VISIBLE
                tvTag.text = "房主"
                tvTag.setBackgroundResource(R.drawable.tag_background)
            }
            player.isAI -> {
                tvTag.visibility = View.VISIBLE
                tvTag.text = "AI"
                tvTag.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            else -> {
                tvTag.visibility = View.GONE
            }
        }

        // 准备状态图标
        if (player.isReady && player.id != hostId) {
            ivReadyStatus.visibility = View.VISIBLE
            ivReadyStatus.setImageResource(android.R.drawable.ic_menu_send)
            ivReadyStatus.setColorFilter(ContextCompat.getColor(this, R.color.score_positive))
        } else {
            ivReadyStatus.visibility = View.GONE
        }

        // 点击踢人（仅房主）
        if (roomManager.isHost() && player.id != hostId && !player.isAI) {
            view.setOnLongClickListener {
                showKickConfirmDialog(player)
                true
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = resources.getDimensionPixelSize(R.dimen.player_item_margin)
        view.layoutParams = params

        container.addView(view)
    }

    private fun addEmptySlot(container: LinearLayout) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_room_player, container, false)

        val tvSeatNumber = view.findViewById<TextView>(R.id.tvSeatNumber)
        val tvPlayerName = view.findViewById<TextView>(R.id.tvPlayerName)
        val tvPlayerStatus = view.findViewById<TextView>(R.id.tvPlayerStatus)
        val tvTag = view.findViewById<TextView>(R.id.tvTag)
        val ivReadyStatus = view.findViewById<ImageView>(R.id.ivReadyStatus)

        tvSeatNumber.text = "-"
        tvPlayerName.text = "等待加入..."
        tvPlayerStatus.visibility = View.GONE
        tvTag.visibility = View.GONE
        ivReadyStatus.visibility = View.GONE

        view.alpha = 0.5f

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = resources.getDimensionPixelSize(R.dimen.player_item_margin)
        view.layoutParams = params

        container.addView(view)
    }

    private fun updateHostControls() {
        val isHost = roomManager.isHost()
        btnReady.visibility = if (isHost) View.GONE else View.VISIBLE
        btnStart.visibility = if (isHost) View.VISIBLE else View.GONE
        btnAddAI.visibility = if (isHost) View.VISIBLE else View.GONE
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.GameStarting -> {
                // 跳转到游戏界面
                navigateToGame(event.state)
            }
            is RoomEvent.PlayerLeft -> {
                textChatManager.addSystemMessage("${event.playerName} 离开了房间")
            }
            is RoomEvent.PlayerRejoined -> {
                textChatManager.addSystemMessage("${event.playerName} 重新加入了房间")
            }
            is RoomEvent.Error -> {
                Toast.makeText(this, "错误: ${event.message}", Toast.LENGTH_SHORT).show()
            }
            else -> { /* 其他事件由observeState处理 */ }
        }
    }

    private fun toggleReady() {
        isReady = !isReady
        roomManager.setReady(isReady)
        btnReady.text = if (isReady) "取消准备" else "准备"
        btnReady.setBackgroundResource(
            if (isReady) R.drawable.button_primary else R.drawable.button_secondary
        )
    }

    private fun startGame() {
        if (!roomManager.canStartGame()) {
            Toast.makeText(this, "等待所有玩家准备", Toast.LENGTH_SHORT).show()
            return
        }
        roomManager.startGame()
    }

    private fun toggleChat() {
        if (isChatVisible) {
            hideChat()
        } else {
            showChat()
        }
    }

    private fun showChat() {
        chatPanel.visibility = View.VISIBLE
        isChatVisible = true
        textChatManager.markAsRead()
    }

    private fun hideChat() {
        chatPanel.visibility = View.GONE
        isChatVisible = false
    }

    private fun updateUnreadBadge(count: Int) {
        if (count > 0 && !isChatVisible) {
            tvUnreadCount.visibility = View.VISIBLE
            tvUnreadCount.text = if (count > 99) "99+" else count.toString()
        } else {
            tvUnreadCount.visibility = View.GONE
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isNotEmpty()) {
            textChatManager.sendMessage(text)
            etMessage.text.clear()
        }
    }

    private fun showLeaveConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("离开房间")
            .setMessage("确定要离开房间吗？")
            .setPositiveButton("确定") { _, _ ->
                leaveRoom()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showKickConfirmDialog(player: RoomPlayer) {
        AlertDialog.Builder(this)
            .setTitle("踢出玩家")
            .setMessage("确定要踢出 ${player.name} 吗？")
            .setPositiveButton("确定") { _, _ ->
                roomManager.kickPlayer(player.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun leaveRoom() {
        roomManager.leaveRoom()
        finish()
    }

    private fun navigateToGame(state: SerializedGameState) {
        val intent = Intent(this, OnlineGameActivity::class.java)
        intent.putExtra(OnlineGameActivity.EXTRA_GAME_STATE, GameMessage.json.encodeToString(SerializedGameState.serializer(), state))
        intent.putExtra(OnlineGameActivity.EXTRA_LOCAL_SEAT_INDEX, roomManager.localSeatIndex)
        startActivity(intent)
        finish()
    }

    private fun updateChatList(messages: List<ChatMessage>) {
        val adapter = rvMessages.adapter as? ChatAdapter
        if (adapter == null) {
            rvMessages.adapter = ChatAdapter(messages)
        } else {
            adapter.updateMessages(messages)
        }
        if (messages.isNotEmpty()) {
            rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    override fun onBackPressed() {
        if (isChatVisible) {
            hideChat()
        } else {
            showLeaveConfirmDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textChatManager.release()
    }
}

// 简单的单例持有者（实际应用中应使用依赖注入）
object NetworkManagerHolder {
    var instance: NetworkManager? = null
}

object RoomManagerHolder {
    var instance: RoomManager? = null
}
