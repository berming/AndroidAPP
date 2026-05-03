package com.communicationcard.game.ui.multiplayer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    companion object {
        private const val TAG = "RoomActivity"
    }

    private var networkManager: NetworkManager? = null
    private var roomManager: RoomManager? = null
    private var textChatManager: TextChatManager? = null

    // UI
    private var tvRoomName: TextView? = null
    private var tvRoomCode: TextView? = null
    private var tvPlayerCount: TextView? = null
    private var btnLeave: Button? = null
    private var btnReady: Button? = null
    private var btnStart: Button? = null
    private var btnAddAI: Button? = null
    private var teamAPlayers: LinearLayout? = null
    private var teamBPlayers: LinearLayout? = null
    private var fabChat: View? = null
    private var tvUnreadCount: TextView? = null
    private var chatPanel: View? = null

    // 聊天相关
    private var rvMessages: RecyclerView? = null
    private var etMessage: EditText? = null
    private var btnSend: Button? = null
    private var btnCloseChat: View? = null
    private var isChatVisible = false

    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            enableImmersiveMode()
            setContentView(R.layout.activity_room)

            if (!initManagers()) {
                showErrorAndFinish("初始化失败：未找到房间数据")
                return
            }

            initViews()
            setupListeners()
            observeState()

            Log.d(TAG, "RoomActivity created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showErrorAndFinish("房间加载失败: ${e.message}")
        }
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun initManagers(): Boolean {
        val nm = NetworkManagerHolder.instance
        val rm = RoomManagerHolder.instance

        if (nm == null || rm == null) {
            Log.e(TAG, "Holders are null: NetworkManager=$nm, RoomManager=$rm")
            return false
        }

        if (rm.currentRoom.value == null) {
            Log.e(TAG, "currentRoom is null")
            return false
        }

        networkManager = nm
        roomManager = rm
        textChatManager = TextChatManager(nm, rm)
        return true
    }

    private fun initViews() {
        tvRoomName = findViewById(R.id.tvRoomName)
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
        chatPanel?.let { panel ->
            rvMessages = panel.findViewById(R.id.rvMessages)
            etMessage = panel.findViewById(R.id.etMessage)
            btnSend = panel.findViewById(R.id.btnSend)
            btnCloseChat = panel.findViewById(R.id.btnCloseChat)
        }

        rvMessages?.layoutManager = LinearLayoutManager(this)

        // 根据是否房主显示不同按钮
        updateHostControls()
    }

    private fun setupListeners() {
        btnLeave?.setOnClickListener {
            showLeaveConfirmDialog()
        }

        btnReady?.setOnClickListener {
            toggleReady()
        }

        btnStart?.setOnClickListener {
            startGame()
        }

        btnAddAI?.setOnClickListener {
            val roomId = roomManager?.currentRoom?.value?.roomId ?: return@setOnClickListener
            networkManager?.send(AddAI(roomId))
        }

        fabChat?.setOnClickListener {
            toggleChat()
        }

        btnCloseChat?.setOnClickListener {
            hideChat()
        }

        btnSend?.setOnClickListener {
            sendMessage()
        }

        // 快捷消息
        chatPanel?.findViewById<View>(R.id.btnQuick1)?.setOnClickListener {
            textChatManager?.sendQuickMessage(QuickMessageType.NICE_PLAY)
        }
        chatPanel?.findViewById<View>(R.id.btnQuick2)?.setOnClickListener {
            textChatManager?.sendQuickMessage(QuickMessageType.PASS)
        }
        chatPanel?.findViewById<View>(R.id.btnQuick3)?.setOnClickListener {
            textChatManager?.sendQuickMessage(QuickMessageType.HELP_TEAMMATE)
        }
        chatPanel?.findViewById<View>(R.id.btnQuick4)?.setOnClickListener {
            textChatManager?.sendQuickMessage(QuickMessageType.GOOD_GAME)
        }
    }

    private fun observeState() {
        val rm = roomManager ?: return

        // 监听房间状态
        lifecycleScope.launch {
            rm.currentRoom.collectLatest { room ->
                room?.let { updateRoomUI(it) }
            }
        }

        // 监听房间事件
        lifecycleScope.launch {
            rm.roomEvents.collectLatest { event ->
                handleRoomEvent(event)
            }
        }

        // 监听聊天消息
        val tcm = textChatManager ?: return
        lifecycleScope.launch {
            tcm.messages.collectLatest { messages ->
                updateChatList(messages)
            }
        }

        // 监听未读数
        lifecycleScope.launch {
            tcm.unreadCount.collectLatest { count ->
                updateUnreadBadge(count)
            }
        }
    }

    private fun updateRoomUI(room: RoomInfo) {
        try {
            tvRoomName?.text = room.roomName.ifEmpty { "房间" }
            tvRoomCode?.text = room.roomCode
            tvPlayerCount?.text = "${room.players.size}/${room.maxPlayers} 玩家"

            // 更新玩家列表
            updatePlayerLists(room)

            // 更新按钮状态
            updateHostControls()
            btnStart?.isEnabled = roomManager?.canStartGame() == true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating room UI", e)
        }
    }

    private fun updatePlayerLists(room: RoomInfo) {
        val teamAContainer = teamAPlayers ?: return
        val teamBContainer = teamBPlayers ?: return

        teamAContainer.removeAllViews()
        teamBContainer.removeAllViews()

        val teamA = room.players.filter { it.team == "TEAM_A" || (it.seatIndex % 2 == 0) }
        val teamB = room.players.filter { it.team == "TEAM_B" || (it.seatIndex % 2 == 1) }

        teamA.forEach { player ->
            addPlayerView(teamAContainer, player, room.hostId)
        }

        teamB.forEach { player ->
            addPlayerView(teamBContainer, player, room.hostId)
        }

        // 添加空位
        val emptyA = (room.maxPlayers / 2) - teamA.size
        val emptyB = (room.maxPlayers / 2) - teamB.size

        repeat(emptyA) {
            addEmptySlot(teamAContainer)
        }
        repeat(emptyB) {
            addEmptySlot(teamBContainer)
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
        if (roomManager?.isHost() == true && player.id != hostId && !player.isAI) {
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
        val isHost = roomManager?.isHost() == true
        btnReady?.visibility = if (isHost) View.GONE else View.VISIBLE
        btnStart?.visibility = if (isHost) View.VISIBLE else View.GONE
        btnAddAI?.visibility = if (isHost) View.VISIBLE else View.GONE
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.GameStarting -> {
                // 跳转到游戏界面
                navigateToGame(event.state)
            }
            is RoomEvent.PlayerLeft -> {
                textChatManager?.addSystemMessage("${event.playerName} 离开了房间")
            }
            is RoomEvent.PlayerRejoined -> {
                textChatManager?.addSystemMessage("${event.playerName} 重新加入了房间")
            }
            is RoomEvent.Error -> {
                Toast.makeText(this, "错误: ${event.message}", Toast.LENGTH_SHORT).show()
            }
            else -> { /* 其他事件由observeState处理 */ }
        }
    }

    private fun toggleReady() {
        isReady = !isReady
        roomManager?.setReady(isReady)
        btnReady?.text = if (isReady) "取消准备" else "准备"
        btnReady?.setBackgroundResource(
            if (isReady) R.drawable.button_primary else R.drawable.button_secondary
        )
    }

    private fun startGame() {
        if (roomManager?.canStartGame() != true) {
            Toast.makeText(this, "等待所有玩家准备", Toast.LENGTH_SHORT).show()
            return
        }
        roomManager?.startGame()
    }

    private fun toggleChat() {
        if (isChatVisible) {
            hideChat()
        } else {
            showChat()
        }
    }

    private fun showChat() {
        chatPanel?.visibility = View.VISIBLE
        isChatVisible = true
        textChatManager?.markAsRead()
    }

    private fun hideChat() {
        chatPanel?.visibility = View.GONE
        isChatVisible = false
    }

    private fun updateUnreadBadge(count: Int) {
        if (count > 0 && !isChatVisible) {
            tvUnreadCount?.visibility = View.VISIBLE
            tvUnreadCount?.text = if (count > 99) "99+" else count.toString()
        } else {
            tvUnreadCount?.visibility = View.GONE
        }
    }

    private fun sendMessage() {
        val text = etMessage?.text?.toString()?.trim() ?: return
        if (text.isNotEmpty()) {
            textChatManager?.sendMessage(text)
            etMessage?.text?.clear()
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
                roomManager?.kickPlayer(player.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun leaveRoom() {
        roomManager?.leaveRoom()
        finish()
    }

    private fun navigateToGame(state: SerializedGameState) {
        val rm = roomManager ?: return
        val intent = Intent(this, OnlineGameActivity::class.java)
        intent.putExtra(OnlineGameActivity.EXTRA_GAME_STATE, GameMessage.json.encodeToString(SerializedGameState.serializer(), state))
        intent.putExtra(OnlineGameActivity.EXTRA_LOCAL_SEAT_INDEX, rm.localSeatIndex)
        startActivity(intent)
        finish()
    }

    private fun updateChatList(messages: List<ChatMessage>) {
        val rv = rvMessages ?: return
        val adapter = rv.adapter as? ChatAdapter
        if (adapter == null) {
            rv.adapter = ChatAdapter(messages)
        } else {
            adapter.updateMessages(messages)
        }
        if (messages.isNotEmpty()) {
            rv.scrollToPosition(messages.size - 1)
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
        textChatManager?.release()
    }
}

// 简单的单例持有者（实际应用中应使用依赖注入）
object NetworkManagerHolder {
    var instance: NetworkManager? = null
}

object RoomManagerHolder {
    var instance: RoomManager? = null
}
