package com.communicationcard.game.ui.multiplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.communicationcard.game.R
import com.communicationcard.game.engine.*
import com.communicationcard.game.model.*
import com.communicationcard.game.network.*
import com.communicationcard.game.ui.SoundManager
import com.communicationcard.game.util.DebugLogManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 多人在线游戏界面
 *
 * 设计原则：重用单人游戏的 activity_game.xml 布局和 UI 逻辑，
 * 仅替换 GameEngine 为 MultiplayerGameEngine 进行网络同步。
 */
class OnlineGameActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnlineGameActivity"
        const val EXTRA_GAME_STATE = "game_state"
        const val EXTRA_LOCAL_SEAT_INDEX = "local_seat_index"
        private const val MESSAGE_DISPLAY_MS = 800L
    }

    private lateinit var networkManager: NetworkManager
    private lateinit var roomManager: RoomManager
    private lateinit var gameSyncManager: GameSyncManager
    private lateinit var multiplayerEngine: MultiplayerGameEngine
    private lateinit var soundManager: SoundManager

    private val handler = Handler(Looper.getMainLooper())

    // UI Elements - 复用单人游戏布局
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
    private lateinit var btnAutoPlay: Button
    private lateinit var gameOverOverlay: FrameLayout
    private lateinit var tvGameOverTitle: TextView
    private lateinit var tvGameOverResult: TextView
    private lateinit var tvFinalScore: TextView
    private lateinit var btnPlayAgain: Button
    private lateinit var btnBackMenu: Button
    private lateinit var btnShowHistory: Button
    private lateinit var tvPlayerName: TextView
    private lateinit var tvPlayerCardCount: TextView
    private lateinit var tvPlayerScore: TextView

    // History overlay (分页)
    private lateinit var historyOverlay: FrameLayout
    private lateinit var tvHistoryColumn1: TextView
    private lateinit var tvHistoryColumn2: TextView
    private lateinit var tvHistoryPageInfo: TextView
    private lateinit var btnCloseHistory: Button
    private lateinit var btnHistoryPrev: Button
    private lateinit var btnHistoryNext: Button
    private var historyCurrentPage = 0
    private val historyLinesPerColumn = 15

    // Current round display
    private lateinit var tvCurrentLeader: TextView
    private lateinit var currentWinningCards: LinearLayout
    private lateinit var tvRoundScore: TextView

    // Hand cards area
    private lateinit var handCardsArea: LinearLayout

    // Player views: viewSlotIndex -> View
    private val playerViewSlots = mutableMapOf<Int, View>()

    // Seat to view slot mapping
    private val seatToViewSlot = mutableMapOf<Int, Int>()

    // Track played cards in current round
    private val currentRoundPlayedCards = mutableMapOf<Int, CardGroup?>()

    // Selected cards
    private val selectedCards = mutableListOf<Card>()
    private val cardViewMap = mutableMapOf<Card, View>()

    // Game history
    private val gameHistory = mutableListOf<String>()
    private var roundNumber = 0

    // Hint state
    private var isHintShowing = false
    private var lastHintCards: List<Card> = emptyList()

    private var localSeatIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogManager.i(TAG, "=== OnlineGameActivity onCreate START ===")

        try {
            enableImmersiveMode()
            setContentView(R.layout.activity_game)

            soundManager = SoundManager(this)

            initManagers()
            initViews()
            initGameFromIntent()
            setupListeners()
            observeState()

            DebugLogManager.i(TAG, "=== OnlineGameActivity onCreate SUCCESS ===")
        } catch (e: Exception) {
            DebugLogManager.e(TAG, "OnlineGameActivity onCreate FAILED", e)
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
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

    private fun initManagers() {
        val nm = NetworkManagerHolder.instance
            ?: throw IllegalStateException("NetworkManager未初始化")
        val rm = RoomManagerHolder.instance
            ?: throw IllegalStateException("RoomManager未初始化")

        networkManager = nm
        roomManager = rm
        gameSyncManager = GameSyncManager(networkManager)
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
        btnAutoPlay = findViewById(R.id.btnAutoPlay)
        gameOverOverlay = findViewById(R.id.gameOverOverlay)
        tvGameOverTitle = findViewById(R.id.tvGameOverTitle)
        tvGameOverResult = findViewById(R.id.tvGameOverResult)
        tvFinalScore = findViewById(R.id.tvFinalScore)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)
        btnBackMenu = findViewById(R.id.btnBackMenu)
        btnShowHistory = findViewById(R.id.btnShowHistory)
        tvPlayerName = findViewById(R.id.tvPlayerName)
        tvPlayerCardCount = findViewById(R.id.tvPlayerCardCount)
        tvPlayerScore = findViewById(R.id.tvPlayerScore)

        // History overlay (分页)
        historyOverlay = findViewById(R.id.historyOverlay)
        tvHistoryColumn1 = findViewById(R.id.tvHistoryColumn1)
        tvHistoryColumn2 = findViewById(R.id.tvHistoryColumn2)
        tvHistoryPageInfo = findViewById(R.id.tvHistoryPageInfo)
        btnCloseHistory = findViewById(R.id.btnCloseHistory)
        btnHistoryPrev = findViewById(R.id.btnHistoryPrev)
        btnHistoryNext = findViewById(R.id.btnHistoryNext)

        // Current round display
        tvCurrentLeader = findViewById(R.id.tvCurrentLeader)
        currentWinningCards = findViewById(R.id.currentWinningCards)
        tvRoundScore = findViewById(R.id.tvRoundScore)

        // Hand cards area
        handCardsArea = findViewById(R.id.handCardsArea)

        // 5个玩家视图槽位 (对应布局中的 player2-6)
        playerViewSlots[0] = findViewById(R.id.player2)
        playerViewSlots[1] = findViewById(R.id.player3)
        playerViewSlots[2] = findViewById(R.id.player4)
        playerViewSlots[3] = findViewById(R.id.player5)
        playerViewSlots[4] = findViewById(R.id.player6)

        // 多人模式下隐藏"再来一局"按钮
        btnPlayAgain.visibility = View.GONE
        // 托管按钮改为"离开"
        btnAutoPlay.text = "离开"
    }

    private fun initGameFromIntent() {
        val stateJson = intent.getStringExtra(EXTRA_GAME_STATE)
            ?: throw IllegalStateException("游戏状态为空")
        localSeatIndex = intent.getIntExtra(EXTRA_LOCAL_SEAT_INDEX, -1)
        if (localSeatIndex < 0) throw IllegalStateException("无效座位号")

        val state = GameMessage.json.decodeFromString(
            SerializedGameState.serializer(), stateJson
        )

        gameSyncManager.setInitialState(state, localSeatIndex)
        multiplayerEngine = MultiplayerGameEngine(gameSyncManager, localSeatIndex)

        // 建立座位到视图槽的映射
        // localSeatIndex 对应底部手牌区，其他5个座位依次对应5个视图槽
        val totalPlayers = state.players.size
        for (slot in 0 until 5) {
            val seat = (localSeatIndex + 1 + slot) % totalPlayers
            seatToViewSlot[seat] = slot
        }

        multiplayerEngine.addEventListener { event ->
            runOnUiThread { handleGameEvent(event) }
        }

        // 设置本地玩家名称显示为 "你 (昵称)"
        val localPlayer = state.players.find { it.id == localSeatIndex }
        localPlayer?.let {
            tvPlayerName.text = "你 (${it.name})"
        }

        // 初始化UI
        updateAllPlayerViews()
        updatePlayerHand()
        updateScores()
        updateCurrentRoundDisplay()
        // 显示首位玩家的回合提示（避免 SharedFlow replay=0 错过初始 TurnStart）
        multiplayerEngine.getCurrentPlayer()?.let { updateTurnIndicator(it) }
        updateButtonStates()

        gameHistory.add("=== 联网游戏开始 ===")
        roundNumber = 1
        gameHistory.add("第${roundNumber}轮:")
    }

    private fun setupListeners() {
        btnPlay.setOnClickListener {
            if (selectedCards.isNotEmpty()) {
                if (multiplayerEngine.humanPlay(selectedCards.toList())) {
                    selectedCards.clear()
                    updateButtonStates()
                }
            }
        }

        btnPass.setOnClickListener {
            if (multiplayerEngine.humanPass()) {
                selectedCards.clear()
                updateButtonStates()
            }
        }

        btnHint.setOnClickListener { showHint() }
        btnHistory.setOnClickListener { showHistory() }
        btnShowHistory.setOnClickListener { showHistory() }
        btnCloseHistory.setOnClickListener { historyOverlay.visibility = View.GONE }
        btnHistoryPrev.setOnClickListener { showHistoryPage(historyCurrentPage - 1) }
        btnHistoryNext.setOnClickListener { showHistoryPage(historyCurrentPage + 1) }

        // 托管按钮改为离开
        btnAutoPlay.setOnClickListener { showLeaveConfirmDialog() }
        btnBackMenu.setOnClickListener { showLeaveConfirmDialog() }

        // 点击手牌区空白处取消选中
        handCardsArea.setOnClickListener { deselectAllCards() }
    }

    private fun observeState() {
        // 监听连接状态
        lifecycleScope.launch {
            networkManager.connectionState.collectLatest { state ->
                handleConnectionState(state)
            }
        }

        // 监听回合倒计时
        lifecycleScope.launch {
            gameSyncManager.turnTimeRemaining.collectLatest { seconds ->
                updateTurnTimer(seconds)
            }
        }

        // 监听操作结果
        lifecycleScope.launch {
            gameSyncManager.actionResults.collectLatest { result ->
                if (!result.success) {
                    showMessage(result.error ?: "操作失败")
                }
            }
        }
    }

    private fun handleConnectionState(state: ConnectionState) {
        when (state) {
            ConnectionState.Disconnected,
            is ConnectionState.Reconnecting -> {
                tvGameStatus.text = "重连中..."
                disableInput()
            }
            ConnectionState.Connected -> {
                enableInput()
            }
            is ConnectionState.Error -> {
                Toast.makeText(this, "连接错误: ${state.reason}", Toast.LENGTH_SHORT).show()
            }
            else -> {}
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
                updateButtonStates()
            }

            is GameEvent.CardsPlayed -> {
                // 记录历史
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  [$teamName]${event.player.name} 出: ${event.cardGroup}")

                // 音效
                if (event.cardGroup.type == CardGroupType.BOMB) {
                    soundManager.playBombSound()
                } else {
                    soundManager.playCardSound()
                }

                currentRoundPlayedCards[event.player.id] = event.cardGroup
                showPlayerPlayedCardsWithAnimation(event.player, event.cardGroup)
                updatePlayerView(event.player)
                updateCurrentRoundDisplay()
                updateScores()

                if (event.player.id == localSeatIndex) {
                    updatePlayerHand()
                }
            }

            is GameEvent.PlayerPassed -> {
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  [$teamName]${event.player.name} 过牌")
                soundManager.playPassSound()
                currentRoundPlayedCards[event.player.id] = null
                showPlayerPassedStatus(event.player)
            }

            is GameEvent.RoundWon -> {
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  → [$teamName]${event.player.name} 赢得本轮, +${event.score}分")
                roundNumber++
                gameHistory.add("第${roundNumber}轮:")
                soundManager.playWinRoundSound()
                showMessage("${event.player.name} 赢得此轮，获得 ${event.score} 分")
                currentRoundPlayedCards.clear()
                clearAllPlayedCards()
            }

            is GameEvent.PlayerFinished -> {
                val teamName = if (event.player.team == Team.TEAM_A) "红" else "蓝"
                gameHistory.add("  ★ [$teamName]${event.player.name} 走完（第${event.order}个）")
                showMessage("${event.player.name} 已走完（第${event.order}个）")
                updatePlayerView(event.player)
            }

            is GameEvent.ScoreUpdate -> updateScores()

            is GameEvent.GameEnded -> {
                val myTeam = multiplayerEngine.players.find { it.id == localSeatIndex }?.team
                if (event.result.winner == myTeam) {
                    soundManager.playGameWinSound()
                } else if (event.result.winner != null) {
                    soundManager.playGameLoseSound()
                }
                showGameOver(event.result)
            }

            is GameEvent.StateRefresh -> {
                // 状态刷新：默认情况下不重建手牌（避免清空选中）
                // 但若手牌内容变了（重连后、AI代打、超时托管等），必须重建以保持一致
                val currentHand = multiplayerEngine.getMyHand()
                val displayedHand = cardViewMap.keys
                val handChanged = currentHand.size != displayedHand.size ||
                    currentHand.toSet() != displayedHand
                if (handChanged) {
                    updatePlayerHand()
                }
                updateAllPlayerViews()
                updateScores()
                updateCurrentRoundDisplay()
                updateButtonStates()
                // 更新回合指示器
                multiplayerEngine.getCurrentPlayer()?.let { updateTurnIndicator(it) }
            }

            else -> {}
        }
    }

    // ========== UI更新方法 (复用单人游戏逻辑) ==========

    private fun updateTurnIndicator(player: Player) {
        tvGameStatus.text = if (player.id == localSeatIndex) {
            "轮到你出牌"
        } else {
            "等待 ${player.name} 出牌"
        }
        highlightCurrentPlayer(player)
    }

    private fun highlightCurrentPlayer(player: Player) {
        playerViewSlots.values.forEach { it.alpha = 0.7f }
        if (player.id != localSeatIndex) {
            val slot = seatToViewSlot[player.id]
            slot?.let { playerViewSlots[it]?.alpha = 1.0f }
        }
    }

    private fun updateTurnTimer(seconds: Int) {
        val currentPlayer = multiplayerEngine.getCurrentPlayer()
        if (currentPlayer?.id == localSeatIndex) {
            tvGameStatus.text = "轮到你出牌 (${seconds}s)"
        }
    }

    private fun updateAllPlayerViews() {
        multiplayerEngine.players.forEach { player ->
            if (player.id != localSeatIndex) {
                updatePlayerView(player)
            }
        }
        // 更新本地玩家信息
        val localPlayer = multiplayerEngine.players.find { it.id == localSeatIndex }
        localPlayer?.let {
            tvPlayerCardCount.text = getString(R.string.cards_count, it.handSize)
            tvPlayerScore.text = getString(R.string.collected_score, it.collectedScore)
        }
    }

    private fun updatePlayerView(player: Player) {
        val slot = seatToViewSlot[player.id] ?: return
        val view = playerViewSlots[slot] ?: return

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
            view.findViewById<View>(R.id.playedCardsContainer).visibility = View.GONE
        }
    }

    private fun showPlayerPlayedCardsWithAnimation(player: Player, cardGroup: CardGroup) {
        if (player.id == localSeatIndex) {
            animateCurrentRoundDisplay()
            return
        }

        val slot = seatToViewSlot[player.id] ?: return
        val view = playerViewSlots[slot] ?: return
        val container = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
        val statusView = view.findViewById<TextView>(R.id.tvStatus)

        container.removeAllViews()
        container.visibility = View.VISIBLE
        statusView.visibility = View.GONE

        val isBomb = cardGroup.type == CardGroupType.BOMB
        val cardCount = cardGroup.cards.size

        cardGroup.cards.forEachIndexed { index, card ->
            val useBombOverlap = isBomb && index < cardCount - 1
            val cardView = createMiniCardView(card, useBombOverlap)
            container.addView(cardView)

            val animation = if (isBomb) createBombAnimation(index) else createCardPlayAnimation(index)
            cardView.startAnimation(animation)
        }
    }

    private fun showPlayerPassedStatus(player: Player) {
        if (player.id == localSeatIndex) return

        val slot = seatToViewSlot[player.id] ?: return
        val view = playerViewSlots[slot] ?: return
        val container = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
        val statusView = view.findViewById<TextView>(R.id.tvStatus)

        container.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.status_pass)
    }

    private fun clearAllPlayedCards() {
        playerViewSlots.values.forEach { view ->
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
        val winningPlay = multiplayerEngine.getLastPlay()
        val roundScore = multiplayerEngine.currentRoundScore

        if (winningPlay != null) {
            val state = gameSyncManager.gameState.value
            val leaderId = state?.lastPlayerId
            val winningPlayer = multiplayerEngine.players.find { it.id == leaderId }

            tvCurrentLeader.text = "${winningPlayer?.name}:"
            tvCurrentLeader.visibility = View.VISIBLE
            val teamColor = if (winningPlayer?.team == Team.TEAM_A) R.color.team_a else R.color.team_b
            tvCurrentLeader.setTextColor(ContextCompat.getColor(this, teamColor))

            currentWinningCards.removeAllViews()
            val isBomb = winningPlay.type == CardGroupType.BOMB
            val cardCount = winningPlay.cards.size
            winningPlay.cards.forEachIndexed { index, card ->
                val useBombOverlap = isBomb && index < cardCount - 1
                val cardView = createMiniCardView(card, useBombOverlap, large = true)
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

    private fun animateCurrentRoundDisplay() {
        currentWinningCards.startAnimation(createCardPlayAnimation(0))
    }

    private fun createMiniCardView(card: Card, bombOverlap: Boolean = false, large: Boolean = false): View {
        val view = LayoutInflater.from(this).inflate(R.layout.view_card, null)

        val tvRank = view.findViewById<TextView>(R.id.tvRank)
        val tvSuit = view.findViewById<TextView>(R.id.tvSuit)

        tvRank.text = card.rank.displayName
        tvSuit.text = if (card.isJoker) "★" else card.suit.symbol

        // 大尺寸用于中央显示，小尺寸用于对手区域
        if (large) {
            tvRank.textSize = 16f
            tvSuit.textSize = 12f
        } else {
            tvRank.textSize = 12f
            tvSuit.textSize = 9f
        }

        val color = when {
            card.isJoker && card.rank == CardRank.BIG_JOKER -> R.color.card_red
            card.isJoker -> R.color.card_black
            card.suit == CardSuit.HEART || card.suit == CardSuit.DIAMOND -> R.color.card_red
            else -> R.color.card_black
        }

        tvRank.setTextColor(ContextCompat.getColor(this, color))
        tvSuit.setTextColor(ContextCompat.getColor(this, color))

        val cardWidth = if (large) 38.dpToPx() else 32.dpToPx()
        val cardHeight = if (large) 54.dpToPx() else 46.dpToPx()
        val params = LinearLayout.LayoutParams(cardWidth, cardHeight)
        params.marginEnd = if (bombOverlap) (-cardWidth * 0.25).toInt() else (-4).dpToPx()
        view.layoutParams = params

        return view
    }

    private fun createCardPlayAnimation(index: Int): Animation {
        val animSet = AnimationSet(true).apply {
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scale = ScaleAnimation(
            0.5f, 1f, 0.5f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 150 }

        val alpha = AlphaAnimation(0f, 1f).apply { duration = 150 }

        animSet.addAnimation(scale)
        animSet.addAnimation(alpha)
        animSet.startOffset = (index * 30).toLong()

        return animSet
    }

    private fun createBombAnimation(index: Int): Animation {
        val animSet = AnimationSet(true).apply {
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scale = ScaleAnimation(
            1.5f, 1f, 1.5f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 200 }

        val alpha = AlphaAnimation(0.3f, 1f).apply { duration = 200 }

        animSet.addAnimation(scale)
        animSet.addAnimation(alpha)
        animSet.startOffset = (index * 50).toLong()

        return animSet
    }

    // ========== 手牌显示 (复用单人逻辑) ==========

    private fun updatePlayerHand() {
        playerHandRow1.removeAllViews()
        playerHandRow2.removeAllViews()
        cardViewMap.clear()
        selectedCards.clear()
        isHintShowing = false
        lastHintCards = emptyList()

        val cards = multiplayerEngine.getMyHand()
        val cardWidth = 48.dpToPx()
        val cardHeight = 68.dpToPx()
        val cardMargin = 2.dpToPx()

        // 按点数分组，炸弹在前
        val cardsByRank = cards.groupBy { it.rank }
        val bombGroups = cardsByRank.filter { it.value.size >= 4 }
            .toList()
            .sortedWith(compareByDescending<Pair<CardRank, List<Card>>> { it.second.size }
                .thenByDescending { it.first.value })
            .map { it.second }

        val nonBombGroups = cardsByRank.filter { it.value.size < 4 }
            .toList()
            .sortedByDescending { it.first.value }
            .map { it.second }

        val sortedGroups = bombGroups + nonBombGroups
        val allCards = sortedGroups.flatten()
        val targetPerRow = (allCards.size + 1) / 2

        val row1Cards = mutableListOf<Card>()
        val row2Cards = mutableListOf<Card>()

        for (group in sortedGroups) {
            for (card in group) {
                if (row1Cards.size < targetPerRow) {
                    row1Cards.add(card)
                } else {
                    row2Cards.add(card)
                }
            }
        }

        addCardsToRowWithOverlap(row1Cards, playerHandRow1, cardWidth, cardHeight, cardMargin)
        addCardsToRowWithOverlap(row2Cards, playerHandRow2, cardWidth, cardHeight, cardMargin)

        // 更新本地玩家信息
        tvPlayerCardCount.text = getString(R.string.cards_count, cards.size)

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

            var sameRankCount = 1
            while (i + sameRankCount < cards.size && cards[i + sameRankCount].rank == currentRank) {
                sameRankCount++
            }

            for (j in 0 until sameRankCount) {
                val currentCard = cards[i + j]
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

    private fun updateButtonStates() {
        val isMyTurn = multiplayerEngine.isMyTurn()
        val hasSelection = selectedCards.isNotEmpty()
        btnPlay.isEnabled = isMyTurn && hasSelection
        btnPass.isEnabled = isMyTurn && multiplayerEngine.canHumanPass()
        btnHint.isEnabled = isMyTurn

        // 调试：如果是自己的回合但出牌按钮禁用
        if (isMyTurn && !hasSelection && btnPlay.isEnabled != (isMyTurn && hasSelection)) {
            DebugLogManager.d(TAG, "出牌按钮状态: isMyTurn=$isMyTurn, selectedCards=${selectedCards.size}")
        }
    }

    private fun updateScores() {
        tvTeamAScore.text = "${multiplayerEngine.teamA.team.displayName}:${multiplayerEngine.teamAScore}分"
        tvTeamBScore.text = "${multiplayerEngine.teamB.team.displayName}:${multiplayerEngine.teamBScore}分"

        // 更新本地玩家得分
        val localPlayer = multiplayerEngine.players.find { it.id == localSeatIndex }
        localPlayer?.let {
            tvPlayerScore.text = getString(R.string.collected_score, it.collectedScore)
        }
    }

    private fun showHint() {
        if (isHintShowing && selectedCards.isNotEmpty() &&
            lastHintCards.isNotEmpty() && selectedCards.toSet() == lastHintCards.toSet()) {
            deselectAllCards()
            isHintShowing = false
            lastHintCards = emptyList()
            return
        }

        val validPlays = multiplayerEngine.getValidPlaysForHuman()
        if (validPlays.isEmpty()) {
            // 调试：如果手牌不为空但没有有效出牌，记录详情
            val myHand = multiplayerEngine.getMyHand()
            val lastPlay = multiplayerEngine.getLastPlay()
            val debugInfo = "手牌:${myHand.size}张, 上家:${lastPlay?.toString() ?: "无"}"
            DebugLogManager.w(TAG, "提示无有效出牌 - $debugInfo")
            showMessage("没有能出的牌，请过牌")
            return
        }

        val hint = validPlays.minWithOrNull(compareBy({ it.size }, { it.primaryRank.value }))
            ?: return

        // 清除之前选中
        selectedCards.clear()
        cardViewMap.values.forEach { view ->
            view.findViewById<View>(R.id.cardContainer)
                .setBackgroundResource(R.drawable.card_background_large)
            view.translationY = 0f
        }

        // 选中提示牌
        hint.cards.forEach { card ->
            selectedCards.add(card)
            cardViewMap[card]?.let { view ->
                view.findViewById<View>(R.id.cardContainer)
                    .setBackgroundResource(R.drawable.card_selected_large)
                view.translationY = -16f
            }
        }

        isHintShowing = true
        lastHintCards = hint.cards.toList()

        showMessage("提示: ${hint.type.displayName}")
        updateButtonStates()
    }

    private fun showMessage(message: String) {
        tvMessage.text = message
        tvMessage.visibility = View.VISIBLE
        handler.postDelayed({
            tvMessage.visibility = View.GONE
        }, MESSAGE_DISPLAY_MS)
    }

    private fun showHistory() {
        historyCurrentPage = 0
        showHistoryPage(0)
        historyOverlay.visibility = View.VISIBLE
    }

    private fun showHistoryPage(page: Int) {
        val linesPerPage = historyLinesPerColumn * 2  // 两栏
        val totalPages = (gameHistory.size + linesPerPage - 1) / linesPerPage
        val safePage = page.coerceIn(0, maxOf(0, totalPages - 1))
        historyCurrentPage = safePage

        val startIndex = safePage * linesPerPage
        val endIndex = minOf(startIndex + linesPerPage, gameHistory.size)
        val pageLines = if (startIndex < gameHistory.size) {
            gameHistory.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // 分配到两栏
        val midPoint = (pageLines.size + 1) / 2
        val column1Lines = pageLines.take(midPoint)
        val column2Lines = pageLines.drop(midPoint)

        tvHistoryColumn1.text = column1Lines.joinToString("\n")
        tvHistoryColumn2.text = column2Lines.joinToString("\n")
        tvHistoryPageInfo.text = "${safePage + 1}/$totalPages"

        // 更新按钮状态
        btnHistoryPrev.isEnabled = safePage > 0
        btnHistoryNext.isEnabled = safePage < totalPages - 1
        btnHistoryPrev.alpha = if (safePage > 0) 1f else 0.5f
        btnHistoryNext.alpha = if (safePage < totalPages - 1) 1f else 0.5f
    }

    private fun showGameOver(result: GameResult) {
        gameOverOverlay.visibility = View.VISIBLE
        disableInput()

        val myTeam = multiplayerEngine.players.find { it.id == localSeatIndex }?.team

        tvGameOverTitle.text = getString(R.string.game_over)

        tvGameOverResult.text = when {
            result.winner == null -> getString(R.string.draw)
            result.winner == myTeam -> getString(R.string.you_win)
            else -> getString(R.string.you_lose)
        }

        tvFinalScore.text = getString(
            R.string.final_score,
            result.teamAScore,
            result.teamBScore
        )

        tvGameOverResult.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    result.winner == null -> R.color.accent
                    result.winner == myTeam -> R.color.primary_light
                    else -> R.color.score_negative
                }
            )
        )
    }

    private fun disableInput() {
        btnPlay.isEnabled = false
        btnPass.isEnabled = false
        btnHint.isEnabled = false
    }

    private fun enableInput() {
        updateButtonStates()
    }

    private fun showLeaveConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("离开游戏")
            .setMessage("确定要离开游戏吗？你的位置将由AI接管。")
            .setPositiveButton("确定") { _, _ ->
                roomManager.leaveRoom()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onBackPressed() {
        showLeaveConfirmDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::multiplayerEngine.isInitialized) multiplayerEngine.release()
        if (::gameSyncManager.isInitialized) gameSyncManager.release()
        if (::soundManager.isInitialized) soundManager.release()
        // 注意：不在此处断开 NetworkManager / 清空 holder，
        // 因为 LobbyActivity 共享同一连接，断开会破坏返回大厅后的体验
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
