package com.communicationcard.game.ui.multiplayer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.communicationcard.game.R

/**
 * 多人游戏界面
 * TODO: Phase 3 实现多人游戏同步
 */
class OnlineGameActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        Toast.makeText(this, "多人游戏功能正在开发中...", Toast.LENGTH_LONG).show()

        // TODO: 实现多人游戏逻辑
        // 1. 获取共享的NetworkManager和RoomManager
        // 2. 创建MultiplayerGameEngine
        // 3. 监听游戏事件
        // 4. 同步游戏状态
    }
}
