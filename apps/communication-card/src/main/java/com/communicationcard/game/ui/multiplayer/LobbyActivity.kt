package com.communicationcard.game.ui.multiplayer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
    }

    private lateinit var networkManager: NetworkManager
    private lateinit var roomManager: RoomManager
    private lateinit var preferences: GamePreferences

    // UI
    private lateinit var etPlayerName: EditText
    private lateinit var etRoomCode: EditText
    private lateinit var btnCreateRoom: Button
    private lateinit var btnJoinRoom: Button
    private lateinit var btnBack: Button
    private lateinit var statusIndicator: View
    private lateinit var tvConnectionStatus: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var tvLoadingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_lobby)

        preferences = GamePreferences(this)
        initViews()
        initNetwork()
        setupListeners()
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
        etPlayerName = findViewById(R.id.etPlayerName)
        etRoomCode = findViewById(R.id.etRoomCode)
        btnCreateRoom = findViewById(R.id.btnCreateRoom)
        btnJoinRoom = findViewById(R.id.btnJoinRoom)
        btnBack = findViewById(R.id.btnBack)
        statusIndicator = findViewById(R.id.statusIndicator)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoadingText = findViewById(R.id.tvLoadingText)

        // 恢复上次使用的昵称
        etPlayerName.setText(preferences.lastPlayerName)
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
        btnCreateRoom.setOnClickListener {
            createRoom()
        }

        btnJoinRoom.setOnClickListener {
            joinRoom()
        }

        btnBack.setOnClickListener {
            finish()
        }
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
                hideLoading()
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
                // 保存昵称
                preferences.lastPlayerName = etPlayerName.text.toString().trim()
                // 跳转到房间
                navigateToRoom()
            }
            is RoomEvent.JoinedRoom -> {
                hideLoading()
                // 保存昵称
                preferences.lastPlayerName = etPlayerName.text.toString().trim()
                // 跳转到房间
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
        val playerName = etPlayerName.text.toString().trim()
        if (playerName.isEmpty()) {
            Toast.makeText(this, "请输入昵称", Toast.LENGTH_SHORT).show()
            return
        }
        if (playerName.length < 2) {
            Toast.makeText(this, "昵称至少2个字符", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading("正在创建房间...")
        roomManager.createRoom(playerName, 6)
    }

    private fun joinRoom() {
        val playerName = etPlayerName.text.toString().trim()
        if (playerName.isEmpty()) {
            Toast.makeText(this, "请输入昵称", Toast.LENGTH_SHORT).show()
            return
        }
        if (playerName.length < 2) {
            Toast.makeText(this, "昵称至少2个字符", Toast.LENGTH_SHORT).show()
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
        roomManager.joinRoom(roomCode, playerName)
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

