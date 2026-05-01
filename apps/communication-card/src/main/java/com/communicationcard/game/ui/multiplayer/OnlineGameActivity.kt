package com.communicationcard.game.ui.multiplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.communicationcard.game.R
import com.communicationcard.game.engine.*
import com.communicationcard.game.model.*
import com.communicationcard.game.network.*
import com.communicationcard.game.ui.SoundManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 多人游戏界面
 *
 * 界面结构：
 * - 顶部：其他玩家区域（显示手牌数、出牌、状态）
 * - 中部：当前轮信息（分数、最大牌、回合倒计时）
 * - 底部：本地玩家手牌和操作按钮
 * - 浮动：聊天按钮和面板
 * - 遮罩：游戏结束、重连中
 *
 * 核心组件：
 * - MultiplayerGameEngine: 游戏逻辑
 * - GameSyncManager: 状态同步
 * - TextChatManager: 聊天功能
 */
class OnlineGameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GAME_STATE = "game_state"
        const val EXTRA_LOCAL_SEAT_INDEX = "local_seat_index"
    }

    private lateinit var networkManager: NetworkManager
    private lateinit var roomManager: RoomManager
    private lateinit var gameSyncManager: GameSyncManager
    private lateinit var multiplayerEngine: MultiplayerGameEngine
    private lateinit var textChatManager: TextChatManager
    private lateinit var soundManager: SoundManager

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
    private lateinit var btnBackMenu: Button
    private lateinit var tvPlayerCardCount: TextView
    private lateinit var tvPlayerScore: TextView

    // Current round display
    private lateinit var tvCurrentLeader: TextView
    private lateinit var currentWinningCards: LinearLayout
    private lateinit var tvRoundScore: TextView

    // Turn timer
    private lateinit var tvTurnTimer: TextView

    // Reconnection overlay
    private lateinit var reconnectOverlay: FrameLayout

    // Chat FAB
    private lateinit var fabChat: View
    private lateinit var tvUnreadCount: TextView
    private lateinit var chatPanel: View

    // Player views
    private val playerViews = mutableMapOf<Int, View>()

    // Selected cards
    private val selectedCards = mutableListOf<Card>()
    private val cardViewMap = mutableMapOf<Card, View>()

    // Track played cards in current round
    private val currentRoundPlayedCards = mutableMapOf<Int, CardGroup?>()

    private var localSeatIndex: Int = -1
    private var isChatVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online_game)

        soundManager = SoundManager(this)

        initManagers()
        initViews()
        setupListeners()
        initGameFromIntent()
        observeState()
    }

    private fun initManagers() {
        networkManager = NetworkManagerHolder.instance
            ?: throw IllegalStateException("NetworkManager not initialized")
        roomManager = RoomManagerHolder.instance
            ?: throw IllegalStateException("RoomManager not initialized")
        gameSyncManager = GameSyncManager(networkManager)
        textChatManager = TextChatManager(networkManager, roomManager)
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
        btnBackMenu = findViewById(R.id.btnBackMenu)
        tvPlayerCardCount = findViewById(R.id.tvPlayerCardCount)
        tvPlayerScore = findViewById(R.id.tvPlayerScore)

        // Current round display
        tvCurrentLeader = findViewById(R.id.tvCurrentLeader)
        currentWinningCards = findViewById(R.id.currentWinningCards)
        tvRoundScore = findViewById(R.id.tvRoundScore)

        // Turn timer
        tvTurnTimer = findViewById(R.id.tvTurnTimer)

        // Reconnection overlay
        reconnectOverlay = findViewById(R.id.reconnectOverlay)

        // Chat
        fabChat = findViewById(R.id.fabChat)
        tvUnreadCount = findViewById(R.id.tvUnreadCount)
        chatPanel = findViewById(R.id.chatPanel)

        // Player views (up to 6 players, excluding local player)
        playerViews[0] = findViewById(R.id.player1)
        playerViews[1] = findViewById(R.id.player2)
        playerViews[2] = findViewById(R.id.player3)
        playerViews[3] = findViewById(R.id.player4)
        playerViews[4] = findViewById(R.id.player5)
        playerViews[5] = findViewById(R.id.player6)
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

        btnHint.setOnClickListener {
            showHint()
        }

        btnBackMenu.setOnClickListener {
            showLeaveConfirmDialog()
        }

        fabChat.setOnClickListener {
            toggleChat()
        }

        chatPanel.findViewById<View>(R.id.btnCloseChat)?.setOnClickListener {
            hideChat()
        }

        // Quick messages
        chatPanel.findViewById<View>(R.id.btnQuick1)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.NICE_PLAY)
        }
        chatPanel.findViewById<View>(R.id.btnQuick2)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.PASS)
        }
        chatPanel.findViewById<View>(R.id.btnQuick3)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.HELP_TEAMMATE)
        }
        chatPanel.findViewById<View>(R.id.btnQuick4)?.setOnClickListener {
            textChatManager.sendQuickMessage(QuickMessageType.GOOD_GAME)
        }
    }

    private fun initGameFromIntent() {
        val stateJson = intent.getStringExtra(EXTRA_GAME_STATE)
        localSeatIndex = intent.getIntExtra(EXTRA_LOCAL_SEAT_INDEX, -1)

        if (stateJson != null && localSeatIndex >= 0) {
            try {
                val state = GameMessage.json.decodeFromString(
                    SerializedGameState.serializer(),
                    stateJson
                )
                gameSyncManager.setInitialState(state, localSeatIndex)
                multiplayerEngine = MultiplayerGameEngine(gameSyncManager, localSeatIndex)

                // Setup event listener
                multiplayerEngine.addEventListener { event ->
                    runOnUiThread {
                        handleGameEvent(event)
                    }
                }

                // Initial UI update
                updateAllPlayerViews()
                updatePlayerHand()
                updateScores()
                updateCurrentRoundDisplay()

            } catch (e: Exception) {
                Toast.makeText(this, "加载游戏状态失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            Toast.makeText(this, "无效的游戏状态", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun observeState() {
        // Observe connection state
        lifecycleScope.launch {
            networkManager.connectionState.collectLatest { state ->
                handleConnectionState(state)
            }
        }

        // Observe turn timer
        lifecycleScope.launch {
            gameSyncManager.turnTimeRemaining.collectLatest { seconds ->
                updateTurnTimer(seconds)
            }
        }

        // Observe action results
        lifecycleScope.launch {
            gameSyncManager.actionResults.collectLatest { result ->
                if (!result.success) {
                    showMessage(result.error ?: "操作失败")
                }
            }
        }

        // Observe turn timeouts
        lifecycleScope.launch {
            gameSyncManager.turnTimeouts.collectLatest { playerId ->
                val player = multiplayerEngine.players.getOrNull(playerId)
                if (player != null) {
                    showMessage("${player.name} 超时，自动过牌")
                }
            }
        }

        // Observe chat
        lifecycleScope.launch {
            textChatManager.unreadCount.collectLatest { count ->
                updateUnreadBadge(count)
            }
        }
    }

    private fun handleConnectionState(state: ConnectionState) {
        when (state) {
            ConnectionState.Disconnected -> {
                reconnectOverlay.visibility = View.VISIBLE
                disableInput()
            }
            is ConnectionState.Reconnecting -> {
                reconnectOverlay.visibility = View.VISIBLE
                disableInput()
            }
            ConnectionState.Connected -> {
                reconnectOverlay.visibility = View.GONE
                enableInput()
            }
            is ConnectionState.Error -> {
                reconnectOverlay.visibility = View.VISIBLE
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
                if (event.cardGroup.type == CardGroupType.BOMB) {
                    soundManager.playBombSound()
                } else {
                    soundManager.playCardSound()
                }

                currentRoundPlayedCards[event.player.id] = event.cardGroup
                showPlayerPlayedCards(event.player, event.cardGroup)
                updatePlayerView(event.player)
                updateCurrentRoundDisplay()
                updateScores()

                if (event.player.id == localSeatIndex) {
                    updatePlayerHand()
                }
            }

            is GameEvent.PlayerPassed -> {
                soundManager.playPassSound()
                currentRoundPlayedCards[event.player.id] = null
                showPlayerPassedStatus(event.player)
            }

            is GameEvent.RoundWon -> {
                soundManager.playWinRoundSound()
                showMessage("${event.player.name} 赢得此轮，获得 ${event.score} 分")
                currentRoundPlayedCards.clear()
                clearAllPlayedCards()
            }

            is GameEvent.PlayerFinished -> {
                showMessage("${event.player.name} 已走完（第${event.order}个）")
                updatePlayerView(event.player)
            }

            is GameEvent.ScoreUpdate -> {
                updateScores()
            }

            is GameEvent.GameEnded -> {
                val myTeam = multiplayerEngine.players.find { it.id == localSeatIndex }?.team
                if (event.result.winner == myTeam) {
                    soundManager.playGameWinSound()
                } else if (event.result.winner != null) {
                    soundManager.playGameLoseSound()
                }
                showGameOver(event.result)
            }

            else -> {}
        }
    }

    // ========== UI更新方法 ==========

    private fun updateAllPlayerViews() {
        val players = multiplayerEngine.players
        players.forEachIndexed { index, player ->
            if (index != localSeatIndex) {
                updatePlayerView(player)
            }
        }
    }

    private fun updatePlayerView(player: Player) {
        val view = playerViews[player.id] ?: return

        val tvName = view.findViewById<TextView>(R.id.tvPlayerName)
        val tvCards = view.findViewById<TextView>(R.id.tvCardCount)
        val tvScore = view.findViewById<TextView>(R.id.tvScore)
        val teamIndicator = view.findViewById<View>(R.id.teamIndicator)

        tvName?.text = player.name
        tvCards?.text = if (player.hasFinished) "已完成" else "${player.handSize}张"
        tvScore?.text = "已收:${player.collectedScore}分"

        teamIndicator?.setBackgroundResource(
            if (player.team == Team.TEAM_A) R.color.team_a else R.color.team_b
        )

        // Show if current player
        val currentPlayer = multiplayerEngine.getCurrentPlayer()
        view.alpha = if (currentPlayer?.id == player.id) 1.0f else 0.7f
    }

    private fun updatePlayerHand() {
        playerHandRow1.removeAllViews()
        playerHandRow2.removeAllViews()
        cardViewMap.clear()

        val hand = multiplayerEngine.getMyHand().sortedWith(
            compareBy({ it.rank.value }, { it.suit.ordinal })
        )

        val halfSize = (hand.size + 1) / 2

        hand.forEachIndexed { index, card ->
            val cardView = createCardView(card)
            cardViewMap[card] = cardView

            if (index < halfSize) {
                playerHandRow1.addView(cardView)
            } else {
                playerHandRow2.addView(cardView)
            }
        }

        tvPlayerCardCount.text = "手牌: ${hand.size}张"
    }

    private fun createCardView(card: Card): View {
        val cardView = LayoutInflater.from(this).inflate(R.layout.item_card_large, null)
        val container = cardView.findViewById<View>(R.id.cardContainer)
        val tvRank = cardView.findViewById<TextView>(R.id.tvCardRank)
        val tvSuit = cardView.findViewById<TextView>(R.id.tvCardSuit)

        tvRank.text = card.rank.displayName
        tvSuit.text = card.suit.symbol

        val color = when (card.suit) {
            CardSuit.HEART, CardSuit.DIAMOND -> ContextCompat.getColor(this, R.color.card_red)
            CardSuit.JOKER -> if (card.rank == CardRank.BIG_JOKER)
                ContextCompat.getColor(this, R.color.card_red)
            else
                ContextCompat.getColor(this, R.color.card_black)
            else -> ContextCompat.getColor(this, R.color.card_black)
        }
        tvRank.setTextColor(color)
        tvSuit.setTextColor(color)

        cardView.setOnClickListener {
            toggleCardSelection(card, cardView)
        }

        return cardView
    }

    private fun toggleCardSelection(card: Card, view: View) {
        val container = view.findViewById<View>(R.id.cardContainer)

        if (selectedCards.contains(card)) {
            selectedCards.remove(card)
            container.setBackgroundResource(R.drawable.card_background_large)
            view.translationY = 0f
        } else {
            selectedCards.add(card)
            container.setBackgroundResource(R.drawable.card_background_selected)
            view.translationY = -20f
        }

        updateButtonStates()
    }

    private fun updateButtonStates() {
        val isMyTurn = multiplayerEngine.isMyTurn()

        btnPlay.isEnabled = isMyTurn && selectedCards.isNotEmpty()
        btnPass.isEnabled = isMyTurn && multiplayerEngine.canHumanPass()
        btnHint.isEnabled = isMyTurn
    }

    private fun updateScores() {
        tvTeamAScore.text = "红队: ${multiplayerEngine.teamAScore}分"
        tvTeamBScore.text = "蓝队: ${multiplayerEngine.teamBScore}分"
    }

    private fun updateCurrentRoundDisplay() {
        val lastPlay = multiplayerEngine.getLastPlay()
        val state = gameSyncManager.gameState.value

        if (lastPlay != null && state?.lastPlayerId != null) {
            val leader = multiplayerEngine.players.getOrNull(state.lastPlayerId!!)
            tvCurrentLeader.text = leader?.name ?: "???"
            tvRoundScore.text = "本轮: ${multiplayerEngine.currentRoundScore}分"

            currentWinningCards.removeAllViews()
            lastPlay.cards.take(5).forEach { card ->
                val cardView = createSmallCardView(card)
                currentWinningCards.addView(cardView)
            }
            if (lastPlay.cards.size > 5) {
                val moreText = TextView(this).apply {
                    text = "+${lastPlay.cards.size - 5}"
                    setTextColor(ContextCompat.getColor(this@OnlineGameActivity, R.color.text_primary))
                }
                currentWinningCards.addView(moreText)
            }
        } else {
            tvCurrentLeader.text = "自由出牌"
            tvRoundScore.text = "本轮: 0分"
            currentWinningCards.removeAllViews()
        }
    }

    private fun createSmallCardView(card: Card): View {
        val cardView = LayoutInflater.from(this).inflate(R.layout.item_card_small, null)
        val tvRank = cardView.findViewById<TextView>(R.id.tvCardRank)
        val tvSuit = cardView.findViewById<TextView>(R.id.tvCardSuit)

        tvRank.text = card.rank.displayName
        tvSuit.text = card.suit.symbol

        return cardView
    }

    private fun updateTurnIndicator(player: Player) {
        val statusText = if (player.id == localSeatIndex) {
            "轮到你出牌"
        } else {
            "等待 ${player.name} 出牌..."
        }
        tvGameStatus.text = statusText
    }

    private fun updateTurnTimer(seconds: Int) {
        tvTurnTimer.text = "${seconds}秒"

        val color = when {
            seconds <= 5 -> ContextCompat.getColor(this, R.color.timer_critical)
            seconds <= 10 -> ContextCompat.getColor(this, R.color.timer_warning)
            else -> ContextCompat.getColor(this, R.color.timer_normal)
        }
        tvTurnTimer.setTextColor(color)
    }

    private fun showPlayerPlayedCards(player: Player, cardGroup: CardGroup) {
        val view = playerViews[player.id] ?: return
        val playedCardsContainerContainer = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
        playedCardsContainerContainer?.removeAllViews()

        cardGroup.cards.take(3).forEach { card ->
            val cardView = createSmallCardView(card)
            playedCardsContainerContainer?.addView(cardView)
        }

        playedCardsContainerContainer?.visibility = View.VISIBLE
    }

    private fun showPlayerPassedStatus(player: Player) {
        val view = playerViews[player.id] ?: return
        val playedCardsContainerContainer = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
        playedCardsContainerContainer?.removeAllViews()

        val passText = TextView(this).apply {
            text = "过"
            setTextColor(ContextCompat.getColor(this@OnlineGameActivity, R.color.text_secondary))
            textSize = 16f
        }
        playedCardsContainerContainer?.addView(passText)
        playedCardsContainerContainer?.visibility = View.VISIBLE
    }

    private fun clearAllPlayedCards() {
        playerViews.values.forEach { view ->
            val playedCardsContainerContainer = view.findViewById<LinearLayout>(R.id.playedCardsContainer)
            playedCardsContainerContainer?.removeAllViews()
            playedCardsContainerContainer?.visibility = View.GONE
        }
    }

    private fun showMessage(text: String) {
        tvMessage.text = text
        tvMessage.visibility = View.VISIBLE

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            tvMessage.visibility = View.GONE
        }, 2000)
    }

    private fun showHint() {
        val validPlays = multiplayerEngine.getValidPlaysForHuman()
        if (validPlays.isEmpty()) {
            showMessage("没有可出的牌，请过牌")
            return
        }

        // Select the first valid play
        selectedCards.clear()
        val bestPlay = validPlays.first()
        bestPlay.cards.forEach { card ->
            val cardView = cardViewMap[card]
            if (cardView != null) {
                selectedCards.add(card)
                cardView.findViewById<View>(R.id.cardContainer)
                    .setBackgroundResource(R.drawable.card_background_selected)
                cardView.translationY = -20f
            }
        }

        updateButtonStates()
    }

    private fun showGameOver(result: GameResult) {
        gameOverOverlay.visibility = View.VISIBLE
        disableInput()

        val myTeam = multiplayerEngine.players.find { it.id == localSeatIndex }?.team

        when {
            result.winner == null -> {
                tvGameOverTitle.text = "平局"
                tvGameOverResult.text = "势均力敌！"
            }
            result.winner == myTeam -> {
                tvGameOverTitle.text = "胜利!"
                tvGameOverResult.text = "恭喜你的队伍获胜！"
            }
            else -> {
                tvGameOverTitle.text = "失败"
                tvGameOverResult.text = "再接再厉！"
            }
        }

        tvFinalScore.text = "红队 ${result.teamAScore} : ${result.teamBScore} 蓝队"
    }

    private fun disableInput() {
        btnPlay.isEnabled = false
        btnPass.isEnabled = false
        btnHint.isEnabled = false
    }

    private fun enableInput() {
        updateButtonStates()
    }

    // ========== Chat ==========

    private fun toggleChat() {
        if (isChatVisible) {
            hideChat()
        } else {
            showChat()
        }
    }

    private fun showChat() {
        chatPanel.visibility = View.VISIBLE
        isChatVisible = true
        textChatManager.markAsRead()
    }

    private fun hideChat() {
        chatPanel.visibility = View.GONE
        isChatVisible = false
    }

    private fun updateUnreadBadge(count: Int) {
        if (count > 0 && !isChatVisible) {
            tvUnreadCount.visibility = View.VISIBLE
            tvUnreadCount.text = if (count > 99) "99+" else count.toString()
        } else {
            tvUnreadCount.visibility = View.GONE
        }
    }

    // ========== Navigation ==========

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
        if (isChatVisible) {
            hideChat()
        } else {
            showLeaveConfirmDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        multiplayerEngine.release()
        gameSyncManager.release()
        textChatManager.release()
    }
}
