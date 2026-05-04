package com.communicationcard.game.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.communicationcard.game.R
import com.communicationcard.game.ai.AIDifficulty
import com.communicationcard.game.network.ConnectionState
import com.communicationcard.game.network.NetworkManager
import com.communicationcard.game.network.RoomEvent
import com.communicationcard.game.network.RoomManager
import com.communicationcard.game.ui.multiplayer.NetworkManagerHolder
import com.communicationcard.game.ui.multiplayer.RoomActivity
import com.communicationcard.game.ui.multiplayer.RoomManagerHolder
import com.communicationcard.game.util.DebugLogManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SERVER_URL = "ws://175.178.158.35:8080/game"
        private const val MAX_NAME_LENGTH = 12
    }

    private lateinit var preferences: GamePreferences

    // 菜单
    private lateinit var menuItems: List<TextView>
    private lateinit var panels: List<View>

    // 单人游戏面板
    private lateinit var rgDifficulty: RadioGroup
    private lateinit var rgPlayerCount: RadioGroup

    // 多人游戏面板
    private var networkManager: NetworkManager? = null
    private var roomManager: RoomManager? = null
    private var multiplayerInited = false
    private var playerName: String = ""
    private lateinit var multiplayerPanel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        preferences = GamePreferences(this)
        playerName = preferences.lastPlayerName

        initViews()
        restoreLastSelections()
        setupListeners()
        selectMenu(0)

        DebugLogManager.i(TAG, "App started")
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        menuItems = listOf(
            findViewById(R.id.menuSinglePlayer),
            findViewById(R.id.menuMultiplayer),
            findViewById(R.id.menuSettings),
            findViewById(R.id.menuStats),
            findViewById(R.id.menuHelp)
        )
        val panelSinglePlayer = findViewById<View>(R.id.panelSinglePlayer)
        multiplayerPanel = findViewById(R.id.panelMultiplayer)
        val panelSettings = findViewById<View>(R.id.panelSettings)
        val panelStats = findViewById<View>(R.id.panelStats)
        val panelHelp = findViewById<View>(R.id.panelHelp)
        panels = listOf(panelSinglePlayer, multiplayerPanel, panelSettings, panelStats, panelHelp)

        rgDifficulty = panelSinglePlayer.findViewById(R.id.rgDifficulty)
        rgPlayerCount = panelSinglePlayer.findViewById(R.id.rgPlayerCount)
    }

    private fun restoreLastSelections() {
        val diffId = when (preferences.lastDifficulty) {
            AIDifficulty.EASY.name -> R.id.rbEasy
            AIDifficulty.HARD.name -> R.id.rbHard
            else -> R.id.rbMedium
        }
        rgDifficulty.check(diffId)

        val playerCountId = when (preferences.lastPlayerCount) {
            8 -> R.id.rb8Players
            else -> R.id.rb6Players
        }
        rgPlayerCount.check(playerCountId)

        val panelSettings = panels[2]
        panelSettings.findViewById<Switch>(R.id.switchSound)?.isChecked = preferences.isSoundEnabled
        panelSettings.findViewById<Switch>(R.id.switchVibration)?.isChecked = preferences.isVibrationEnabled
        panelSettings.findViewById<Switch>(R.id.switchAnimation)?.isChecked = preferences.isAnimationEnabled
    }

    private fun setupListeners() {
        // 菜单点击
        menuItems.forEachIndexed { index, menu ->
            menu.setOnClickListener { selectMenu(index) }
        }

        // 单人游戏
        panels[0].findViewById<Button>(R.id.btnStartGame).setOnClickListener {
            startGame()
        }

        // 多人游戏面板
        setupMultiplayerListeners()

        // 设置面板
        val panelSettings = panels[2]
        panelSettings.findViewById<Switch>(R.id.switchSound)?.setOnCheckedChangeListener { _, c ->
            preferences.isSoundEnabled = c
        }
        panelSettings.findViewById<Switch>(R.id.switchVibration)?.setOnCheckedChangeListener { _, c ->
            preferences.isVibrationEnabled = c
        }
        panelSettings.findViewById<Switch>(R.id.switchAnimation)?.setOnCheckedChangeListener { _, c ->
            preferences.isAnimationEnabled = c
        }

        // 帮助面板
        val panelHelp = panels[4]
        panelHelp.findViewById<Button>(R.id.btnRules)?.setOnClickListener { showRulesDialog() }
        panelHelp.findViewById<Button>(R.id.btnAbout)?.setOnClickListener { showAboutDialog() }
        panelHelp.findViewById<Button>(R.id.btnDebugLogs)?.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
    }

    private fun selectMenu(index: Int) {
        menuItems.forEachIndexed { i, menu -> menu.isSelected = (i == index) }
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        // 进入多人游戏菜单时初始化网络
        if (index == 1) {
            initMultiplayer()
        }
    }

    // ===================== 单人游戏 =====================

    private fun startGame() {
        val difficulty = when (rgDifficulty.checkedRadioButtonId) {
            R.id.rbEasy -> AIDifficulty.EASY
            R.id.rbMedium -> AIDifficulty.MEDIUM
            R.id.rbHard -> AIDifficulty.HARD
            else -> AIDifficulty.MEDIUM
        }

        val playerCount = when (rgPlayerCount.checkedRadioButtonId) {
            R.id.rb6Players -> 6
            R.id.rb8Players -> 8
            else -> 6
        }

        preferences.lastDifficulty = difficulty.name
        preferences.lastPlayerCount = playerCount

        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty.name)
            putExtra(GameActivity.EXTRA_PLAYER_COUNT, playerCount)
        }
        startActivity(intent)
    }

    // ===================== 多人游戏 =====================

    private fun initMultiplayer() {
        if (multiplayerInited) {
            // 已初始化，刷新昵称
            refreshPlayerName()
            return
        }

        DebugLogManager.i(TAG, "initMultiplayer")
        try {
            networkManager = NetworkManager(this, SERVER_URL)
            roomManager = RoomManager(networkManager!!)

            lifecycleScope.launch {
                networkManager!!.connectionState.collectLatest { state ->
                    updateConnectionUI(state)
                }
            }
            lifecycleScope.launch {
                roomManager!!.roomEvents.collectLatest { event ->
                    handleRoomEvent(event)
                }
            }

            networkManager!!.connect()
            multiplayerInited = true

            refreshPlayerName()

            // 首次进入：如果没有昵称，提示输入
            if (playerName.isEmpty()) {
                showNameDialog(firstTime = true)
            }
        } catch (e: Exception) {
            DebugLogManager.e(TAG, "initMultiplayer failed", e)
            Toast.makeText(this, "初始化网络失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMultiplayerListeners() {
        multiplayerPanel.findViewById<View>(R.id.playerInfoBar).setOnClickListener {
            showNameDialog(firstTime = false)
        }
        multiplayerPanel.findViewById<TextView>(R.id.btnEditName).setOnClickListener {
            showNameDialog(firstTime = false)
        }
        multiplayerPanel.findViewById<Button>(R.id.btnCreateRoom).setOnClickListener {
            createRoom()
        }
        multiplayerPanel.findViewById<Button>(R.id.btnJoinRoom).setOnClickListener {
            joinRoom()
        }
    }

    private fun refreshPlayerName() {
        val tv = multiplayerPanel.findViewById<TextView>(R.id.tvPlayerName)
        if (playerName.isEmpty()) {
            tv.text = "未设置（点击设置）"
            tv.alpha = 0.6f
        } else {
            tv.text = playerName
            tv.alpha = 1f
        }
    }

    private fun showNameDialog(firstTime: Boolean) {
        val input = EditText(this).apply {
            setText(playerName)
            setSelection(playerName.length)
            hint = "输入昵称（最多 $MAX_NAME_LENGTH 个字符）"
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))
        }
        val container = FrameLayout(this).apply {
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (firstTime) "设置玩家昵称" else "修改玩家昵称")
            .setMessage(if (firstTime) "首次进入多人游戏，请先设置一个昵称" else null)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show()
                    if (firstTime) showNameDialog(firstTime = true)
                } else {
                    playerName = name
                    preferences.lastPlayerName = name
                    refreshPlayerName()
                }
            }
        if (!firstTime) builder.setNegativeButton("取消", null)
        else builder.setCancelable(false)
        builder.show()
    }

    private fun updateConnectionUI(state: ConnectionState) {
        val statusIndicator = multiplayerPanel.findViewById<View>(R.id.statusIndicator)
        val tvStatus = multiplayerPanel.findViewById<TextView>(R.id.tvConnectionStatus)
        val loading = multiplayerPanel.findViewById<View>(R.id.loadingOverlay)
        when (state) {
            is ConnectionState.Disconnected -> {
                statusIndicator.setBackgroundColor(Color.GRAY)
                tvStatus.text = "未连接"
                if (loading.visibility == View.VISIBLE) {
                    loading.visibility = View.GONE
                    Toast.makeText(this, "连接已断开，请重试", Toast.LENGTH_SHORT).show()
                }
            }
            is ConnectionState.Connecting -> {
                statusIndicator.setBackgroundColor(Color.YELLOW)
                tvStatus.text = "正在连接..."
            }
            is ConnectionState.Connected -> {
                statusIndicator.setBackgroundColor(Color.GREEN)
                tvStatus.text = "已连接"
            }
            is ConnectionState.Reconnecting -> {
                statusIndicator.setBackgroundColor(Color.YELLOW)
                tvStatus.text = "重连中(${state.attempt})..."
                if (loading.visibility == View.VISIBLE) {
                    loading.visibility = View.GONE
                    Toast.makeText(this, "连接中断，正在重连...", Toast.LENGTH_SHORT).show()
                }
            }
            is ConnectionState.Error -> {
                statusIndicator.setBackgroundColor(Color.RED)
                tvStatus.text = "连接错误"
                loading.visibility = View.GONE
                Toast.makeText(this, "连接失败: ${state.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        DebugLogManager.i(TAG, "handleRoomEvent: $event")
        when (event) {
            is RoomEvent.RoomCreated -> {
                DebugLogManager.i(TAG, "RoomCreated: ${event.room.roomCode}")
                hideMultiplayerLoading()
                navigateToRoom()
            }
            is RoomEvent.JoinedRoom -> {
                DebugLogManager.i(TAG, "JoinedRoom: ${event.room.roomCode}")
                hideMultiplayerLoading()
                navigateToRoom()
            }
            is RoomEvent.Error -> {
                DebugLogManager.e(TAG, "RoomEvent Error: ${event.message}")
                hideMultiplayerLoading()
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
            hint = "房间名称（可选）"
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        val container = FrameLayout(this).apply {
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("创建房间")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                doCreateRoom(input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doCreateRoom(roomName: String) {
        val rm = roomManager ?: return
        showMultiplayerLoading("正在创建房间...")
        val sent = rm.createRoom(playerName, roomName, 6)
        if (!sent) {
            hideMultiplayerLoading()
            Toast.makeText(this, "未连接到服务器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun joinRoom() {
        if (playerName.isEmpty()) {
            showNameDialog(firstTime = true)
            return
        }
        val etRoomCode = multiplayerPanel.findViewById<EditText>(R.id.etRoomCode)
        val code = etRoomCode.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            Toast.makeText(this, "请输入房间码", Toast.LENGTH_SHORT).show()
            return
        }
        if (code.length < 4) {
            Toast.makeText(this, "房间码格式错误", Toast.LENGTH_SHORT).show()
            return
        }
        val rm = roomManager ?: return
        showMultiplayerLoading("正在加入房间...")
        val sent = rm.joinRoom(code, playerName)
        if (!sent) {
            hideMultiplayerLoading()
            Toast.makeText(this, "未连接到服务器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMultiplayerLoading(text: String) {
        multiplayerPanel.findViewById<TextView>(R.id.tvLoadingText).text = text
        multiplayerPanel.findViewById<View>(R.id.loadingOverlay).visibility = View.VISIBLE
    }

    private fun hideMultiplayerLoading() {
        multiplayerPanel.findViewById<View>(R.id.loadingOverlay).visibility = View.GONE
    }

    private fun navigateToRoom() {
        val nm = networkManager ?: return
        val rm = roomManager ?: return
        DebugLogManager.i(TAG, "navigateToRoom, currentRoom=${rm.currentRoom.value}")
        NetworkManagerHolder.instance = nm
        RoomManagerHolder.instance = rm
        startActivity(Intent(this, RoomActivity::class.java))
    }

    // ===================== 帮助 =====================

    private fun showRulesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rules, null)
        AlertDialog.Builder(this, R.style.Theme_CommunicationCard)
            .setView(dialogView)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于沟通牌")
            .setMessage("沟通牌是一款团队对抗型扑克牌游戏。\n\n版本：1.0.0")
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从房间返回时刷新昵称
        playerName = preferences.lastPlayerName
        if (multiplayerInited) {
            refreshPlayerName()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果不在房间中，断开连接
        if (multiplayerInited && roomManager?.currentRoom?.value == null) {
            networkManager?.disconnect()
        }
    }
}
