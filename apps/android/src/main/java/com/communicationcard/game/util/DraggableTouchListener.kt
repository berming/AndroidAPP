package com.communicationcard.game.util

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 可拖动 + 可点击的 OnTouchListener。
 * - 短按未移动 → 触发 onClick
 * - 移动超过 touchSlop → 进入拖动模式，更新 view 的 x/y
 * - 拖动后会限制在父 View 范围内
 */
class DraggableTouchListener(
    private val onClick: () -> Unit
) : View.OnTouchListener {

    private var downRawX = 0f
    private var downRawY = 0f
    private var dX = 0f
    private var dY = 0f
    private var isDragging = false
    private var touchSlop = 0

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val parent = view.parent as? View ?: return false
        if (touchSlop == 0) {
            touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dX = view.x - event.rawX
                dY = view.y - event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    val newX = (event.rawX + dX).coerceIn(
                        0f,
                        (parent.width - view.width).toFloat()
                    )
                    val newY = (event.rawY + dY).coerceIn(
                        0f,
                        (parent.height - view.height).toFloat()
                    )
                    view.x = newX
                    view.y = newY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    view.performClick()
                    onClick()
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }
}
