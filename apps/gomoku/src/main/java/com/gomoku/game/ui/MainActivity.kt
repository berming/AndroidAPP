package com.gomoku.game.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gomoku.game.R
import com.gomoku.game.databinding.ActivityMainBinding
import com.gomoku.game.model.Difficulty

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnStart.setOnClickListener {
            val difficulty = when (binding.rgDifficulty.checkedRadioButtonId) {
                R.id.rbEasy -> Difficulty.EASY
                R.id.rbHard -> Difficulty.HARD
                else -> Difficulty.MEDIUM
            }

            val intent = Intent(this, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty.name)
            }
            startActivity(intent)
        }
    }
}
