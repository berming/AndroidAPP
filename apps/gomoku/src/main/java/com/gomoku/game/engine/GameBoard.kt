package com.gomoku.game.engine

import com.gomoku.game.model.*

/**
 * 五子棋棋盘
 */
class GameBoard(val size: Int = 15) {

    // 棋盘状态
    private val board: Array<Array<Stone>> = Array(size) { Array(size) { Stone.EMPTY } }

    // 落子历史
    private val _moveHistory = mutableListOf<Move>()
    val moveHistory: List<Move> get() = _moveHistory.toList()

    // 当前回合
    var currentTurn: Stone = Stone.BLACK
        private set

    // 游戏状态
    var gameState: GameState = GameState.PLAYING
        private set

    // 胜利信息
    var winInfo: WinInfo? = null
        private set

    // 方向向量：横、竖、斜、反斜
    private val directions = listOf(
        Pair(0, 1),   // 横
        Pair(1, 0),   // 竖
        Pair(1, 1),   // 斜
        Pair(1, -1)   // 反斜
    )

    /**
     * 获取指定位置的棋子
     */
    fun getStone(row: Int, col: Int): Stone {
        return if (Position(row, col).isValid(size)) board[row][col] else Stone.EMPTY
    }

    fun getStone(pos: Position): Stone = getStone(pos.row, pos.col)

    /**
     * 判断位置是否为空
     */
    fun isEmpty(row: Int, col: Int): Boolean = getStone(row, col) == Stone.EMPTY

    fun isEmpty(pos: Position): Boolean = isEmpty(pos.row, pos.col)

    /**
     * 落子
     * @return true 如果落子成功
     */
    fun placeStone(row: Int, col: Int): Boolean {
        val pos = Position(row, col)

        if (!pos.isValid(size) || !isEmpty(pos) || gameState != GameState.PLAYING) {
            return false
        }

        board[row][col] = currentTurn
        _moveHistory.add(Move(pos, currentTurn, _moveHistory.size + 1))

        // 检查胜负
        checkWin(pos)

        // 如果游戏继续，切换回合
        if (gameState == GameState.PLAYING) {
            currentTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK

            // 检查平局（棋盘满了）
            if (_moveHistory.size >= size * size) {
                gameState = GameState.DRAW
            }
        }

        return true
    }

    fun placeStone(pos: Position): Boolean = placeStone(pos.row, pos.col)

    /**
     * 悔棋
     */
    fun undo(): Boolean {
        if (_moveHistory.isEmpty() || gameState != GameState.PLAYING) {
            return false
        }

        val lastMove = _moveHistory.removeAt(_moveHistory.lastIndex)
        board[lastMove.position.row][lastMove.position.col] = Stone.EMPTY
        currentTurn = lastMove.stone

        return true
    }

    /**
     * 悔两步（玩家和AI各一步）
     */
    fun undoTwo(): Boolean {
        if (_moveHistory.size < 2) return false

        // 重置游戏状态
        gameState = GameState.PLAYING
        winInfo = null

        repeat(2) {
            val lastMove = _moveHistory.removeAt(_moveHistory.lastIndex)
            board[lastMove.position.row][lastMove.position.col] = Stone.EMPTY
        }

        currentTurn = Stone.BLACK
        return true
    }

    /**
     * 检查是否获胜
     */
    private fun checkWin(lastMove: Position) {
        val stone = board[lastMove.row][lastMove.col]

        for ((dr, dc) in directions) {
            val positions = mutableListOf(lastMove)

            // 正向搜索
            var r = lastMove.row + dr
            var c = lastMove.col + dc
            while (Position(r, c).isValid(size) && board[r][c] == stone) {
                positions.add(Position(r, c))
                r += dr
                c += dc
            }

            // 反向搜索
            r = lastMove.row - dr
            c = lastMove.col - dc
            while (Position(r, c).isValid(size) && board[r][c] == stone) {
                positions.add(Position(r, c))
                r -= dr
                c -= dc
            }

            // 检查是否五连
            if (positions.size >= 5) {
                gameState = if (stone == Stone.BLACK) GameState.BLACK_WINS else GameState.WHITE_WINS
                winInfo = WinInfo(stone, positions.sortedWith(compareBy({ it.row }, { it.col })))
                return
            }
        }
    }

    /**
     * 获取所有空位置
     */
    fun getEmptyPositions(): List<Position> {
        val positions = mutableListOf<Position>()
        for (row in 0 until size) {
            for (col in 0 until size) {
                if (board[row][col] == Stone.EMPTY) {
                    positions.add(Position(row, col))
                }
            }
        }
        return positions
    }

    /**
     * 获取有邻居的空位置（用于AI优化）
     */
    fun getEmptyPositionsWithNeighbors(): List<Position> {
        val positions = mutableListOf<Position>()
        for (row in 0 until size) {
            for (col in 0 until size) {
                if (board[row][col] == Stone.EMPTY && hasNeighbor(row, col)) {
                    positions.add(Position(row, col))
                }
            }
        }
        return if (positions.isEmpty()) listOf(Position(size / 2, size / 2)) else positions
    }

    /**
     * 检查位置是否有邻居（周围2格内有棋子）
     */
    private fun hasNeighbor(row: Int, col: Int, distance: Int = 2): Boolean {
        for (dr in -distance..distance) {
            for (dc in -distance..distance) {
                if (dr == 0 && dc == 0) continue
                val nr = row + dr
                val nc = col + dc
                if (Position(nr, nc).isValid(size) && board[nr][nc] != Stone.EMPTY) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 重置棋盘
     */
    fun reset() {
        for (row in 0 until size) {
            for (col in 0 until size) {
                board[row][col] = Stone.EMPTY
            }
        }
        _moveHistory.clear()
        currentTurn = Stone.BLACK
        gameState = GameState.PLAYING
        winInfo = null
    }

    /**
     * 复制棋盘（用于AI模拟）
     */
    fun copy(): GameBoard {
        val newBoard = GameBoard(size)
        for (row in 0 until size) {
            for (col in 0 until size) {
                newBoard.board[row][col] = this.board[row][col]
            }
        }
        newBoard.currentTurn = this.currentTurn
        newBoard.gameState = this.gameState
        newBoard._moveHistory.addAll(this._moveHistory)
        return newBoard
    }

    /**
     * 在指定位置模拟落子（不改变回合，用于AI评估）
     */
    fun simulatePlace(row: Int, col: Int, stone: Stone): Boolean {
        if (!Position(row, col).isValid(size) || !isEmpty(row, col)) {
            return false
        }
        board[row][col] = stone
        return true
    }

    /**
     * 移除指定位置的棋子（用于AI模拟后恢复）
     */
    fun removeStone(row: Int, col: Int) {
        if (Position(row, col).isValid(size)) {
            board[row][col] = Stone.EMPTY
        }
    }
}
