package com.gomoku.game.model

/**
 * 棋子类型
 */
enum class Stone {
    EMPTY,  // 空
    BLACK,  // 黑棋（玩家先手）
    WHITE   // 白棋（AI后手）
}

/**
 * 棋盘位置
 */
data class Position(val row: Int, val col: Int) {
    fun isValid(boardSize: Int): Boolean {
        return row in 0 until boardSize && col in 0 until boardSize
    }

    override fun toString(): String = "($row, $col)"
}

/**
 * 游戏状态
 */
enum class GameState {
    PLAYING,        // 游戏进行中
    BLACK_WINS,     // 黑棋获胜
    WHITE_WINS,     // 白棋获胜
    DRAW            // 平局
}

/**
 * 落子记录
 */
data class Move(
    val position: Position,
    val stone: Stone,
    val moveNumber: Int
)

/**
 * 游戏难度
 */
enum class Difficulty(val displayName: String, val searchDepth: Int) {
    EASY("简单", 1),
    MEDIUM("中等", 2),
    HARD("困难", 3)
}

/**
 * 胜利信息
 */
data class WinInfo(
    val winner: Stone,
    val winningPositions: List<Position>  // 五连珠的位置
)
