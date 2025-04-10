package com.bb.colorscan

import android.app.Application
import com.bb.colorscan.data.SettingsRepository

/**
 * 应用程序类，用于提供全局单例
 */
class ColorScanApplication : Application() {
    // 延迟初始化的设置仓库，用于全局访问
    val settingsRepository: SettingsRepository by lazy { 
        SettingsRepository(applicationContext)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    
    companion object {
        private var instance: ColorScanApplication? = null
        
        /**
         * 获取应用实例
         */
        fun getInstance(): ColorScanApplication {
            return instance!!
        }
    }
} 