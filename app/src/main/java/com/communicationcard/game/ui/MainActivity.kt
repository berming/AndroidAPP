package com.communicationcard.game.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.communicationcard.game.R
import com.communicationcard.game.ai.AIDifficulty

class MainActivity : AppCompatActivity() {

    private lateinit var rgDifficulty: RadioGroup
    private lateinit var rgPlayerCount: RadioGroup
    private lateinit var btnStartGame: Button
    private lateinit var btnRules: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        rgDifficulty = findViewById(R.id.rgDifficulty)
        rgPlayerCount = findViewById(R.id.rgPlayerCount)
        btnStartGame = findViewById(R.id.btnStartGame)
        btnRules = findViewById(R.id.btnRules)
    }

    private fun setupListeners() {
        btnStartGame.setOnClickListener {
            startGame()
        }

        btnRules.setOnClickListener {
            showRulesDialog()
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
