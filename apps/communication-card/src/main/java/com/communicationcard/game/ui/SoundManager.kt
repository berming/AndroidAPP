package com.communicationcard.game.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 游戏音效和震动管理器
 */
class SoundManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var vibrator: Vibrator? = null

    // Sound IDs (placeholder - will use vibration as feedback)
    private var isInitialized = false

    // Sound/vibration enabled flags
    var isSoundEnabled = true
    var isVibrationEnabled = true

    init {
        initSoundPool()
        initVibrator()
    }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()

        isInitialized = true
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * 播放出牌音效
     */
    fun playCardSound() {
        if (!isSoundEnabled) return
        vibrateShort()
    }

    /**
     * 播放炸弹音效
     */
    fun playBombSound() {
        if (!isSoundEnabled) return
        vibrateLong()
    }

    /**
     * 播放过牌音效
     */
    fun playPassSound() {
        if (!isSoundEnabled) return
        vibrateQuick()
    }

    /**
     * 播放赢轮音效
     */
    fun playWinRoundSound() {
        if (!isSoundEnabled) return
        vibratePattern(longArrayOf(0, 50, 50, 100))
    }

    /**
     * 播放游戏胜利音效
     */
    fun playGameWinSound() {
        if (!isSoundEnabled) return
        vibratePattern(longArrayOf(0, 100, 100, 100, 100, 200))
    }

    /**
     * 播放游戏失败音效
     */
    fun playGameLoseSound() {
        if (!isSoundEnabled) return
        vibratePattern(longArrayOf(0, 200, 100, 200))
    }

    /**
     * 短震动
     */
    private fun vibrateShort() {
        if (!isVibrationEnabled) return
        vibrate(30)
    }

    /**
     * 长震动
     */
    private fun vibrateLong() {
        if (!isVibrationEnabled) return
        vibrate(100)
    }

    /**
     * 快速震动
     */
    private fun vibrateQuick() {
        if (!isVibrationEnabled) return
        vibrate(15)
    }

    private fun vibrate(durationMs: Long) {
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
