package com.gomoku.game.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gomoku.game.R
import com.gomoku.game.ai.GomokuAI
import com.gomoku.game.databinding.ActivityGameBinding
import com.gomoku.game.engine.GameBoard
import com.gomoku.game.model.*

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIFFICULTY = "difficulty"
        private const val AI_DELAY_MS = 500L
    }

    private lateinit var binding: ActivityGameBinding
    private lateinit var gameBoard: GameBoard
    private lateinit var ai: GomokuAI
    private lateinit var difficulty: Difficulty

    private val handler = Handler(Looper.getMainLooper())
    private var isAIThinking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        difficulty = Difficulty.valueOf(
            intent.getStringExtra(EXTRA_DIFFICULTY) ?: Difficulty.MEDIUM.name
        )

        initGame()
        setupListeners()
        updateUI()
    }

    private fun initGame() {
        gameBoard = GameBoard()
        ai = GomokuAI(difficulty)

        binding.boardView.gameBoard = gameBoard
        binding.tvDifficulty.text = difficulty.displayName
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.boardView.onCellClickListener = { row, col ->
            onCellClick(row, col)
        }

        binding.btnUndo.setOnClickListener {
            undoMove()
        }

        binding.btnRestart.setOnClickListener {
            restartGame()
        }

        binding.btnShowNumbers.setOnClickListener {
            toggleMoveNumbers()
        }

        binding.btnPlayAgain.setOnClickListener {
            binding.gameOverOverlay.visibility = View.GONE
            restartGame()
        }

        binding.btnBackMenu.setOnClickListener {
            finish()
        }
    }

    private fun onCellClick(row: Int, col: Int) {
        // 忽略点击：游戏结束、AI思考中、不是玩家回合
        if (gameBoard.gameState != GameState.PLAYING) return
        if (isAIThinking) return
        if (gameBoard.currentTurn != Stone.BLACK) return

        // 尝试落子
        if (gameBoard.placeStone(row, col)) {
            binding.boardView.refresh()
            updateUI()

            // 检查游戏是否结束
            if (gameBoard.gameState != GameState.PLAYING) {
                showGameOver()
                return
            }

            // AI回合
            scheduleAIMove()
        }
    }

    private fun scheduleAIMove() {
        isAIThinking = true
        binding.tvStatus.text = getString(R.string.ai_thinking)
        updateButtonStates()

        handler.postDelayed({
            executeAIMove()
        }, AI_DELAY_MS)
    }

    private fun executeAIMove() {
        val aiMove = ai.getBestMove(gameBoard)

        if (aiMove != null) {
            gameBoard.placeStone(aiMove)
            binding.boardView.refresh()
        }

        isAIThinking = false
        updateUI()

        if (gameBoard.gameState != GameState.PLAYING) {
            showGameOver()
        }
    }

    private fun updateUI() {
        updateStatus()
        updateButtonStates()
    }

    private fun updateStatus() {
        binding.tvStatus.text = when (gameBoard.gameState) {
            GameState.PLAYING -> {
                if (gameBoard.currentTurn == Stone.BLACK) {
                    getString(R.string.your_turn)
                } else {
                    getString(R.string.ai_thinking)
                }
            }
            GameState.BLACK_WINS -> getString(R.string.you_win)
            GameState.WHITE_WINS -> getString(R.string.you_lose)
            GameState.DRAW -> getString(R.string.draw)
        }
    }

    private fun updateButtonStates() {
        val canUndo = gameBoard.moveHistory.size >= 2 &&
                gameBoard.gameState == GameState.PLAYING &&
                !isAIThinking

        binding.btnUndo.isEnabled = canUndo
        binding.btnUndo.alpha = if (canUndo) 1f else 0.5f
    }

    private fun undoMove() {
        if (gameBoard.undoTwo()) {
            binding.boardView.refresh()
            updateUI()
        }
    }

    private fun restartGame() {
        gameBoard.reset()
        binding.boardView.refresh()
        isAIThinking = false
        updateUI()
    }

    private fun toggleMoveNumbers() {
        binding.boardView.showMoveNumbers = !binding.boardView.showMoveNumbers
        binding.btnShowNumbers.text = if (binding.boardView.showMoveNumbers) {
            getString(R.string.hide_numbers)
        } else {
            getString(R.string.show_numbers)
        }
    }

    private fun showGameOver() {
        binding.gameOverOverlay.visibility = View.VISIBLE

        binding.tvGameOverTitle.text = getString(R.string.game_over)

        when (gameBoard.gameState) {
            GameState.BLACK_WINS -> {
                binding.tvGameOverResult.text = getString(R.string.you_win)
                binding.tvGameOverResult.setTextColor(
                    ContextCompat.getColor(this, R.color.win_color)
                )
            }
            GameState.WHITE_WINS -> {
                binding.tvGameOverResult.text = getString(R.string.you_lose)
                binding.tvGameOverResult.setTextColor(
                    ContextCompat.getColor(this, R.color.lose_color)
                )
            }
            GameState.DRAW -> {
                binding.tvGameOverResult.text = getString(R.string.draw)
                binding.tvGameOverResult.setTextColor(
                    ContextCompat.getColor(this, R.color.draw_color)
                )
            }
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
