package com.communicationcard.game.ui

import android.app.Activity
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/**
 * Stage G：游戏内"更多…"菜单（与 Web 端 [com.communicationcard.game.web.ui.MoreMenuDialog]
 * 对称；附录 A.2）。
 *
 * 由 [GameActivity] / [OnlineGameActivity] 的 `btnMore.setOnClickListener` 调起；
 * 用 `AlertDialog` overlay，关闭后回到原游戏屏幕（不退出 Activity）。
 *
 * 5 项菜单：
 *
 * 1. 查看出牌记录 → [onShowHistory] 回调（复用既有 `showHistory()` History Overlay）
 * 2. 设置昵称       → 子 dialog（[showNicknameDialog]）；保存写入
 *                    `GamePreferences.lastPlayerName`（与 MainActivity 同一持久化路径）
 * 3. AI 出牌速度    → 子 dialog（[showSpeedDialog]）；保存写入 `GamePreferences.gameSpeed`
 *                    （与 SettingsActivity 同一持久化路径）
 * 4. 离开 / 返回主界面 → [onLeave] 回调（多人 = 离开房间；单机 = 返回主界面）
 * 5. 暂离 / 我回来了   → [onToggleAfk] 回调（仅多人模式显示）
 *
 * 用户明示："昵称""AI 速度"现有入口（MainActivity / SettingsActivity）保持原位，
 * 本菜单只是**新增一个游戏内入口**，不迁移、不移除原入口。
 */
object MoreMenuDialog {

    /**
     * 弹出"更多…"主菜单。
     *
     * @param activity 调用方 Activity（用于 AlertDialog context + 子 dialog inflate）
     * @param preferences 共享偏好（昵称 / AI 速度的持久化路径）
     * @param isMultiplayer true = 多人模式；显示"离开房间"+"暂离"项
     * @param imAiSubstitute 仅多人有效；true = 当前已被 AI 接管（暂离中），菜单第 5 项
     *                       文字为"我回来了（取消暂离）"，否则为"暂离（AI 接管）"
     * @param onShowHistory 用户点"查看出牌记录"时调用
     * @param onLeave 用户点"离开房间"/"返回主界面"时调用
     * @param onToggleAfk 用户点"暂离"/"我回来了"时调用（仅多人）
     * @param onNicknameSaved 昵称保存后回调（默认无操作；调用方可在此刷新 UI 上的玩家名）
     * @param onSpeedChanged AI 速度改后回调（默认无操作；调用方可在此重新计算 aiDelayMs）
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        preferences: GamePreferences,
        isMultiplayer: Boolean,
        imAiSubstitute: Boolean,
        onShowHistory: () -> Unit,
        onLeave: () -> Unit,
        onToggleAfk: () -> Unit = {},
        onNicknameSaved: (String) -> Unit = {},
        onSpeedChanged: (Int) -> Unit = {},
    ) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        items.add("查看出牌记录")
        actions.add { onShowHistory() }

        items.add("设置昵称")
        actions.add { showNicknameDialog(activity, preferences, onNicknameSaved) }

        items.add("AI 出牌速度")
        actions.add { showSpeedDialog(activity, preferences, onSpeedChanged) }

        items.add(if (isMultiplayer) "离开房间" else "返回主界面")
        actions.add { onLeave() }

        if (isMultiplayer) {
            items.add(if (imAiSubstitute) "我回来了（取消暂离）" else "暂离（AI 接管）")
            actions.add { onToggleAfk() }
        }

        AlertDialog.Builder(activity)
            .setTitle("更多")
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 设置昵称子 dialog；保存写 [GamePreferences.lastPlayerName]。 */
    private fun showNicknameDialog(
        activity: Activity,
        preferences: GamePreferences,
        onSaved: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(preferences.lastPlayerName)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        // 加点 padding 让输入框不贴边
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("设置昵称")
            .setMessage("改名后立即保存到本机；与 SettingsActivity 共用同一持久化项。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != preferences.lastPlayerName) {
                    preferences.lastPlayerName = newName
                    onSaved(newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** AI 出牌速度子 dialog；保存写 [GamePreferences.gameSpeed]。 */
    private fun showSpeedDialog(
        activity: Activity,
        preferences: GamePreferences,
        onChanged: (Int) -> Unit,
    ) {
        val labels = arrayOf(
            "慢 (1000 ms / 步)",
            "正常 (400 ms / 步)",
            "极快 (50 ms / 步)",
        )
        val current = preferences.gameSpeed.coerceIn(0, 2)
        AlertDialog.Builder(activity)
            .setTitle("AI 出牌速度")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                if (which != preferences.gameSpeed) {
                    preferences.gameSpeed = which
                    onChanged(which)
                }
                dialog.dismiss()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
