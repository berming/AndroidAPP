package com.gomoku.game.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.gomoku.game.engine.GameBoard
import com.gomoku.game.model.*

/**
 * 五子棋棋盘视图
 */
class GomokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 棋盘数据
    var gameBoard: GameBoard? = null
        set(value) {
            field = value
            invalidate()
        }

    // 点击监听器
    var onCellClickListener: ((row: Int, col: Int) -> Unit)? = null

    // 绘制参数
    private var cellSize: Float = 0f
    private var boardOffset: Float = 0f
    private var stoneRadius: Float = 0f

    // 画笔
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DEB887")  // 棋盘底色
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B4513")  // 线条颜色
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val blackStonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        setShadowLayer(4f, 2f, 2f, Color.parseColor("#40000000"))
    }

    private val whiteStonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 2f, 2f, Color.parseColor("#40000000"))
    }

    private val whiteStoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val starPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B4513")
        style = Paint.Style.FILL
    }

    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val winLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val moveNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // 是否显示落子序号
    var showMoveNumbers: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // 启用阴影
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDimensions()
    }

    private fun calculateDimensions() {
        val board = gameBoard ?: return
        val viewSize = minOf(width, height).toFloat()
        val padding = viewSize * 0.04f  // 边距

        cellSize = (viewSize - 2 * padding) / (board.size - 1)
        boardOffset = padding
        stoneRadius = cellSize * 0.45f
        moveNumberPaint.textSize = stoneRadius * 0.8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val board = gameBoard ?: return

        if (cellSize == 0f) calculateDimensions()

        drawBoard(canvas, board)
        drawStarPoints(canvas, board)
        drawStones(canvas, board)
        drawLastMove(canvas, board)
        drawWinLine(canvas, board)
    }

    /**
     * 绘制棋盘
     */
    private fun drawBoard(canvas: Canvas, board: GameBoard) {
        // 棋盘背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardPaint)

        // 绘制网格线
        for (i in 0 until board.size) {
            val pos = boardOffset + i * cellSize

            // 横线
            canvas.drawLine(
                boardOffset, pos,
                boardOffset + (board.size - 1) * cellSize, pos,
                linePaint
            )

            // 竖线
            canvas.drawLine(
                pos, boardOffset,
                pos, boardOffset + (board.size - 1) * cellSize,
                linePaint
            )
        }
    }

    /**
     * 绘制星位（天元和四个角星）
     */
    private fun drawStarPoints(canvas: Canvas, board: GameBoard) {
        if (board.size != 15) return

        val starPoints = listOf(
            Pair(7, 7),   // 天元
            Pair(3, 3), Pair(3, 11),   // 左上、右上
            Pair(11, 3), Pair(11, 11)  // 左下、右下
        )

        val radius = cellSize * 0.12f

        for ((row, col) in starPoints) {
            val x = boardOffset + col * cellSize
            val y = boardOffset + row * cellSize
            canvas.drawCircle(x, y, radius, starPointPaint)
        }
    }

    /**
     * 绘制棋子
     */
    private fun drawStones(canvas: Canvas, board: GameBoard) {
        for (row in 0 until board.size) {
            for (col in 0 until board.size) {
                val stone = board.getStone(row, col)
                if (stone != Stone.EMPTY) {
                    drawStone(canvas, row, col, stone, board)
                }
            }
        }
    }

    /**
     * 绘制单个棋子
     */
    private fun drawStone(canvas: Canvas, row: Int, col: Int, stone: Stone, board: GameBoard) {
        val x = boardOffset + col * cellSize
        val y = boardOffset + row * cellSize

        when (stone) {
            Stone.BLACK -> {
                canvas.drawCircle(x, y, stoneRadius, blackStonePaint)
            }
            Stone.WHITE -> {
                canvas.drawCircle(x, y, stoneRadius, whiteStonePaint)
                canvas.drawCircle(x, y, stoneRadius, whiteStoneStrokePaint)
            }
            else -> {}
        }

        // 绘制落子序号
        if (showMoveNumbers) {
            val move = board.moveHistory.find { it.position.row == row && it.position.col == col }
            if (move != null) {
                moveNumberPaint.color = if (stone == Stone.BLACK) Color.WHITE else Color.BLACK
                val textY = y - (moveNumberPaint.descent() + moveNumberPaint.ascent()) / 2
                canvas.drawText(move.moveNumber.toString(), x, textY, moveNumberPaint)
            }
        }
    }

    /**
     * 绘制最后一手标记
     */
    private fun drawLastMove(canvas: Canvas, board: GameBoard) {
        val lastMove = board.moveHistory.lastOrNull() ?: return

        val x = boardOffset + lastMove.position.col * cellSize
        val y = boardOffset + lastMove.position.row * cellSize
        val markRadius = stoneRadius * 0.3f

        canvas.drawCircle(x, y, markRadius, lastMovePaint)
    }

    /**
     * 绘制获胜连线
     */
    private fun drawWinLine(canvas: Canvas, board: GameBoard) {
        val winInfo = board.winInfo ?: return
        if (winInfo.winningPositions.size < 2) return

        val sorted = winInfo.winningPositions.sortedWith(compareBy({ it.row }, { it.col }))
        val first = sorted.first()
        val last = sorted.last()

        val startX = boardOffset + first.col * cellSize
        val startY = boardOffset + first.row * cellSize
        val endX = boardOffset + last.col * cellSize
        val endY = boardOffset + last.row * cellSize

        canvas.drawLine(startX, startY, endX, endY, winLinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val board = gameBoard ?: return true

            val col = ((event.x - boardOffset + cellSize / 2) / cellSize).toInt()
            val row = ((event.y - boardOffset + cellSize / 2) / cellSize).toInt()

            if (row in 0 until board.size && col in 0 until board.size) {
                onCellClickListener?.invoke(row, col)
            }
        }
        return true
    }

    /**
     * 刷新棋盘
     */
    fun refresh() {
        invalidate()
    }
}
