package com.communicationcard.game.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.communicationcard.game.R
import com.communicationcard.game.ai.AIDifficulty
import com.communicationcard.game.ui.multiplayer.LobbyActivity
import com.communicationcard.game.util.DebugLogManager

class MainActivity : AppCompatActivity() {

    private lateinit var preferences: GamePreferences

    // 菜单项
    private lateinit var menuSinglePlayer: TextView
    private lateinit var menuMultiplayer: TextView
    private lateinit var menuSettings: TextView
    private lateinit var menuStats: TextView
    private lateinit var menuHelp: TextView
    private val menuItems = mutableListOf<TextView>()

    // 面板
    private lateinit var panelSinglePlayer: View
    private lateinit var panelMultiplayer: View
    private lateinit var panelSettings: View
    private lateinit var panelStats: View
    private lateinit var panelHelp: View
    private val panels = mutableListOf<View>()

    // 单人游戏面板
    private lateinit var rgDifficulty: RadioGroup
    private lateinit var rgPlayerCount: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        preferences = GamePreferences(this)
        initViews()
        restoreLastSelections()
        setupListeners()
        selectMenu(0)

        DebugLogManager.i("MainActivity", "App started")
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
        // 关闭按钮
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        // 菜单项
        menuSinglePlayer = findViewById(R.id.menuSinglePlayer)
        menuMultiplayer = findViewById(R.id.menuMultiplayer)
        menuSettings = findViewById(R.id.menuSettings)
        menuStats = findViewById(R.id.menuStats)
        menuHelp = findViewById(R.id.menuHelp)
        menuItems.addAll(listOf(menuSinglePlayer, menuMultiplayer, menuSettings, menuStats, menuHelp))

        // 面板
        panelSinglePlayer = findViewById(R.id.panelSinglePlayer)
        panelMultiplayer = findViewById(R.id.panelMultiplayer)
        panelSettings = findViewById(R.id.panelSettings)
        panelStats = findViewById(R.id.panelStats)
        panelHelp = findViewById(R.id.panelHelp)
        panels.addAll(listOf(panelSinglePlayer, panelMultiplayer, panelSettings, panelStats, panelHelp))

        // 单人游戏面板内的控件
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

        // 恢复设置
        panelSettings.findViewById<Switch>(R.id.switchSound)?.isChecked = preferences.isSoundEnabled
        panelSettings.findViewById<Switch>(R.id.switchVibration)?.isChecked = preferences.isVibrationEnabled
        panelSettings.findViewById<Switch>(R.id.switchAnimation)?.isChecked = preferences.isAnimationEnabled
    }

    private fun setupListeners() {
        // 菜单点击
        menuItems.forEachIndexed { index, menu ->
            menu.setOnClickListener { selectMenu(index) }
        }

        // 单人游戏 - 开始按钮
        panelSinglePlayer.findViewById<Button>(R.id.btnStartGame).setOnClickListener {
            startGame()
        }

        // 联网对抗 - 进入大厅
        panelMultiplayer.findViewById<Button>(R.id.btnEnterLobby).setOnClickListener {
            startActivity(Intent(this, LobbyActivity::class.java))
        }

        // 设置面板
        panelSettings.findViewById<Switch>(R.id.switchSound)?.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSoundEnabled = isChecked
        }
        panelSettings.findViewById<Switch>(R.id.switchVibration)?.setOnCheckedChangeListener { _, isChecked ->
            preferences.isVibrationEnabled = isChecked
        }
        panelSettings.findViewById<Switch>(R.id.switchAnimation)?.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAnimationEnabled = isChecked
        }
        panelSettings.findViewById<Button>(R.id.btnDebugLogs)?.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // 帮助面板
        panelHelp.findViewById<Button>(R.id.btnRules)?.setOnClickListener {
            showRulesDialog()
        }
        panelHelp.findViewById<Button>(R.id.btnAbout)?.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun selectMenu(index: Int) {
        // 更新菜单选中状态
        menuItems.forEachIndexed { i, menu ->
            menu.isSelected = (i == index)
        }

        // 显示对应面板
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

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
}
