package com.communicationcard.game.web.viewmodel

import com.communicationcard.game.engine.CardRules
import com.communicationcard.game.model.Card
import com.communicationcard.game.model.CardRank
import com.communicationcard.game.model.CardSuit
import com.communicationcard.game.network.SerializedCard
import com.communicationcard.game.network.SerializedGameState
import com.communicationcard.game.web.net.GameSyncManager
import com.communicationcard.game.web.net.NetworkClient
import com.communicationcard.game.web.net.RoomManager
import com.communicationcard.game.web.singleplayer.SinglePlayerEngine
import com.communicationcard.game.web.storage.GameSpeed
import com.communicationcard.game.web.storage.Statistics
import com.communicationcard.game.web.storage.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 应用顶层状态机。
 *
 * - 持有 [Screen] StateFlow 供 App.kt 渲染
 * - 协调单机引擎 / 联网管理器
 * - 把异步事件（房间更新、游戏状态、结算）映射到屏幕切换
 * - 持久化用户偏好（[UserPreferences]）与战绩（[Statistics]）到 localStorage
 *
 * 设计原则：所有跨屏幕的状态都集中在这里；屏幕 Composable 只接收一个 Screen 数据类。
 */
class AppViewModel {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // ---------- 联网管理器（懒加载，进入联网模式时才创建） ----------
    private var net: NetworkClient? = null
    private var room: RoomManager? = null
    private var sync: GameSyncManager? = null

    // ---------- 单机引擎（懒加载） ----------
    private var single: SinglePlayerEngine? = null

    // 每"会话"（一次单机或一次联网）独立的协程 Job：goHome / 切换模式时取消，
    // 避免上一会话的 collectors 继续把过期事件推到 _screen。
    private var sessionJob: Job? = null

    private fun newSessionScope(): CoroutineScope {
        sessionJob?.cancel()
        val job = SupervisorJob(parent = scope.coroutineContext[Job])
        sessionJob = job
        return CoroutineScope(scope.coroutineContext + job)
    }

    // ---------- 持久化偏好 / 战绩（构造时从 localStorage 加载） ----------
    private var prefs: UserPreferences = UserPreferences.load()
    private var stats: Statistics = Statistics.load()

    private var serverUrl = defaultServerUrl()

    // ============================================================
    //  入口动作（Home → 各子屏）
    // ============================================================

    fun startSinglePlayer() {
        val sessionScope = newSessionScope()
        // 用户偏好里的速度 → AI 出牌延迟
        val engine = SinglePlayerEngine(
            playerCount = 6,
            aiDelayMs = prefs.gameSpeed.aiDelayMs,
        ).also { single = it }
        engine.start()
        sessionScope.launch {
            engine.state.collect { state ->
                if (state == null) return@collect
                _screen.value = Screen.Game(
                    state = state,
                    localSeatIndex = engine.humanSeatIndex,
                    mode = Screen.Game.Mode.SinglePlayer,
                    selectedCardIds = currentSelection(),
                )
            }
        }
        sessionScope.launch {
            engine.gameEnd.collect { result ->
                val state = engine.state.value
                val breakdown = state?.let(::buildPlayerBreakdown).orEmpty()
                recordStatsForResult(result, mySeatIndex = engine.humanSeatIndex, players = state?.players)
                _screen.value = Screen.Settlement(result, Screen.Game.Mode.SinglePlayer, breakdown)
            }
        }
    }

    fun startMultiplayer() {
        sessionJob?.cancel()
        sessionJob = null
        _screen.value = Screen.Lobby(
            serverUrl = serverUrl,
            nickname = prefs.nickname,
            connectionState = com.communicationcard.game.web.net.WebSocketTransport.State.Disconnected,
            rooms = emptyList(),
        )
    }

    fun openSettings() {
        _screen.value = Screen.Settings(prefs)
    }

    fun openStats() {
        _screen.value = Screen.Stats(stats)
    }

    fun openHelp() {
        _screen.value = Screen.Help
    }

    fun goHome() {
        sessionJob?.cancel()
        sessionJob = null
        net?.close()
        net = null
        room = null
        sync = null
        single = null
        _screen.value = Screen.Home
    }

    // ============================================================
    //  Settings 屏：每次改值即写 localStorage（小数据，写频率低，OK）
    // ============================================================

    fun updatePrefs(transform: (UserPreferences) -> UserPreferences) {
        prefs = transform(prefs)
        UserPreferences.save(prefs)
        if (_screen.value is Screen.Settings) _screen.value = Screen.Settings(prefs)
    }

    // ============================================================
    //  Stats 屏
    // ============================================================

    fun resetStats() {
        Statistics.reset()
        stats = Statistics()
        if (_screen.value is Screen.Stats) _screen.value = Screen.Stats(stats)
    }

    private fun recordStatsForResult(
        result: com.communicationcard.game.network.SerializedGameResult,
        mySeatIndex: Int,
        players: List<com.communicationcard.game.network.SerializedPlayer>?,
    ) {
        if (mySeatIndex < 0 || players == null) return
        val myTeam = players.find { it.id == mySeatIndex }?.team ?: return
        val myTeamWon: Boolean? = when (result.winner) {
            null -> null
            myTeam -> true
            else -> false
        }
        val myTeamScore = if (myTeam == "TEAM_A") result.teamAScore else result.teamBScore
        stats = stats.afterGame(myTeamWon, myTeamScore)
        Statistics.save(stats)
    }

    private fun buildPlayerBreakdown(state: SerializedGameState): List<Screen.Settlement.PlayerSummary> =
        state.players.map { p ->
            Screen.Settlement.PlayerSummary(
                seatIndex = p.id,
                name = p.name,
                team = p.team,
                collectedScore = p.collectedScore,
                handSize = p.handSize,
                hasFinished = p.hasFinished,
                finishOrder = p.finishOrder,
            )
        }

    // ============================================================
    //  Lobby
    // ============================================================

    fun setServerUrl(url: String) {
        serverUrl = url
        updateLobby { it.copy(serverUrl = url) }
    }

    fun setNickname(name: String) {
        updatePrefs { it.copy(nickname = name) }
        updateLobby { it.copy(nickname = name) }
    }

    fun connectServer() {
        net?.close()
        val sessionScope = newSessionScope()

        val n = NetworkClient(serverUrl).also { net = it }
        val r = RoomManager(n).also { room = it }
        val s = GameSyncManager(n).also { sync = it }

        sessionScope.launch {
            n.connectionState.collect { st ->
                updateLobby { it.copy(connectionState = st) }
                if (st == com.communicationcard.game.web.net.WebSocketTransport.State.Connected) {
                    r.refreshRoomList()
                }
            }
        }
        sessionScope.launch {
            r.roomList.collect { list -> updateLobby { it.copy(rooms = list) } }
        }
        sessionScope.launch {
            combine(r.currentRoom, r.localPlayerId) { cr, pid -> cr to pid }
                .collect { (cr, pid) ->
                    if (cr != null && pid != null) {
                        if (cr.status == com.communicationcard.game.network.RoomStatus.IN_GAME) {
                            // 等 GameSync/GameStart 推到 Game 屏
                        } else {
                            val isReady = cr.players.find { it.id == pid }?.isReady ?: false
                            _screen.value = Screen.Room(cr, pid, isReady)
                        }
                    }
                }
        }
        sessionScope.launch {
            s.gameState.collect { state ->
                if (state == null) return@collect
                _screen.value = Screen.Game(
                    state = state,
                    localSeatIndex = s.localSeatIndex.value,
                    mode = Screen.Game.Mode.Multiplayer,
                    selectedCardIds = currentSelection(),
                )
            }
        }
        sessionScope.launch {
            s.gameEnd.collect { result ->
                val state = s.gameState.value
                val breakdown = state?.let(::buildPlayerBreakdown).orEmpty()
                recordStatsForResult(result, mySeatIndex = s.localSeatIndex.value, players = state?.players)
                _screen.value = Screen.Settlement(result, Screen.Game.Mode.Multiplayer, breakdown)
            }
        }

        n.connect()
    }

    fun createRoom() { room?.createRoom(playerName = prefs.nickname) }
    fun joinRoom(code: String) { room?.joinRoom(roomCode = code, playerName = prefs.nickname) }
    fun refreshRooms() { room?.refreshRoomList() }

    // ============================================================
    //  Room
    // ============================================================

    fun toggleReady() {
        val current = (_screen.value as? Screen.Room) ?: return
        val newReady = !current.isReady
        room?.setReady(newReady)
        _screen.value = current.copy(isReady = newReady)
    }

    fun startGame() { room?.startGame() }
    fun addAI() { room?.addAI() }

    fun leaveRoom() {
        room?.leaveRoom()
        _screen.value = Screen.Lobby(
            serverUrl = serverUrl,
            nickname = prefs.nickname,
            connectionState = net?.connectionState?.value
                ?: com.communicationcard.game.web.net.WebSocketTransport.State.Disconnected,
            rooms = emptyList(),
        )
        room?.refreshRoomList()
    }

    // ============================================================
    //  Game
    // ============================================================

    fun playCards(cards: List<SerializedCard>) {
        when (val s = _screen.value) {
            is Screen.Game -> when (s.mode) {
                Screen.Game.Mode.Multiplayer -> sync?.playCards(cards)
                Screen.Game.Mode.SinglePlayer -> single?.humanPlay(cards)
            }
            else -> Unit
        }
    }

    fun pass() {
        when (val s = _screen.value) {
            is Screen.Game -> when (s.mode) {
                Screen.Game.Mode.Multiplayer -> sync?.pass()
                Screen.Game.Mode.SinglePlayer -> single?.humanPass()
            }
            else -> Unit
        }
    }

    /**
     * 提示：调 [CardRules.findValidPlays]，从所有合法出法中挑"最小"的一手高亮在 UI。
     *
     * - 若我没轮到、或非 Game 屏：no-op
     * - 若是首发回合（lastPlayedGroup == null）：选最小的有效组合（保留好牌）
     * - 否则：选最小的能压过 last 的组合
     * - 若一个都找不到：清空 hint（提醒用户应过牌）
     */
    fun hint() {
        val s = (_screen.value as? Screen.Game) ?: return
        val state = s.state
        val mySeat = s.localSeatIndex
        if (state.currentPlayerIndex != mySeat) return
        val me = state.players.find { it.id == mySeat } ?: return

        val handCards = me.hand.mapNotNull(::deserializeCard)
        if (handCards.size != me.hand.size) return // 反序列化失败 → 放弃，避免错提示

        val lastGroup = state.lastPlayedGroup?.let { lg ->
            val cards = lg.cards.mapNotNull(::deserializeCard)
            if (cards.size != lg.cards.size) null else CardRules.identifyCardGroup(cards)
        }

        val validPlays = CardRules.findValidPlays(handCards, lastGroup)
        // 选 primaryRank 最小的，张数次之；保留大牌
        val pick = validPlays.minByOrNull { it.primaryRank.value * 100 + it.cards.size } ?: return

        val keys = pick.cards.map { keyOf(it) }.toSet()
        _screen.value = s.copy(hintedCardIds = keys, selectedCardIds = keys)
    }

    fun toggleSelectedCard(cardId: String) {
        val s = (_screen.value as? Screen.Game) ?: return
        val newSel = if (cardId in s.selectedCardIds) s.selectedCardIds - cardId else s.selectedCardIds + cardId
        _screen.value = s.copy(selectedCardIds = newSel, hintedCardIds = emptySet())
    }

    fun clearSelection() {
        val s = (_screen.value as? Screen.Game) ?: return
        _screen.value = s.copy(selectedCardIds = emptySet(), hintedCardIds = emptySet())
    }

    fun leaveGame() {
        goHome()
    }

    // ============================================================
    //  Settlement
    // ============================================================

    fun playAgain() {
        when ((_screen.value as? Screen.Settlement)?.mode) {
            Screen.Game.Mode.SinglePlayer -> startSinglePlayer()
            Screen.Game.Mode.Multiplayer -> _screen.value = Screen.Lobby(
                serverUrl = serverUrl,
                nickname = prefs.nickname,
                connectionState = net?.connectionState?.value
                    ?: com.communicationcard.game.web.net.WebSocketTransport.State.Disconnected,
                rooms = emptyList(),
            )
            null -> Unit
        }
    }

    // ============================================================
    //  helpers
    // ============================================================

    private fun currentSelection(): Set<String> = (_screen.value as? Screen.Game)?.selectedCardIds ?: emptySet()

    private inline fun updateLobby(transform: (Screen.Lobby) -> Screen.Lobby) {
        val current = _screen.value as? Screen.Lobby ?: return
        _screen.value = transform(current)
    }

    /** 与 GameScreen.kt 的 keyOf 保持一致：suit|rank|deckIndex 唯一标识一张实例。 */
    private fun keyOf(c: Card): String = "${c.suit.name}|${c.rank.name}|${c.deckIndex}"

    private fun deserializeCard(s: SerializedCard): Card? = try {
        Card(
            rank = CardRank.valueOf(s.rank),
            suit = CardSuit.valueOf(s.suit),
            deckIndex = s.deckIndex,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * 默认服务器 URL：
 *
 * - 本地 dev（hostname=localhost / 127.0.0.1 / 空）→ `ws://localhost:8080/game`
 * - 部署到带反代的服务器 → `ws[s]://<host>/game` 同源同端口
 */
private fun defaultServerUrl(): String {
    val proto = currentProtocol()
    val host = currentHost()
    val isLocal = host.isBlank() || host == "localhost" || host == "127.0.0.1"
    if (isLocal) return "ws://localhost:8080/game"
    val wsScheme = if (proto == "https:") "wss" else "ws"
    return "$wsScheme://$host/game"
}

@JsFun("() => window.location.protocol")
private external fun currentProtocol(): String

@JsFun("() => window.location.hostname")
private external fun currentHost(): String
