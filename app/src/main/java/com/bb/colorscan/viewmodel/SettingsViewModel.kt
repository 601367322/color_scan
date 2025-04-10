package com.bb.colorscan.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bb.colorscan.data.SettingsRepository
import com.bb.colorscan.service.ScreenCaptureService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.provider.Settings
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Build

/**
 * 设置页面的ViewModel，负责处理设置相关的业务逻辑
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application.applicationContext)
    
    // UI状态
    private val _monitorAudioPath = MutableStateFlow("")
    private val _countdownAudioPath = MutableStateFlow("")
    private val _countdownDuration = MutableStateFlow("5")
    private val _targetRgb = MutableStateFlow("255,0,0")
    private val _isRecording = MutableStateFlow(false)
    
    // 公开的状态
    val monitorAudioPath: StateFlow<String> = _monitorAudioPath.asStateFlow()
    val countdownAudioPath: StateFlow<String> = _countdownAudioPath.asStateFlow()
    val countdownDuration: StateFlow<String> = _countdownDuration.asStateFlow()
    val targetRgb: StateFlow<String> = _targetRgb.asStateFlow()
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    // 媒体投影数据，供录屏功能使用
    var mediaProjectionResultCode = 0
    var mediaProjectionResultData: Intent? = null
    
    // 防抖动变量
    private var monitorAudioPathSaveJob: Job? = null
    private var countdownAudioPathSaveJob: Job? = null
    private var countdownDurationSaveJob: Job? = null
    private var targetRgbSaveJob: Job? = null
    
    // 防抖动延迟（毫秒）
    private val saveDebounceTime = 500L
    
    // 用于通知UI显示权限请求对话框的状态
    private val _showOverlayPermissionDialog = MutableStateFlow(false)
    val showOverlayPermissionDialog: StateFlow<Boolean> = _showOverlayPermissionDialog.asStateFlow()
    
    init {
        // 初始化时从Repository加载数据
        viewModelScope.launch {
            repository.monitorAudioPath.collect {
                _monitorAudioPath.value = it
            }
        }
        
        viewModelScope.launch {
            repository.countdownAudioPath.collect {
                _countdownAudioPath.value = it
            }
        }
        
        viewModelScope.launch {
            repository.countdownDuration.collect {
                _countdownDuration.value = it
            }
        }
        
        viewModelScope.launch {
            repository.targetRgb.collect {
                _targetRgb.value = it
            }
        }
    }
    
    /**
     * 更新监控音频路径
     */
    fun updateMonitorAudioPath(uri: Uri) {
        val path = getFilePathFromUri(uri)
        _monitorAudioPath.value = path
    }
    
    /**
     * 更新倒计时音频路径
     */
    fun updateCountdownAudioPath(uri: Uri) {
        val path = getFilePathFromUri(uri)
        _countdownAudioPath.value = path
    }
    
    /**
     * 更新监控音频路径（直接设置字符串）
     */
    fun updateMonitorAudioPath(path: String) {
        _monitorAudioPath.value = path
        // 自动保存（带防抖动）
        debounceSave(monitorAudioPathSaveJob) {
            saveMonitorAudioSettings()
        }
    }
    
    /**
     * 更新倒计时音频路径（直接设置字符串）
     */
    fun updateCountdownAudioPath(path: String) {
        _countdownAudioPath.value = path
        // 自动保存（带防抖动）
        debounceSave(countdownAudioPathSaveJob) {
            saveCountdownAudioSettings()
        }
    }
    
    /**
     * 更新倒计时时长
     */
    fun updateCountdownDuration(duration: String) {
        _countdownDuration.value = duration
        // 自动保存（带防抖动）
        debounceSave(countdownDurationSaveJob) {
            saveCountdownDurationSettings()
        }
    }
    
    /**
     * 更新目标RGB值
     */
    fun updateTargetRgb(rgb: String) {
        _targetRgb.value = rgb
        // 自动保存（带防抖动）
        debounceSave(targetRgbSaveJob) {
            saveTargetRgbSettings()
        }
    }
    
    /**
     * 防抖动保存
     * @param currentJob 当前运行的Job（如果有）
     * @param saveOperation 执行保存操作的函数
     */
    private fun debounceSave(currentJob: Job?, saveOperation: () -> Unit) {
        // 取消当前正在运行的Job
        currentJob?.cancel()
        
        // 创建新的Job并启动延迟保存
        viewModelScope.launch {
            delay(saveDebounceTime)
            saveOperation()
        }
    }
    
    /**
     * 保存监控音频设置
     */
    fun saveMonitorAudioSettings() {
        repository.saveMonitorAudioPath(_monitorAudioPath.value)
    }
    
    /**
     * 保存倒计时音频设置
     */
    fun saveCountdownAudioSettings() {
        repository.saveCountdownAudioPath(_countdownAudioPath.value)
    }
    
    /**
     * 保存倒计时时长设置
     */
    fun saveCountdownDurationSettings() {
        repository.saveCountdownDuration(_countdownDuration.value)
    }
    
    /**
     * 保存目标RGB值设置
     */
    fun saveTargetRgbSettings() {
        repository.saveTargetRgb(_targetRgb.value)
    }
    
    /**
     * 开始录屏服务
     */
    fun startScreenCapture() {
        if (mediaProjectionResultCode != 0 && mediaProjectionResultData != null) {
            // 检查是否有"在其他应用上显示"的权限
            if (!hasOverlayPermission()) {
                // 如果没有权限，通知UI层显示权限请求对话框
                _showOverlayPermissionDialog.value = true
                return
            }
            
            val context = getApplication<Application>().applicationContext
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.RESULT_CODE, mediaProjectionResultCode)
                putExtra(ScreenCaptureService.DATA, mediaProjectionResultData)
                putExtra(ScreenCaptureService.COLOR_RGB, _targetRgb.value)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            _isRecording.value = true
        }
    }
    
    /**
     * 停止录屏服务
     */
    fun stopScreenCapture() {
        val context = getApplication<Application>().applicationContext
        val serviceIntent = Intent(context, ScreenCaptureService::class.java)
        context.stopService(serviceIntent)
        _isRecording.value = false
    }
    
    /**
     * 检查是否有"在其他应用上显示"的权限
     */
    private fun hasOverlayPermission(): Boolean {
        val context = getApplication<Application>().applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // 对于低于Android 6.0的版本，默认返回true
        }
    }
    
    /**
     * 打开系统设置页面，让用户手动授予"在其他应用上显示"的权限
     */
    fun requestOverlayPermission() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        _showOverlayPermissionDialog.value = false
    }
    
    /**
     * 关闭权限请求对话框
     */
    fun dismissOverlayPermissionDialog() {
        _showOverlayPermissionDialog.value = false
    }
    
    /**
     * 切换录屏状态
     */
    fun toggleRecording(shouldRecord: Boolean) {
        if (shouldRecord) {
            startScreenCapture()
        } else {
            stopScreenCapture()
        }
    }
    
    /**
     * 从Uri获取文件路径
     */
    private fun getFilePathFromUri(uri: Uri): String {
        // 简单实现，仅返回Uri的字符串形式
        // 在实际应用中，这里需要更复杂的逻辑来从ContentResolver获取实际文件路径
        return uri.toString()
    }
} 