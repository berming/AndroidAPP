package com.communicationcard.game.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.communicationcard.game.R
import com.communicationcard.game.ai.AIDifficulty
import com.communicationcard.game.ui.multiplayer.LobbyActivity

class MainActivity : AppCompatActivity() {

    private lateinit var rgDifficulty: RadioGroup
    private lateinit var rgPlayerCount: RadioGroup
    private lateinit var btnStartGame: Button
    private lateinit var btnMultiplayer: Button
    private lateinit var btnRules: Button
    private lateinit var btnSettings: Button

    private lateinit var preferences: GamePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        preferences = GamePreferences(this)
        initViews()
        restoreLastSelections()
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
        rgDifficulty = findViewById(R.id.rgDifficulty)
        rgPlayerCount = findViewById(R.id.rgPlayerCount)
        btnStartGame = findViewById(R.id.btnStartGame)
        btnMultiplayer = findViewById(R.id.btnMultiplayer)
        btnRules = findViewById(R.id.btnRules)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun restoreLastSelections() {
        // Restore last used difficulty
        val diffId = when (preferences.lastDifficulty) {
            AIDifficulty.EASY.name -> R.id.rbEasy
            AIDifficulty.HARD.name -> R.id.rbHard
            else -> R.id.rbMedium
        }
        rgDifficulty.check(diffId)

        // Restore last used player count
        val playerCountId = when (preferences.lastPlayerCount) {
            8 -> R.id.rb8Players
            else -> R.id.rb6Players
        }
        rgPlayerCount.check(playerCountId)
    }

    private fun setupListeners() {
        btnStartGame.setOnClickListener {
            startGame()
        }

        btnMultiplayer.setOnClickListener {
            startActivity(Intent(this, LobbyActivity::class.java))
        }

        btnRules.setOnClickListener {
            showRulesDialog()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

        // Save last selections
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
}
