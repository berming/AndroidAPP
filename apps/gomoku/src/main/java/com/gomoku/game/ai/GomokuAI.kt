package com.gomoku.game.ai

import com.gomoku.game.engine.GameBoard
import com.gomoku.game.model.*
import kotlin.math.max
import kotlin.math.min

/**
 * 五子棋AI - 使用Minimax算法与Alpha-Beta剪枝
 */
class GomokuAI(private val difficulty: Difficulty = Difficulty.MEDIUM) {

    companion object {
        // 棋型分数
        private const val FIVE = 100000       // 五连
        private const val OPEN_FOUR = 10000   // 活四
        private const val FOUR = 1000         // 冲四
        private const val OPEN_THREE = 1000   // 活三
        private const val THREE = 100         // 眠三
        private const val OPEN_TWO = 100      // 活二
        private const val TWO = 10            // 眠二
        private const val ONE = 1             // 单子
    }

    // 方向向量
    private val directions = listOf(
        Pair(0, 1),   // 横
        Pair(1, 0),   // 竖
        Pair(1, 1),   // 斜
        Pair(1, -1)   // 反斜
    )

    /**
     * 获取AI的最佳落子位置
     */
    fun getBestMove(board: GameBoard): Position? {
        if (board.gameState != GameState.PLAYING) return null

        val candidates = board.getEmptyPositionsWithNeighbors()
        if (candidates.isEmpty()) return null

        // 如果是第一步，下在中心
        if (board.moveHistory.isEmpty()) {
            return Position(board.size / 2, board.size / 2)
        }

        // 如果是第二步（AI后手第一步），下在对方棋子附近
        if (board.moveHistory.size == 1) {
            val firstMove = board.moveHistory[0].position
            val offsets = listOf(
                Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1),
                Pair(1, 0), Pair(0, 1), Pair(-1, 0), Pair(0, -1)
            )
            for ((dr, dc) in offsets) {
                val pos = Position(firstMove.row + dr, firstMove.col + dc)
                if (pos.isValid(board.size) && board.isEmpty(pos)) {
                    return pos
                }
            }
        }

        // 检查是否有必胜或必防的点
        val urgentMove = findUrgentMove(board, Stone.WHITE) // AI是白棋
            ?: findUrgentMove(board, Stone.BLACK)  // 阻止玩家
        if (urgentMove != null) return urgentMove

        // 使用Minimax算法
        return findBestMoveWithMinimax(board, candidates)
    }

    /**
     * 查找紧急落子点（能赢或必须防守）
     */
    private fun findUrgentMove(board: GameBoard, stone: Stone): Position? {
        val candidates = board.getEmptyPositionsWithNeighbors()

        for (pos in candidates) {
            board.simulatePlace(pos.row, pos.col, stone)
            val score = evaluatePosition(board, pos, stone)
            board.removeStone(pos.row, pos.col)

            // 如果能形成五连
            if (score >= FIVE) {
                return pos
            }
        }

        // 检查活四
        for (pos in candidates) {
            board.simulatePlace(pos.row, pos.col, stone)
            val score = evaluatePosition(board, pos, stone)
            board.removeStone(pos.row, pos.col)

            if (score >= OPEN_FOUR) {
                return pos
            }
        }

        return null
    }

    /**
     * 使用Minimax算法找最佳落子
     */
    private fun findBestMoveWithMinimax(board: GameBoard, candidates: List<Position>): Position {
        var bestScore = Int.MIN_VALUE
        var bestMove = candidates[0]

        // 对候选位置按评估分数排序（优化搜索顺序）
        val sortedCandidates = candidates.map { pos ->
            board.simulatePlace(pos.row, pos.col, Stone.WHITE)
            val score = evaluateBoard(board, Stone.WHITE)
            board.removeStone(pos.row, pos.col)
            Pair(pos, score)
        }.sortedByDescending { it.second }.map { it.first }

        // 限制搜索的候选数量
        val limitedCandidates = sortedCandidates.take(15)

        for (pos in limitedCandidates) {
            board.simulatePlace(pos.row, pos.col, Stone.WHITE)
            val score = minimax(
                board,
                difficulty.searchDepth,
                Int.MIN_VALUE,
                Int.MAX_VALUE,
                false
            )
            board.removeStone(pos.row, pos.col)

            if (score > bestScore) {
                bestScore = score
                bestMove = pos
            }
        }

        return bestMove
    }

    /**
     * Minimax算法与Alpha-Beta剪枝
     */
    private fun minimax(
        board: GameBoard,
        depth: Int,
        alpha: Int,
        beta: Int,
        isMaximizing: Boolean
    ): Int {
        // 终止条件
        if (depth == 0) {
            return evaluateBoard(board, Stone.WHITE)
        }

        val candidates = board.getEmptyPositionsWithNeighbors().take(10)
        if (candidates.isEmpty()) {
            return evaluateBoard(board, Stone.WHITE)
        }

        var a = alpha
        var b = beta

        if (isMaximizing) {
            var maxScore = Int.MIN_VALUE
            for (pos in candidates) {
                board.simulatePlace(pos.row, pos.col, Stone.WHITE)
                val score = minimax(board, depth - 1, a, b, false)
                board.removeStone(pos.row, pos.col)

                maxScore = max(maxScore, score)
                a = max(a, score)
                if (b <= a) break  // Beta剪枝
            }
            return maxScore
        } else {
            var minScore = Int.MAX_VALUE
            for (pos in candidates) {
                board.simulatePlace(pos.row, pos.col, Stone.BLACK)
                val score = minimax(board, depth - 1, a, b, true)
                board.removeStone(pos.row, pos.col)

                minScore = min(minScore, score)
                b = min(b, score)
                if (b <= a) break  // Alpha剪枝
            }
            return minScore
        }
    }

    /**
     * 评估整个棋盘对指定玩家的分数
     */
    private fun evaluateBoard(board: GameBoard, stone: Stone): Int {
        var score = 0
        val opponent = if (stone == Stone.BLACK) Stone.WHITE else Stone.BLACK

        for (row in 0 until board.size) {
            for (col in 0 until board.size) {
                if (board.getStone(row, col) == stone) {
                    score += evaluatePosition(board, Position(row, col), stone)
                } else if (board.getStone(row, col) == opponent) {
                    score -= evaluatePosition(board, Position(row, col), opponent)
                }
            }
        }

        return score
    }

    /**
     * 评估单个位置的分数
     */
    private fun evaluatePosition(board: GameBoard, pos: Position, stone: Stone): Int {
        var totalScore = 0

        for ((dr, dc) in directions) {
            totalScore += evaluateLine(board, pos, dr, dc, stone)
        }

        return totalScore
    }

    /**
     * 评估一条线上的棋型
     */
    private fun evaluateLine(
        board: GameBoard,
        pos: Position,
        dr: Int,
        dc: Int,
        stone: Stone
    ): Int {
        var count = 1
        var openEnds = 0
        var blocked = 0

        // 正向搜索
        var r = pos.row + dr
        var c = pos.col + dc
        while (Position(r, c).isValid(board.size) && board.getStone(r, c) == stone) {
            count++
            r += dr
            c += dc
        }
        if (Position(r, c).isValid(board.size) && board.getStone(r, c) == Stone.EMPTY) {
            openEnds++
        } else {
            blocked++
        }

        // 反向搜索
        r = pos.row - dr
        c = pos.col - dc
        while (Position(r, c).isValid(board.size) && board.getStone(r, c) == stone) {
            count++
            r -= dr
            c -= dc
        }
        if (Position(r, c).isValid(board.size) && board.getStone(r, c) == Stone.EMPTY) {
            openEnds++
        } else {
            blocked++
        }

        return getPatternScore(count, openEnds)
    }

    /**
     * 根据连子数和开放端数获取分数
     */
    private fun getPatternScore(count: Int, openEnds: Int): Int {
        if (count >= 5) return FIVE

        return when {
            count == 4 && openEnds == 2 -> OPEN_FOUR
            count == 4 && openEnds == 1 -> FOUR
            count == 3 && openEnds == 2 -> OPEN_THREE
            count == 3 && openEnds == 1 -> THREE
            count == 2 && openEnds == 2 -> OPEN_TWO
            count == 2 && openEnds == 1 -> TWO
            count == 1 && openEnds > 0 -> ONE
            else -> 0
        }
    }
}
