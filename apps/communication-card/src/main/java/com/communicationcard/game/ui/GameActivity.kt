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
    private lateinit var playerHandRow1: LinearLayout
    private lateinit var playerHandRow2: LinearLayout
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
    private lateinit var tvPlayerCardCount: TextView
    private lateinit var tvPlayerScore: TextView

    // Current round display
    private lateinit var tvCurrentLeader: TextView
    private lateinit var currentWinningCards: LinearLayout
    private lateinit var tvRoundScore: TextView

    // Player views map: playerId -> View
    private val playerViews = mutableMapOf<Int, View>()

    // Track played cards in current round for each player
    private val currentRoundPlayedCards = mutableMapOf<Int, CardGroup?>()

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
        playerHandRow1 = findViewById(R.id.playerHandRow1)
        playerHandRow2 = findViewById(R.id.playerHandRow2)
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
        tvPlayerCardCount = findViewById(R.id.tvPlayerCardCount)
        tvPlayerScore = findViewById(R.id.tvPlayerScore)

        // Current round display
        tvCurrentLeader = findViewById(R.id.tvCurrentLeader)
        currentWinningCards = findViewById(R.id.currentWinningCards)
        tvRoundScore = findViewById(R.id.tvRoundScore)

        // Initialize player views - map player ID to view
        // Player IDs: 0=Human, 1=玩家2, 2=玩家3, 3=玩家4, 4=玩家5, 5=玩家6
        // Layout order: player2, player3, player4, player5, player6
        playerViews[1] = findViewById(R.id.player2)  // 玩家2, id=1
        playerViews[2] = findViewById(R.id.player3)  // 玩家3, id=2
        playerViews[3] = findViewById(R.id.player4)  // 玩家4, id=3
        playerViews[4] = findViewById(R.id.player5)  // 玩家5, id=4
        playerViews[5] = findViewById(R.id.player6)  // 玩家6, id=5
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
                currentRoundPlayedCards.clear()
                updateAllPlayerViews()
                updatePlayerHand()
                updateScores()
                updateCurrentRoundDisplay()
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
                // Track played cards for this player in current round
                currentRoundPlayedCards[event.player.id] = event.cardGroup
                showPlayerPlayedCards(event.player, event.cardGroup)
                updatePlayerView(event.player)
                updateCurrentRoundDisplay()
                if (event.player.type == PlayerType.HUMAN) {
                    updatePlayerHand()
                }
            }

            is GameEvent.PlayerPassed -> {
                currentRoundPlayedCards[event.player.id] = null // Mark as passed
                showPlayerPassedStatus(event.player)
            }

            is GameEvent.RoundWon -> {
                showMessage("${event.player.name} 赢得此轮，获得 ${event.score} 分")
                handler.postDelayed({
                    // Clear all played cards for new round
                    currentRoundPlayedCards.clear()
                    clearAllPlayedCards()
                    updateCurrentRoundDisplay()
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
                // AI communication now shown via played cards
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
        playerViews.values.forEach { it.alpha = 0.7f }

        // Highlight current player
        if (player.type == PlayerType.HUMAN) {
            // Human player area is always highlighted
            return
        }

        playerViews[player.id]?.alpha = 1.0f
    }

    private fun updateAllPlayerViews() {
        // Update all AI player views (players 2-6)
        gameEngine.players.filter { it.type == PlayerType.AI }.forEach { player ->
            updatePlayerView(player)
        }

        // Update human player info
        val humanPlayer = gameEngine.humanPlayer
        if (humanPlayer != null) {
            tvPlayerCardCount.text = getString(R.string.cards_count, humanPlayer.handSize)
            tvPlayerScore.text = getString(R.string.collected_score, humanPlayer.collectedScore)
        }
    }

    private fun updatePlayerView(player: Player) {
        val view = playerViews[player.id] ?: return

        view.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvPlayerName).text = player.name
        view.findViewById<TextView>(R.id.tvCardCount).text = "${player.handSize}张"
        view.findViewById<TextView>(R.id.tvScore).text = "已收:${player.collectedScore}分"

        val indicator = view.findViewById<View>(R.id.teamIndicator)
        indicator.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (player.team == Team.TEAM_A) R.color.team_a else R.color.team_b
            )
        )

        val statusView = view.findViewById<TextView>(R.id.tvStatus)
        if (player.hasFinished) {
            statusView.visibility = View.VISIBLE
            statusView.text = "已走完"
            // Hide played cards container
            view.findViewById<View>(R.id.playedCardsContainer).visibility = View.GONE
        }
    }

    private fun showPlayerPlayedCards(player: Player, cardGroup: CardGroup) {
        if (player.type == PlayerType.HUMAN) {
            // Human player's cards shown in center area
            return
        }

        val view = playerViews[player.id] ?: return
        val container = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
        val statusView = view.findViewById<TextView>(R.id.tvStatus)

        container.removeAllViews()
        container.visibility = View.VISIBLE
        statusView.visibility = View.GONE

        cardGroup.cards.forEach { card ->
            val cardView = createMiniCardView(card)
            container.addView(cardView)
        }
    }

    private fun showPlayerPassedStatus(player: Player) {
        if (player.type == PlayerType.HUMAN) {
            return
        }

        val view = playerViews[player.id] ?: return
        val container = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
        val statusView = view.findViewById<TextView>(R.id.tvStatus)

        container.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.status_pass)
    }

    private fun clearAllPlayedCards() {
        playerViews.values.forEach { view ->
            val container = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
            val statusView = view.findViewById<TextView>(R.id.tvStatus)

            container.removeAllViews()
            container.visibility = View.VISIBLE

            if (statusView.text != "已走完") {
                statusView.visibility = View.GONE
            }
        }
    }

    private fun updateCurrentRoundDisplay() {
        // Find current winning play
        val winningPlay = gameEngine.getCurrentWinningPlay()
        val roundScore = gameEngine.getCurrentRoundScore()

        if (winningPlay != null) {
            val winningPlayer = gameEngine.players.find { it.id == winningPlay.first }
            tvCurrentLeader.text = "${winningPlayer?.name}:"
            tvCurrentLeader.visibility = View.VISIBLE

            currentWinningCards.removeAllViews()
            winningPlay.second.cards.forEach { card ->
                val cardView = createMiniCardView(card)
                currentWinningCards.addView(cardView)
            }

            tvRoundScore.text = getString(R.string.round_score, roundScore)
            tvRoundScore.visibility = View.VISIBLE
        } else {
            tvCurrentLeader.visibility = View.GONE
            currentWinningCards.removeAllViews()
            tvRoundScore.text = getString(R.string.round_score, 0)
        }
    }

    private fun createMiniCardView(card: Card): View {
        val view = LayoutInflater.from(this).inflate(R.layout.view_card, null)

        val tvRank = view.findViewById<TextView>(R.id.tvRank)
        val tvSuit = view.findViewById<TextView>(R.id.tvSuit)

        tvRank.text = card.rank.displayName
        tvRank.textSize = 10f
        tvSuit.text = if (card.isJoker) "★" else card.suit.symbol
        tvSuit.textSize = 8f

        val color = when {
            card.isJoker && card.rank == CardRank.BIG_JOKER -> R.color.card_red
            card.isJoker -> R.color.card_black
            card.suit == CardSuit.HEART || card.suit == CardSuit.DIAMOND -> R.color.card_red
            else -> R.color.card_black
        }

        tvRank.setTextColor(ContextCompat.getColor(this, color))
        tvSuit.setTextColor(ContextCompat.getColor(this, color))

        val params = LinearLayout.LayoutParams(28.dpToPx(), 40.dpToPx())
        params.marginEnd = (-6).dpToPx()
        view.layoutParams = params

        return view
    }

    private fun updatePlayerHand() {
        playerHandRow1.removeAllViews()
        playerHandRow2.removeAllViews()
        cardViewMap.clear()
        selectedCards.clear()

        val humanPlayer = gameEngine.humanPlayer ?: return
        val cards = humanPlayer.hand
        val cardCount = cards.size

        // Card size for two-row display (no overlap)
        val cardWidth = 48.dpToPx()
        val cardHeight = 68.dpToPx()
        val cardMargin = 2.dpToPx()

        // Split cards into two rows
        val halfCount = (cardCount + 1) / 2 // First row gets more if odd number
        val row1Cards = cards.take(halfCount)
        val row2Cards = cards.drop(halfCount)

        // Add cards to first row
        row1Cards.forEach { card ->
            val cardView = createCardView(card, cardWidth, cardHeight, cardMargin)
            cardView.setOnClickListener {
                toggleCardSelection(card, cardView)
            }
            playerHandRow1.addView(cardView)
            cardViewMap[card] = cardView
        }

        // Add cards to second row
        row2Cards.forEach { card ->
            val cardView = createCardView(card, cardWidth, cardHeight, cardMargin)
            cardView.setOnClickListener {
                toggleCardSelection(card, cardView)
            }
            playerHandRow2.addView(cardView)
            cardViewMap[card] = cardView
        }

        updateButtonStates()
    }

    private fun createCardView(card: Card, width: Int, height: Int, marginEnd: Int): View {
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

        val params = LinearLayout.LayoutParams(width, height)
        params.marginEnd = marginEnd
        view.layoutParams = params

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
        tvTeamAScore.text = "${gameEngine.teamA.team.displayName}:${gameEngine.teamA.finishedPlayersScore}分"
        tvTeamBScore.text = "${gameEngine.teamB.team.displayName}:${gameEngine.teamB.finishedPlayersScore}分"

        // Update human player score display
        val humanPlayer = gameEngine.humanPlayer
        if (humanPlayer != null) {
            tvPlayerScore.text = getString(R.string.collected_score, humanPlayer.collectedScore)
        }
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
        currentRoundPlayedCards.clear()
        selectedCards.clear()
        cardViewMap.clear()

        // Reset all player views
        playerViews.values.forEach { view ->
            view.findViewById<TextView>(R.id.tvStatus).visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.playedCardsContainer).removeAllViews()
        }

        // Reset current round display
        currentWinningCards.removeAllViews()
        tvCurrentLeader.visibility = View.GONE

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
