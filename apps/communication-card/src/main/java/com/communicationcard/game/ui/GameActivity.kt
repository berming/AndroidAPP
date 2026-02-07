package com.communicationcard.game.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.communicationcard.game.R
import com.communicationcard.game.ai.AIDifficulty
import com.communicationcard.game.engine.GameEngine
import com.communicationcard.game.engine.GameEvent
import com.communicationcard.game.engine.GamePhase
import com.communicationcard.game.engine.GameResult
import com.communicationcard.game.model.*

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIFFICULTY = "difficulty"
        const val EXTRA_PLAYER_COUNT = "player_count"
        private const val AI_DELAY_MS = 1000L
        private const val MESSAGE_DISPLAY_MS = 1500L
    }

    private lateinit var gameEngine: GameEngine
    private val handler = Handler(Looper.getMainLooper())

    // UI Elements
    private lateinit var tvTeamAScore: TextView
    private lateinit var tvTeamBScore: TextView
    private lateinit var tvGameStatus: TextView
    private lateinit var playedCardsContainer: LinearLayout
    private lateinit var playerHandContainer: LinearLayout
    private lateinit var tvMessage: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnPass: Button
    private lateinit var btnHint: Button
    private lateinit var gameOverOverlay: FrameLayout
    private lateinit var tvGameOverTitle: TextView
    private lateinit var tvGameOverResult: TextView
    private lateinit var tvFinalScore: TextView
    private lateinit var btnPlayAgain: Button
    private lateinit var btnBackMenu: Button

    // Opponent views
    private val opponentViews = mutableListOf<View>()
    // Teammate views
    private val teammateViews = mutableListOf<View>()

    // Selected cards for playing
    private val selectedCards = mutableListOf<Card>()
    private val cardViewMap = mutableMapOf<Card, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val difficulty = AIDifficulty.valueOf(
            intent.getStringExtra(EXTRA_DIFFICULTY) ?: AIDifficulty.MEDIUM.name
        )
        val playerCount = intent.getIntExtra(EXTRA_PLAYER_COUNT, 6)

        initViews()
        initGame(playerCount, difficulty)
        setupListeners()
    }

    private fun initViews() {
        tvTeamAScore = findViewById(R.id.tvTeamAScore)
        tvTeamBScore = findViewById(R.id.tvTeamBScore)
        tvGameStatus = findViewById(R.id.tvGameStatus)
        playedCardsContainer = findViewById(R.id.playedCardsContainer)
        playerHandContainer = findViewById(R.id.playerHandContainer)
        tvMessage = findViewById(R.id.tvMessage)
        btnPlay = findViewById(R.id.btnPlay)
        btnPass = findViewById(R.id.btnPass)
        btnHint = findViewById(R.id.btnHint)
        gameOverOverlay = findViewById(R.id.gameOverOverlay)
        tvGameOverTitle = findViewById(R.id.tvGameOverTitle)
        tvGameOverResult = findViewById(R.id.tvGameOverResult)
        tvFinalScore = findViewById(R.id.tvFinalScore)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)
        btnBackMenu = findViewById(R.id.btnBackMenu)

        // Initialize opponent views
        opponentViews.add(findViewById(R.id.opponent1))
        opponentViews.add(findViewById(R.id.opponent2))
        opponentViews.add(findViewById(R.id.opponent3))

        // Initialize teammate views
        teammateViews.add(findViewById(R.id.teammate1))
        teammateViews.add(findViewById(R.id.teammate2))
    }

    private fun initGame(playerCount: Int, difficulty: AIDifficulty) {
        gameEngine = GameEngine(playerCount, difficulty)
        gameEngine.initializeGame()

        gameEngine.addEventListener { event ->
            runOnUiThread {
                handleGameEvent(event)
            }
        }

        // Start the game
        handler.postDelayed({
            gameEngine.startGame()
        }, 500)
    }

    private fun setupListeners() {
        btnPlay.setOnClickListener {
            if (selectedCards.isNotEmpty()) {
                if (gameEngine.humanPlay(selectedCards.toList())) {
                    selectedCards.clear()
                    updateButtonStates()
                }
            }
        }

        btnPass.setOnClickListener {
            if (gameEngine.humanPass()) {
                selectedCards.clear()
                updateButtonStates()
            }
        }

        btnHint.setOnClickListener {
            showHint()
        }

        btnPlayAgain.setOnClickListener {
            restartGame()
        }

        btnBackMenu.setOnClickListener {
            finish()
        }
    }

    private fun handleGameEvent(event: GameEvent) {
        when (event) {
            is GameEvent.CardsDealt -> {
                updateAllPlayerViews()
                updatePlayerHand()
                updateScores()
            }

            is GameEvent.TurnStart -> {
                updateTurnIndicator(event.player)
                if (event.player.type == PlayerType.AI) {
                    scheduleAITurn()
                } else {
                    updateButtonStates()
                }
            }

            is GameEvent.CardsPlayed -> {
                showPlayedCards(event.cardGroup)
                updatePlayerView(event.player)
                if (event.player.type == PlayerType.HUMAN) {
                    updatePlayerHand()
                }
                showMessage("${event.player.name} 出了 ${event.cardGroup}")
            }

            is GameEvent.PlayerPassed -> {
                showMessage("${event.player.name} 过牌")
                updatePlayerStatus(event.player, "过牌")
            }

            is GameEvent.RoundWon -> {
                showMessage("${event.player.name} 赢得此轮，获得 ${event.score} 分")
                handler.postDelayed({
                    playedCardsContainer.removeAllViews()
                    clearAllPlayerStatus()
                }, MESSAGE_DISPLAY_MS)
            }

            is GameEvent.PlayerFinished -> {
                showMessage("${event.player.name} 已走完（第${event.order}个）")
                updatePlayerView(event.player)
            }

            is GameEvent.ScoreUpdate -> {
                updateScores()
            }

            is GameEvent.GameEnded -> {
                showGameOver(event.result)
            }

            is GameEvent.AICommunication -> {
                updatePlayerStatus(event.player, event.message)
            }
        }
    }

    private fun updateTurnIndicator(player: Player) {
        tvGameStatus.text = if (player.type == PlayerType.HUMAN) {
            "轮到你出牌"
        } else {
            "等待 ${player.name} 出牌"
        }

        // Highlight current player
        highlightCurrentPlayer(player)
    }

    private fun highlightCurrentPlayer(player: Player) {
        // Reset all highlights
        opponentViews.forEach { it.alpha = 0.7f }
        teammateViews.forEach { it.alpha = 0.7f }

        // Find and highlight current player
        val playerIndex = player.id
        if (player.type == PlayerType.HUMAN) {
            // Human player area is always visible
            return
        }

        // Find the view for this player
        val opponents = gameEngine.players.filter { it.team != gameEngine.humanPlayer?.team }
        val teammates = gameEngine.players.filter {
            it.team == gameEngine.humanPlayer?.team && it.type == PlayerType.AI
        }

        val opponentIndex = opponents.indexOfFirst { it.id == player.id }
        if (opponentIndex >= 0 && opponentIndex < opponentViews.size) {
            opponentViews[opponentIndex].alpha = 1.0f
        }

        val teammateIndex = teammates.indexOfFirst { it.id == player.id }
        if (teammateIndex >= 0 && teammateIndex < teammateViews.size) {
            teammateViews[teammateIndex].alpha = 1.0f
        }
    }

    private fun updateAllPlayerViews() {
        val humanTeam = gameEngine.humanPlayer?.team ?: Team.TEAM_A
        val opponents = gameEngine.players.filter { it.team != humanTeam }
        val teammates = gameEngine.players.filter { it.team == humanTeam && it.type == PlayerType.AI }

        // Update opponent views
        opponents.forEachIndexed { index, player ->
            if (index < opponentViews.size) {
                updateOpponentView(opponentViews[index], player)
            }
        }

        // Hide unused opponent views
        for (i in opponents.size until opponentViews.size) {
            opponentViews[i].visibility = View.GONE
        }

        // Update teammate views
        teammates.forEachIndexed { index, player ->
            if (index < teammateViews.size) {
                updateTeammateView(teammateViews[index], player)
            }
        }

        // Hide unused teammate views
        for (i in teammates.size until teammateViews.size) {
            teammateViews[i].visibility = View.GONE
        }
    }

    private fun updateOpponentView(view: View, player: Player) {
        view.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvPlayerName).text = player.name
        view.findViewById<TextView>(R.id.tvCardCount).text = "${player.handSize}张牌"
        view.findViewById<TextView>(R.id.tvScore).text = "已收: ${player.collectedScore}分"

        val indicator = view.findViewById<View>(R.id.teamIndicator)
        indicator.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (player.team == Team.TEAM_A) R.color.team_a else R.color.team_b
            )
        )

        if (player.hasFinished) {
            view.findViewById<TextView>(R.id.tvStatus).apply {
                visibility = View.VISIBLE
                text = "已走完"
            }
        }
    }

    private fun updateTeammateView(view: View, player: Player) {
        view.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvPlayerName).text = player.name
        view.findViewById<TextView>(R.id.tvCardCount).text = "${player.handSize}张"
        view.findViewById<TextView>(R.id.tvScore).text = "已收: ${player.collectedScore}分"

        if (player.hasFinished) {
            view.findViewById<TextView>(R.id.tvStatus).apply {
                visibility = View.VISIBLE
                text = "已走完"
            }
        }
    }

    private fun updatePlayerView(player: Player) {
        val humanTeam = gameEngine.humanPlayer?.team ?: Team.TEAM_A
        val opponents = gameEngine.players.filter { it.team != humanTeam }
        val teammates = gameEngine.players.filter { it.team == humanTeam && it.type == PlayerType.AI }

        val opponentIndex = opponents.indexOfFirst { it.id == player.id }
        if (opponentIndex >= 0 && opponentIndex < opponentViews.size) {
            updateOpponentView(opponentViews[opponentIndex], player)
        }

        val teammateIndex = teammates.indexOfFirst { it.id == player.id }
        if (teammateIndex >= 0 && teammateIndex < teammateViews.size) {
            updateTeammateView(teammateViews[teammateIndex], player)
        }
    }

    private fun updatePlayerStatus(player: Player, status: String) {
        val humanTeam = gameEngine.humanPlayer?.team ?: Team.TEAM_A
        val opponents = gameEngine.players.filter { it.team != humanTeam }
        val teammates = gameEngine.players.filter { it.team == humanTeam && it.type == PlayerType.AI }

        val opponentIndex = opponents.indexOfFirst { it.id == player.id }
        if (opponentIndex >= 0 && opponentIndex < opponentViews.size) {
            opponentViews[opponentIndex].findViewById<TextView>(R.id.tvStatus).apply {
                visibility = View.VISIBLE
                text = status
            }
        }

        val teammateIndex = teammates.indexOfFirst { it.id == player.id }
        if (teammateIndex >= 0 && teammateIndex < teammateViews.size) {
            teammateViews[teammateIndex].findViewById<TextView>(R.id.tvStatus).apply {
                visibility = View.VISIBLE
                text = status
            }
        }
    }

    private fun clearAllPlayerStatus() {
        opponentViews.forEach { view ->
            val statusView = view.findViewById<TextView>(R.id.tvStatus)
            if (statusView.text != "已走完") {
                statusView.visibility = View.GONE
            }
        }
        teammateViews.forEach { view ->
            val statusView = view.findViewById<TextView>(R.id.tvStatus)
            if (statusView.text != "已走完") {
                statusView.visibility = View.GONE
            }
        }
    }

    private fun updatePlayerHand() {
        playerHandContainer.removeAllViews()
        cardViewMap.clear()
        selectedCards.clear()

        val humanPlayer = gameEngine.humanPlayer ?: return

        humanPlayer.hand.forEach { card ->
            val cardView = createCardView(card)
            cardView.setOnClickListener {
                toggleCardSelection(card, cardView)
            }
            playerHandContainer.addView(cardView)
            cardViewMap[card] = cardView
        }

        updateButtonStates()
    }

    private fun createCardView(card: Card, isSmall: Boolean = false): View {
        val view = LayoutInflater.from(this).inflate(R.layout.view_card, null)

        val tvRank = view.findViewById<TextView>(R.id.tvRank)
        val tvSuit = view.findViewById<TextView>(R.id.tvSuit)

        tvRank.text = card.rank.displayName
        tvSuit.text = if (card.isJoker) "★" else card.suit.symbol

        val color = when {
            card.isJoker && card.rank == CardRank.BIG_JOKER -> R.color.card_red
            card.isJoker -> R.color.card_black
            card.suit == CardSuit.HEART || card.suit == CardSuit.DIAMOND -> R.color.card_red
            else -> R.color.card_black
        }

        tvRank.setTextColor(ContextCompat.getColor(this, color))
        tvSuit.setTextColor(ContextCompat.getColor(this, color))

        if (isSmall) {
            val params = LinearLayout.LayoutParams(48.dpToPx(), 68.dpToPx())
            params.marginEnd = (-8).dpToPx()
            view.layoutParams = params
        }

        return view
    }

    private fun toggleCardSelection(card: Card, cardView: View) {
        val container = cardView.findViewById<View>(R.id.cardContainer)

        if (selectedCards.contains(card)) {
            selectedCards.remove(card)
            container.setBackgroundResource(R.drawable.card_background)
            cardView.translationY = 0f
        } else {
            selectedCards.add(card)
            container.setBackgroundResource(R.drawable.card_selected)
            cardView.translationY = -16f
        }

        updateButtonStates()
    }

    private fun updateButtonStates() {
        val currentPlayer = gameEngine.getCurrentPlayer()
        val isHumanTurn = currentPlayer.type == PlayerType.HUMAN &&
                gameEngine.gamePhase == GamePhase.PLAYING

        btnPlay.isEnabled = isHumanTurn && selectedCards.isNotEmpty()
        btnPass.isEnabled = isHumanTurn && gameEngine.canHumanPass()
        btnHint.isEnabled = isHumanTurn
    }

    private fun showPlayedCards(cardGroup: CardGroup) {
        playedCardsContainer.removeAllViews()

        cardGroup.cards.forEach { card ->
            val cardView = createCardView(card, isSmall = true)
            playedCardsContainer.addView(cardView)
        }
    }

    private fun showMessage(message: String) {
        tvMessage.text = message
        tvMessage.visibility = View.VISIBLE

        handler.postDelayed({
            tvMessage.visibility = View.GONE
        }, MESSAGE_DISPLAY_MS)
    }

    private fun scheduleAITurn() {
        btnPlay.isEnabled = false
        btnPass.isEnabled = false
        btnHint.isEnabled = false

        handler.postDelayed({
            if (gameEngine.gamePhase == GamePhase.PLAYING) {
                gameEngine.executeAITurn()
            }
        }, AI_DELAY_MS)
    }

    private fun showHint() {
        val validPlays = gameEngine.getValidPlaysForHuman()
        if (validPlays.isEmpty()) {
            showMessage("没有能出的牌，请过牌")
            return
        }

        // Select the first valid play as hint
        selectedCards.clear()
        cardViewMap.values.forEach { view ->
            view.findViewById<View>(R.id.cardContainer)
                .setBackgroundResource(R.drawable.card_background)
            view.translationY = 0f
        }

        val hint = validPlays.first()
        hint.cards.forEach { card ->
            selectedCards.add(card)
            cardViewMap[card]?.let { view ->
                view.findViewById<View>(R.id.cardContainer)
                    .setBackgroundResource(R.drawable.card_selected)
                view.translationY = -16f
            }
        }

        showMessage("提示: ${hint.type.displayName}")
        updateButtonStates()
    }

    private fun updateScores() {
        tvTeamAScore.text = "${gameEngine.teamA.team.displayName}: ${gameEngine.teamA.finishedPlayersScore}分"
        tvTeamBScore.text = "${gameEngine.teamB.team.displayName}: ${gameEngine.teamB.finishedPlayersScore}分"
    }

    private fun showGameOver(result: GameResult) {
        gameOverOverlay.visibility = View.VISIBLE

        val humanTeam = gameEngine.humanPlayer?.team

        tvGameOverTitle.text = getString(R.string.game_over)

        tvGameOverResult.text = when {
            result.winner == null -> getString(R.string.draw)
            result.winner == humanTeam -> getString(R.string.you_win)
            else -> getString(R.string.you_lose)
        }

        tvFinalScore.text = getString(
            R.string.final_score,
            result.teamAScore,
            result.teamBScore
        )

        // Set result color
        tvGameOverResult.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    result.winner == null -> R.color.accent
                    result.winner == humanTeam -> R.color.primary_light
                    else -> R.color.score_negative
                }
            )
        )
    }

    private fun restartGame() {
        gameOverOverlay.visibility = View.GONE
        playedCardsContainer.removeAllViews()
        selectedCards.clear()
        cardViewMap.clear()

        // Reset all status views
        opponentViews.forEach { view ->
            view.findViewById<TextView>(R.id.tvStatus).visibility = View.GONE
        }
        teammateViews.forEach { view ->
            view.findViewById<TextView>(R.id.tvStatus).visibility = View.GONE
        }

        gameEngine.startGame()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
