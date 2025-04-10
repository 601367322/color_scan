package com.bb.colorscan.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置数据仓库，负责处理应用设置的存储和获取
 */
class SettingsRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "ColorScanSettings"
        private const val KEY_MONITOR_AUDIO = "monitor_audio_path"
        private const val KEY_COUNTDOWN_AUDIO = "countdown_audio_path"
        private const val KEY_COUNTDOWN_DURATION = "countdown_duration"
        private const val KEY_TARGET_RGB = "target_rgb"
        
        // 默认值
        private const val DEFAULT_COUNTDOWN_DURATION = "5"
        private const val DEFAULT_RGB = "13,22,33"
    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 使用Flow存储设置状态，便于观察变化
    private val _monitorAudioPath = MutableStateFlow(
        sharedPrefs.getString(KEY_MONITOR_AUDIO, "") ?: ""
    )
    private val _countdownAudioPath = MutableStateFlow(
        sharedPrefs.getString(KEY_COUNTDOWN_AUDIO, "") ?: ""
    )
    private val _countdownDuration = MutableStateFlow(
        sharedPrefs.getString(KEY_COUNTDOWN_DURATION, DEFAULT_COUNTDOWN_DURATION) 
            ?: DEFAULT_COUNTDOWN_DURATION
    )
    private val _targetRgb = MutableStateFlow(
        sharedPrefs.getString(KEY_TARGET_RGB, DEFAULT_RGB) ?: DEFAULT_RGB
    )
    
    // 公开不可变的Flow以供观察
    val monitorAudioPath: Flow<String> = _monitorAudioPath.asStateFlow()
    val countdownAudioPath: Flow<String> = _countdownAudioPath.asStateFlow()
    val countdownDuration: Flow<String> = _countdownDuration.asStateFlow()
    val targetRgb: Flow<String> = _targetRgb.asStateFlow()
    
    /**
     * 保存监控音频路径
     */
    fun saveMonitorAudioPath(path: String) {
        sharedPrefs.edit().putString(KEY_MONITOR_AUDIO, path).apply()
        _monitorAudioPath.value = path
    }
    
    /**
     * 保存倒计时音频路径
     */
    fun saveCountdownAudioPath(path: String) {
        sharedPrefs.edit().putString(KEY_COUNTDOWN_AUDIO, path).apply()
        _countdownAudioPath.value = path
    }
    
    /**
     * 保存倒计时时长
     */
    fun saveCountdownDuration(duration: String) {
        sharedPrefs.edit().putString(KEY_COUNTDOWN_DURATION, duration).apply()
        _countdownDuration.value = duration
    }
    
    /**
     * 保存目标RGB颜色值
     */
    fun saveTargetRgb(rgb: String) {
        sharedPrefs.edit().putString(KEY_TARGET_RGB, rgb).apply()
        _targetRgb.value = rgb
    }
    
    /**
     * 获取最新的RGB值（非Flow形式，用于服务等）
     */
    fun getCurrentRgb(): String {
        return sharedPrefs.getString(KEY_TARGET_RGB, DEFAULT_RGB) ?: DEFAULT_RGB
    }
    
    /**
     * 获取监控音频路径（非Flow形式，用于服务等）
     */
    fun getMonitorAudioPath(): String {
        return sharedPrefs.getString(KEY_MONITOR_AUDIO, "") ?: ""
    }
    
    /**
     * 获取倒计时音频路径（非Flow形式，用于服务等）
     */
    fun getCountdownAudioPath(): String {
        return sharedPrefs.getString(KEY_COUNTDOWN_AUDIO, "") ?: ""
    }
    
    /**
     * 获取倒计时时长（非Flow形式，用于服务等）
     */
    fun getCountdownDuration(): String {
        return sharedPrefs.getString(KEY_COUNTDOWN_DURATION, DEFAULT_COUNTDOWN_DURATION) 
            ?: DEFAULT_COUNTDOWN_DURATION
    }
} 