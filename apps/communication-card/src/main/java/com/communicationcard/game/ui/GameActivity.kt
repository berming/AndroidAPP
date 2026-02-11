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
    private lateinit var btnHistory: Button
    private lateinit var gameOverOverlay: FrameLayout
    private lateinit var tvGameOverTitle: TextView
    private lateinit var tvGameOverResult: TextView
    private lateinit var tvFinalScore: TextView
    private lateinit var btnPlayAgain: Button
    private lateinit var btnBackMenu: Button
    private lateinit var btnShowHistory: Button
    private lateinit var tvPlayerCardCount: TextView
    private lateinit var tvPlayerScore: TextView

    // History overlay
    private lateinit var historyOverlay: FrameLayout
    private lateinit var tvHistoryContent: TextView
    private lateinit var btnCloseHistory: Button

    // Current round display
    private lateinit var tvCurrentLeader: TextView
    private lateinit var currentWinningCards: LinearLayout
    private lateinit var tvRoundScore: TextView

    // Hand cards area for click to deselect
    private lateinit var handCardsArea: LinearLayout

    // Player views map: playerId -> View
    private val playerViews = mutableMapOf<Int, View>()

    // Track played cards in current round for each player
    private val currentRoundPlayedCards = mutableMapOf<Int, CardGroup?>()

    // Selected cards for playing
    private val selectedCards = mutableListOf<Card>()
    private val cardViewMap = mutableMapOf<Card, View>()

    // Game history
    private val gameHistory = mutableListOf<String>()
    private var roundNumber = 0

    // Hint toggle state: tracks if hint cards are currently shown
    private var isHintShowing = false
    private var lastHintCards: List<Card> = emptyList()

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
        btnHistory = findViewById(R.id.btnHistory)
        gameOverOverlay = findViewById(R.id.gameOverOverlay)
        tvGameOverTitle = findViewById(R.id.tvGameOverTitle)
        tvGameOverResult = findViewById(R.id.tvGameOverResult)
        tvFinalScore = findViewById(R.id.tvFinalScore)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)
        btnBackMenu = findViewById(R.id.btnBackMenu)
        btnShowHistory = findViewById(R.id.btnShowHistory)
        tvPlayerCardCount = findViewById(R.id.tvPlayerCardCount)
        tvPlayerScore = findViewById(R.id.tvPlayerScore)

        // History overlay
        historyOverlay = findViewById(R.id.historyOverlay)
        tvHistoryContent = findViewById(R.id.tvHistoryContent)
        btnCloseHistory = findViewById(R.id.btnCloseHistory)

        // Current round display
        tvCurrentLeader = findViewById(R.id.tvCurrentLeader)
        currentWinningCards = findViewById(R.id.currentWinningCards)
        tvRoundScore = findViewById(R.id.tvRoundScore)

        // Hand cards area for click to deselect
        handCardsArea = findViewById(R.id.handCardsArea)

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

        btnHistory.setOnClickListener {
            showHistory()
        }

        btnShowHistory.setOnClickListener {
            showHistory()
        }

        btnCloseHistory.setOnClickListener {
            historyOverlay.visibility = View.GONE
        }

        btnPlayAgain.setOnClickListener {
            restartGame()
        }

        btnBackMenu.setOnClickListener {
            finish()
        }

        // Click on empty space in hand area to deselect all cards
        handCardsArea.setOnClickListener {
            deselectAllCards()
        }
    }

    private fun deselectAllCards() {
        if (selectedCards.isNotEmpty()) {
            selectedCards.clear()
            isHintShowing = false
            cardViewMap.values.forEach { view ->
                view.findViewById<View>(R.id.cardContainer)
                    .setBackgroundResource(R.drawable.card_background_large)
                view.translationY = 0f
            }
            updateButtonStates()
        }
    }

    private fun handleGameEvent(event: GameEvent) {
        when (event) {
            is GameEvent.CardsDealt -> {
                currentRoundPlayedCards.clear()
                gameHistory.clear()
                roundNumber = 1
                gameHistory.add("=== 游戏开始 ===")
                gameHistory.add("第${roundNumber}轮:")
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
                // Record history
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  [$teamName]${event.player.name} 出: ${event.cardGroup}")

                // Track played cards for this player in current round
                currentRoundPlayedCards[event.player.id] = event.cardGroup
                showPlayerPlayedCards(event.player, event.cardGroup)
                updatePlayerView(event.player)
                updateCurrentRoundDisplay()
                updateScores() // Update scores after each play
                if (event.player.type == PlayerType.HUMAN) {
                    updatePlayerHand()
                }
            }

            is GameEvent.PlayerPassed -> {
                // Record history
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  [$teamName]${event.player.name} 过牌")

                currentRoundPlayedCards[event.player.id] = null // Mark as passed
                showPlayerPassedStatus(event.player)
            }

            is GameEvent.RoundWon -> {
                // Record history
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  → [$teamName]${event.player.name} 赢得本轮, +${event.score}分")
                roundNumber++
                gameHistory.add("第${roundNumber}轮:")

                showMessage("${event.player.name} 赢得此轮，获得 ${event.score} 分")
                // Clear played cards for new round (but keep current round display)
                currentRoundPlayedCards.clear()
                clearAllPlayedCards()
                // Note: Don't clear currentRoundDisplay here - it will be updated when next player plays
            }

            is GameEvent.PlayerFinished -> {
                // Record history
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  ★ [$teamName]${event.player.name} 走完（第${event.order}个）")

                showMessage("${event.player.name} 已走完（第${event.order}个）")
                updatePlayerView(event.player)
            }

            is GameEvent.ScoreUpdate -> {
                updateScores()
            }

            is GameEvent.GameEnded -> {
                // Record final score calculation
                recordFinalScore(event.result)
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

        // Check if it's a bomb (4+ same rank cards) for compact layout
        val isBomb = cardGroup.type == CardGroupType.BOMB
        val cardCount = cardGroup.cards.size

        cardGroup.cards.forEachIndexed { index, card ->
            // For bomb: all cards except last get 20% overlap (negative marginEnd)
            val useBombOverlap = isBomb && index < cardCount - 1
            val cardView = createMiniCardView(card, useBombOverlap)
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
            // Set text color based on team
            val teamColor = if (winningPlayer?.team == Team.TEAM_A) R.color.team_a else R.color.team_b
            tvCurrentLeader.setTextColor(ContextCompat.getColor(this, teamColor))

            currentWinningCards.removeAllViews()
            val isBomb = winningPlay.second.type == CardGroupType.BOMB
            val cardCount = winningPlay.second.cards.size
            winningPlay.second.cards.forEachIndexed { index, card ->
                // For bomb: all cards except last get 20% overlap
                val useBombOverlap = isBomb && index < cardCount - 1
                val cardView = createMiniCardView(card, useBombOverlap)
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

    private fun createMiniCardView(card: Card, bombOverlap: Boolean = false): View {
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

        val cardWidth = 28.dpToPx()
        val params = LinearLayout.LayoutParams(cardWidth, 40.dpToPx())
        // For bomb cards, use 20% overlap - negative marginEnd makes next card overlap this one's right side
        // This keeps the left side (rank/suit info) visible
        params.marginEnd = if (bombOverlap) (-cardWidth * 0.2).toInt() else (-6).dpToPx()
        view.layoutParams = params

        return view
    }

    private fun updatePlayerHand() {
        playerHandRow1.removeAllViews()
        playerHandRow2.removeAllViews()
        cardViewMap.clear()
        selectedCards.clear()

        // Reset hint state when hand updates
        isHintShowing = false
        lastHintCards = emptyList()

        val humanPlayer = gameEngine.humanPlayer ?: return
        val cards = humanPlayer.hand

        // Card size for two-row display
        val cardWidth = 48.dpToPx()
        val cardHeight = 68.dpToPx()
        val cardMargin = 2.dpToPx()

        // Group cards by rank
        val cardsByRank = cards.groupBy { it.rank }

        // Separate bombs (4+) and non-bombs
        // Bombs: by count desc, then rank desc
        // Non-bombs: by rank desc only
        val bombGroups = cardsByRank.filter { it.value.size >= 4 }
            .toList()
            .sortedWith(compareByDescending<Pair<CardRank, List<Card>>> { it.second.size }
                .thenByDescending { it.first.value })
            .map { it.second }

        val nonBombGroups = cardsByRank.filter { it.value.size < 4 }
            .toList()
            .sortedByDescending { it.first.value }
            .map { it.second }

        // Combine: bombs first, then non-bombs
        val sortedGroups = bombGroups + nonBombGroups

        // Flatten all cards in order
        val allCards = sortedGroups.flatten()

        // Fill row 1 first, then row 2
        val targetPerRow = (allCards.size + 1) / 2
        val row1Cards = mutableListOf<Card>()
        val row2Cards = mutableListOf<Card>()

        // Track which cards belong to same rank groups for overlap
        var currentIdx = 0
        for (group in sortedGroups) {
            for (card in group) {
                if (row1Cards.size < targetPerRow) {
                    row1Cards.add(card)
                } else {
                    row2Cards.add(card)
                }
            }
        }

        // Add cards to rows with overlap for same rank cards
        addCardsToRowWithOverlap(row1Cards, playerHandRow1, cardWidth, cardHeight, cardMargin)
        addCardsToRowWithOverlap(row2Cards, playerHandRow2, cardWidth, cardHeight, cardMargin)

        updateButtonStates()
    }

    private fun addCardsToRowWithOverlap(
        cards: List<Card>,
        row: LinearLayout,
        cardWidth: Int,
        cardHeight: Int,
        cardMargin: Int
    ) {
        var i = 0
        while (i < cards.size) {
            val card = cards[i]
            val currentRank = card.rank

            // Count consecutive same-rank cards in this row
            var sameRankCount = 1
            while (i + sameRankCount < cards.size && cards[i + sameRankCount].rank == currentRank) {
                sameRankCount++
            }

            // Add all same-rank cards with overlap
            for (j in 0 until sameRankCount) {
                val currentCard = cards[i + j]
                // Apply 30% overlap for 2+ same rank cards (except last in group)
                val useOverlap = sameRankCount >= 2 && j < sameRankCount - 1
                val margin = if (useOverlap) (-cardWidth * 0.3).toInt() else cardMargin

                val cardView = createCardView(currentCard, cardWidth, cardHeight, margin)
                cardView.setOnClickListener {
                    toggleCardSelection(currentCard, cardView)
                }
                row.addView(cardView)
                cardViewMap[currentCard] = cardView
            }

            i += sameRankCount
        }
    }

    private fun createCardView(card: Card, width: Int, height: Int, marginEnd: Int): View {
        val view = LayoutInflater.from(this).inflate(R.layout.view_card, null)

        val tvRank = view.findViewById<TextView>(R.id.tvRank)
        val tvSuit = view.findViewById<TextView>(R.id.tvSuit)
        val cardContainer = view.findViewById<View>(R.id.cardContainer)

        // Use large rounded corner background for hand cards (10dp radius)
        cardContainer.setBackgroundResource(R.drawable.card_background_large)

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
        // Reset hint state when user manually selects/deselects
        isHintShowing = false

        val container = cardView.findViewById<View>(R.id.cardContainer)

        if (selectedCards.contains(card)) {
            selectedCards.remove(card)
            container.setBackgroundResource(R.drawable.card_background_large)
            cardView.translationY = 0f
        } else {
            selectedCards.add(card)
            container.setBackgroundResource(R.drawable.card_selected_large)
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
        // Toggle: if hint is showing, deselect and return
        if (isHintShowing && selectedCards.isNotEmpty() &&
            lastHintCards.isNotEmpty() && selectedCards.toSet() == lastHintCards.toSet()) {
            deselectAllCards()
            isHintShowing = false
            lastHintCards = emptyList()
            return
        }

        val validPlays = gameEngine.getValidPlaysForHuman()
        if (validPlays.isEmpty()) {
            showMessage("没有能出的牌，请过牌")
            return
        }

        // 获取手牌中的炸弹点数（这些点数的牌不应该拆开）
        val humanHand = gameEngine.humanPlayer?.hand ?: emptyList()
        val bombRanks = humanHand.groupBy { it.rank }
            .filter { it.value.size >= 4 }
            .keys

        // 分类：炸弹 vs 非炸弹
        val nonBombs = validPlays.filter { it.type != CardGroupType.BOMB }
        val bombs = validPlays.filter { it.type == CardGroupType.BOMB }

        // 过滤掉会拆炸弹的出牌选择
        val safeNonBombs = nonBombs.filter { cardGroup ->
            // 如果这个牌型的点数是炸弹点数，且不是使用全部（会拆炸弹），则过滤掉
            val rank = cardGroup.primaryRank
            if (bombRanks.contains(rank)) {
                // 检查是否使用了该点数的所有牌
                val bombSize = humanHand.count { it.rank == rank }
                cardGroup.cards.count { it.rank == rank } >= bombSize
            } else {
                true
            }
        }

        // 判断是自由出牌还是压牌
        val isFirstPlay = gameEngine.getLastPlay() == null

        val hint = if (isFirstPlay) {
            // 自由出牌策略：优先出小牌，保留大牌和炸弹
            findBestFreePlay(safeNonBombs, bombs)
        } else {
            // 压牌策略：用刚好能压住的最小牌
            findBestBeatPlay(safeNonBombs, bombs)
        }

        // Clear previous selection
        selectedCards.clear()
        cardViewMap.values.forEach { view ->
            view.findViewById<View>(R.id.cardContainer)
                .setBackgroundResource(R.drawable.card_background_large)
            view.translationY = 0f
        }

        // Select hint cards
        hint.cards.forEach { card ->
            selectedCards.add(card)
            cardViewMap[card]?.let { view ->
                view.findViewById<View>(R.id.cardContainer)
                    .setBackgroundResource(R.drawable.card_selected_large)
                view.translationY = -16f
            }
        }

        // Track hint state for toggle
        isHintShowing = true
        lastHintCards = hint.cards.toList()

        showMessage("提示: ${hint.type.displayName}")
        updateButtonStates()
    }

    /**
     * 自由出牌策略：优先出小牌，保留大牌
     * 优先级：小单张 > 小对子 > 小三张 > 顺子 > 炸弹
     * 避免出2、A、王等大牌（value >= 12）
     */
    private fun findBestFreePlay(nonBombs: List<CardGroup>, bombs: List<CardGroup>): CardGroup {
        // 按牌型分类
        val singles = nonBombs.filter { it.type == CardGroupType.SINGLE }
        val pairs = nonBombs.filter { it.type == CardGroupType.PAIR }
        val triples = nonBombs.filter { it.type == CardGroupType.TRIPLE }
        val straights = nonBombs.filter { it.type == CardGroupType.STRAIGHT }

        // 定义"小牌"阈值：value < 12 (即小于A的牌)
        val smallThreshold = 12

        // 优先出小单张
        val smallSingles = singles.filter { it.primaryRank.value < smallThreshold }
        if (smallSingles.isNotEmpty()) {
            return smallSingles.minByOrNull { it.primaryRank.value }!!
        }

        // 其次出小对子
        val smallPairs = pairs.filter { it.primaryRank.value < smallThreshold }
        if (smallPairs.isNotEmpty()) {
            return smallPairs.minByOrNull { it.primaryRank.value }!!
        }

        // 然后出小三张
        val smallTriples = triples.filter { it.primaryRank.value < smallThreshold }
        if (smallTriples.isNotEmpty()) {
            return smallTriples.minByOrNull { it.primaryRank.value }!!
        }

        // 顺子可以出（一次走5张以上，有优势）
        if (straights.isNotEmpty()) {
            return straights.minByOrNull { it.primaryRank.value }!!
        }

        // 如果没有小牌，出最小的非炸弹牌（可能是A、2、王）
        if (nonBombs.isNotEmpty()) {
            return nonBombs.minWithOrNull(compareBy({ it.size }, { it.primaryRank.value }))!!
        }

        // 最后才出炸弹
        return bombs.minWithOrNull(compareBy({ it.size }, { it.primaryRank.value }))!!
    }

    /**
     * 压牌策略：用刚好能压住的最小牌
     * 尽量保留大牌（2、王），除非没有其他选择
     */
    private fun findBestBeatPlay(nonBombs: List<CardGroup>, bombs: List<CardGroup>): CardGroup {
        if (nonBombs.isNotEmpty()) {
            // 定义"大牌"阈值：value >= 13 (2和王)
            val bigThreshold = 13

            // 优先用普通牌压（非2、非王）
            val regularPlays = nonBombs.filter { it.primaryRank.value < bigThreshold }
            if (regularPlays.isNotEmpty()) {
                return regularPlays.minByOrNull { it.primaryRank.value }!!
            }

            // 没有普通牌，只能用大牌（2或王）
            return nonBombs.minByOrNull { it.primaryRank.value }!!
        }

        // 没有非炸弹牌能压，用最小的炸弹
        return bombs.minWithOrNull(compareBy({ it.size }, { it.primaryRank.value }))!!
    }

    private fun updateScores() {
        // Show sum of all team members' collected scores (not just finished players)
        tvTeamAScore.text = "${gameEngine.teamA.team.displayName}:${gameEngine.teamA.totalCollectedScore}分"
        tvTeamBScore.text = "${gameEngine.teamB.team.displayName}:${gameEngine.teamB.totalCollectedScore}分"

        // Update human player score display
        val humanPlayer = gameEngine.humanPlayer
        if (humanPlayer != null) {
            tvPlayerScore.text = getString(R.string.collected_score, humanPlayer.collectedScore)
        }
    }

    private fun showHistory() {
        tvHistoryContent.text = gameHistory.joinToString("\n")
        historyOverlay.visibility = View.VISIBLE
    }

    private fun recordFinalScore(result: GameResult) {
        gameHistory.add("")
        gameHistory.add("=== 游戏结束 ===")
        gameHistory.add("触发方式: ${if (result.trigger.name == "TEAM_ALL_FINISHED") "全队走完" else "得分达标"}")
        gameHistory.add("")
        gameHistory.add("--- 结算计算 ---")

        // Show each player's collected score
        gameHistory.add("红队玩家已收分:")
        gameEngine.teamA.players.forEach { player ->
            val status = if (player.hasFinished) "已走完" else "未走完(手牌${player.handScore}分)"
            gameHistory.add("  ${player.name}: ${player.collectedScore}分 $status")
        }

        gameHistory.add("蓝队玩家已收分:")
        gameEngine.teamB.players.forEach { player ->
            val status = if (player.hasFinished) "已走完" else "未走完(手牌${player.handScore}分)"
            gameHistory.add("  ${player.name}: ${player.collectedScore}分 $status")
        }

        gameHistory.add("")
        gameHistory.add("--- 最终得分 ---")
        gameHistory.add("红队: ${result.teamAScore}分")
        gameHistory.add("蓝队: ${result.teamBScore}分")
        gameHistory.add("")

        val winnerText = when {
            result.winner == null -> "平局"
            result.winner == Team.TEAM_A -> "红队获胜!"
            else -> "蓝队获胜!"
        }
        gameHistory.add("结果: $winnerText")
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
        historyOverlay.visibility = View.GONE
        currentRoundPlayedCards.clear()
        selectedCards.clear()
        cardViewMap.clear()
        gameHistory.clear()
        roundNumber = 0

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
