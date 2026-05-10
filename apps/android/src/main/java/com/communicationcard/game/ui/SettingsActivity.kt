package com.communicationcard.game.ui

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.communicationcard.game.R

/**
 * 设置界面
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var preferences: GamePreferences

    // 设置控件
    private lateinit var switchSound: Switch
    private lateinit var switchVibration: Switch
    private lateinit var rgSpeed: RadioGroup
    private lateinit var rbSpeedSlow: RadioButton
    private lateinit var rbSpeedNormal: RadioButton
    private lateinit var rbSpeedFast: RadioButton
    private lateinit var rbSpeedVeryFast: RadioButton
    private lateinit var btnBack: Button

    // 统计控件
    private lateinit var tvTotalGames: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var tvBestStreak: TextView
    private lateinit var tvWins: TextView
    private lateinit var tvLosses: TextView
    private lateinit var tvCurrentStreak: TextView
    private lateinit var tvTotalScore: TextView
    private lateinit var btnResetStats: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_settings)

        preferences = GamePreferences(this)

        initViews()
        loadSettings()
        loadStatistics()
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
        // 设置控件
        switchSound = findViewById(R.id.switchSound)
        switchVibration = findViewById(R.id.switchVibration)
        rgSpeed = findViewById(R.id.rgSpeed)
        rbSpeedSlow = findViewById(R.id.rbSpeedSlow)
        rbSpeedNormal = findViewById(R.id.rbSpeedNormal)
        rbSpeedFast = findViewById(R.id.rbSpeedFast)
        rbSpeedVeryFast = findViewById(R.id.rbSpeedVeryFast)
        btnBack = findViewById(R.id.btnBack)

        // 统计控件
        tvTotalGames = findViewById(R.id.tvTotalGames)
        tvWinRate = findViewById(R.id.tvWinRate)
        tvBestStreak = findViewById(R.id.tvBestStreak)
        tvWins = findViewById(R.id.tvWins)
        tvLosses = findViewById(R.id.tvLosses)
        tvCurrentStreak = findViewById(R.id.tvCurrentStreak)
        tvTotalScore = findViewById(R.id.tvTotalScore)
        btnResetStats = findViewById(R.id.btnResetStats)
    }

    private fun loadSettings() {
        switchSound.isChecked = preferences.isSoundEnabled
        switchVibration.isChecked = preferences.isVibrationEnabled

        when (preferences.gameSpeed) {
            GamePreferences.SPEED_SLOW -> rbSpeedSlow.isChecked = true
            GamePreferences.SPEED_NORMAL -> rbSpeedNormal.isChecked = true
            GamePreferences.SPEED_FAST -> rbSpeedFast.isChecked = true
            GamePreferences.SPEED_VERY_FAST -> rbSpeedVeryFast.isChecked = true
        }
    }

    private fun loadStatistics() {
        tvTotalGames.text = preferences.totalGames.toString()
        tvWinRate.text = "${(preferences.getWinRate() * 100).toInt()}%"
        tvBestStreak.text = preferences.bestStreak.toString()
        tvWins.text = preferences.wins.toString()
        tvLosses.text = preferences.losses.toString()
        tvCurrentStreak.text = preferences.currentStreak.toString()
        tvTotalScore.text = preferences.totalScore.toString()
    }

    private fun setupListeners() {
        switchSound.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSoundEnabled = isChecked
        }

        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            preferences.isVibrationEnabled = isChecked
        }

        rgSpeed.setOnCheckedChangeListener { _, checkedId ->
            val speed = when (checkedId) {
                R.id.rbSpeedSlow -> GamePreferences.SPEED_SLOW
                R.id.rbSpeedNormal -> GamePreferences.SPEED_NORMAL
                R.id.rbSpeedFast -> GamePreferences.SPEED_FAST
                R.id.rbSpeedVeryFast -> GamePreferences.SPEED_VERY_FAST
                else -> GamePreferences.SPEED_NORMAL
            }
            preferences.gameSpeed = speed
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnResetStats.setOnClickListener {
            showResetConfirmDialog()
        }
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("重置统计")
            .setMessage("确定要重置所有游戏统计数据吗？此操作无法撤销。")
            .setPositiveButton("确定") { _, _ ->
                preferences.resetStatistics()
                loadStatistics()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
