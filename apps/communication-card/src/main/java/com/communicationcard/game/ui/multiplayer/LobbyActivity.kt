package com.communicationcard.game.ui.multiplayer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.communicationcard.game.R
import com.communicationcard.game.network.*
import com.communicationcard.game.ui.GamePreferences
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 多人游戏大厅
 */
class LobbyActivity : AppCompatActivity() {

    companion object {
        // 游戏服务器地址 - 腾讯云服务器
        private const val SERVER_URL = "ws://175.178.158.35:8080/game"
        private const val MAX_NAME_LENGTH = 12
    }

    private lateinit var networkManager: NetworkManager
    private lateinit var roomManager: RoomManager
    private lateinit var preferences: GamePreferences

    // UI
    private lateinit var playerInfoBar: LinearLayout
    private lateinit var tvPlayerName: TextView
    private lateinit var btnEditName: TextView
    private lateinit var etRoomCode: EditText
    private lateinit var btnCreateRoom: Button
    private lateinit var btnJoinRoom: Button
    private lateinit var btnBack: Button
    private lateinit var statusIndicator: View
    private lateinit var tvConnectionStatus: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var tvLoadingText: TextView

    private var playerName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_lobby)

        preferences = GamePreferences(this)
        playerName = preferences.lastPlayerName

        initViews()
        initNetwork()
        setupListeners()
        refreshPlayerName()

        // 首次进入：如果没有昵称，提示输入
        if (playerName.isEmpty()) {
            showNameDialog(firstTime = true)
        }
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

    private fun initViews() {
        playerInfoBar = findViewById(R.id.playerInfoBar)
        tvPlayerName = findViewById(R.id.tvPlayerName)
        btnEditName = findViewById(R.id.btnEditName)
        etRoomCode = findViewById(R.id.etRoomCode)
        btnCreateRoom = findViewById(R.id.btnCreateRoom)
        btnJoinRoom = findViewById(R.id.btnJoinRoom)
        btnBack = findViewById(R.id.btnBack)
        statusIndicator = findViewById(R.id.statusIndicator)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoadingText = findViewById(R.id.tvLoadingText)
    }

    private fun initNetwork() {
        networkManager = NetworkManager(this, SERVER_URL)
        roomManager = RoomManager(networkManager)

        // 监听连接状态
        lifecycleScope.launch {
            networkManager.connectionState.collectLatest { state ->
                updateConnectionUI(state)
            }
        }

        // 监听房间事件
        lifecycleScope.launch {
            roomManager.roomEvents.collectLatest { event ->
                handleRoomEvent(event)
            }
        }

        // 连接服务器
        networkManager.connect()
    }

    private fun setupListeners() {
        playerInfoBar.setOnClickListener { showNameDialog(firstTime = false) }
        btnEditName.setOnClickListener { showNameDialog(firstTime = false) }
        btnCreateRoom.setOnClickListener { createRoom() }
        btnJoinRoom.setOnClickListener { joinRoom() }
        btnBack.setOnClickListener { finish() }
    }

    private fun refreshPlayerName() {
        if (playerName.isEmpty()) {
            tvPlayerName.text = "未设置（点击设置昵称）"
            tvPlayerName.alpha = 0.6f
        } else {
            tvPlayerName.text = playerName
            tvPlayerName.alpha = 1f
        }
    }

    private fun showNameDialog(firstTime: Boolean) {
        val input = EditText(this).apply {
            setText(playerName)
            setSelection(playerName.length)
            hint = "输入昵称（最多 $MAX_NAME_LENGTH 个字符）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_NAME_LENGTH))
        }
        val container = FrameLayout(this).apply {
            val padding = (resources.displayMetrics.density * 16).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (firstTime) "设置玩家昵称" else "修改玩家昵称")
            .setMessage(if (firstTime) "首次进入多人游戏，请先设置一个昵称" else null)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show()
                    if (firstTime) showNameDialog(firstTime = true)
                } else {
                    playerName = newName
                    preferences.lastPlayerName = newName
                    refreshPlayerName()
                }
            }

        if (!firstTime) {
            builder.setNegativeButton("取消", null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun updateConnectionUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Disconnected -> {
                statusIndicator.setBackgroundColor(Color.GRAY)
                tvConnectionStatus.text = "未连接"
                setButtonsEnabled(false)
            }
            is ConnectionState.Connecting -> {
                statusIndicator.setBackgroundColor(Color.YELLOW)
                tvConnectionStatus.text = "正在连接..."
                setButtonsEnabled(false)
            }
            is ConnectionState.Connected -> {
                statusIndicator.setBackgroundColor(Color.GREEN)
                tvConnectionStatus.text = "已连接"
                setButtonsEnabled(true)
            }
            is ConnectionState.Reconnecting -> {
                statusIndicator.setBackgroundColor(Color.YELLOW)
                tvConnectionStatus.text = "重连中(${state.attempt})..."
                setButtonsEnabled(false)
            }
            is ConnectionState.Error -> {
                statusIndicator.setBackgroundColor(Color.RED)
                tvConnectionStatus.text = "连接错误"
                setButtonsEnabled(false)
                hideLoading()
                Toast.makeText(this, "连接失败: ${state.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.RoomCreated -> {
                hideLoading()
                navigateToRoom()
            }
            is RoomEvent.JoinedRoom -> {
                hideLoading()
                navigateToRoom()
            }
            is RoomEvent.Error -> {
                hideLoading()
                Toast.makeText(this, "错误: ${event.message}", Toast.LENGTH_SHORT).show()
            }
            else -> { /* 其他事件在房间界面处理 */ }
        }
    }

    private fun createRoom() {
        if (playerName.isEmpty()) {
            showNameDialog(firstTime = true)
            return
        }

        showCreateRoomDialog()
    }

    private fun showCreateRoomDialog() {
        val input = EditText(this).apply {
            hint = "房间名称（可选，留空使用默认）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        }
        val container = FrameLayout(this).apply {
            val padding = (resources.displayMetrics.density * 16).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("创建房间")
            .setMessage("为你的房间起一个名字，方便好友识别")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val roomName = input.text.toString().trim()
                doCreateRoom(roomName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doCreateRoom(roomName: String) {
        showLoading("正在创建房间...")
        val sent = roomManager.createRoom(playerName, roomName, 6)
        if (!sent) {
            hideLoading()
            Toast.makeText(this, "未连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun joinRoom() {
        if (playerName.isEmpty()) {
            showNameDialog(firstTime = true)
            return
        }

        val roomCode = etRoomCode.text.toString().trim().uppercase()
        if (roomCode.isEmpty()) {
            Toast.makeText(this, "请输入房间码", Toast.LENGTH_SHORT).show()
            return
        }
        if (roomCode.length < 4) {
            Toast.makeText(this, "房间码格式错误", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading("正在加入房间...")
        val sent = roomManager.joinRoom(roomCode, playerName)
        if (!sent) {
            hideLoading()
            Toast.makeText(this, "未连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToRoom() {
        // 共享实例给RoomActivity
        NetworkManagerHolder.instance = networkManager
        RoomManagerHolder.instance = roomManager

        val intent = Intent(this, RoomActivity::class.java)
        startActivity(intent)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnCreateRoom.isEnabled = enabled
        btnJoinRoom.isEnabled = enabled
    }

    private fun showLoading(text: String) {
        tvLoadingText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果没有进入房间，断开连接
        if (roomManager.currentRoom.value == null) {
            networkManager.disconnect()
        }
    }
}
