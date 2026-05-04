package com.communicationcard.game.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.communicationcard.game.R
import com.communicationcard.game.util.DebugLogManager

/**
 * 调试日志查看器
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnCopy: Button
    private lateinit var btnClear: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_log_viewer)

        initViews()
        setupListeners()
        refreshLogs()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun initViews() {
        tvLogs = findViewById(R.id.tvLogs)
        scrollView = findViewById(R.id.scrollView)
        btnCopy = findViewById(R.id.btnCopy)
        btnClear = findViewById(R.id.btnClear)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun setupListeners() {
        btnCopy.setOnClickListener {
            copyLogs()
        }

        btnClear.setOnClickListener {
            DebugLogManager.clear()
            refreshLogs()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        btnRefresh.setOnClickListener {
            refreshLogs()
            Toast.makeText(this, "日志已刷新", Toast.LENGTH_SHORT).show()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun refreshLogs() {
        val logs = DebugLogManager.getFullLogsFromFile()
        tvLogs.text = if (logs.isEmpty()) "暂无日志" else logs
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun copyLogs() {
        val logs = DebugLogManager.getFullLogsFromFile()
        if (logs.isEmpty()) {
            Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("debug_logs", logs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
