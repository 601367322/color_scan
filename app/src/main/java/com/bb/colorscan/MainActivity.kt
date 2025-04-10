package com.bb.colorscan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.bb.colorscan.ui.SettingsScreen
import com.bb.colorscan.ui.theme.ColorScanTheme
import com.bb.colorscan.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: SettingsViewModel
    private var colorReceiver: BroadcastReceiver? = null
    
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 使用WindowCompat替代enableEdgeToEdge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 初始化ViewModel
        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        
        // 初始化权限请求
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                // 权限已获取，请求MediaProjection权限
                requestMediaProjection()
            } else {
                Toast.makeText(this, "需要通知权限才能启动录屏服务", Toast.LENGTH_LONG).show()
            }
        }
        
        // 注册颜色检测广播接收器
        registerColorReceiver()
        
        setContent {
            ColorScanTheme {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 注销广播接收器
        unregisterColorReceiver()
    }
    
    private fun registerColorReceiver() {
        colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.bb.colorscan.COLOR_FOUND") {
                    val x = intent.getIntExtra("x", 0)
                    val y = intent.getIntExtra("y", 0)
                    // 处理颜色检测事件
                    Toast.makeText(this@MainActivity, "在坐标($x, $y)找到目标颜色!", Toast.LENGTH_SHORT).show()
                    // 这里可以播放音频提示等
                }
            }
        }
        
        registerReceiver(colorReceiver, IntentFilter("com.bb.colorscan.COLOR_FOUND"))
    }
    
    private fun unregisterColorReceiver() {
        colorReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // 忽略未注册的接收器异常
            }
        }
    }
    
    /**
     * 检查权限并请求录屏
     */
    fun checkPermissionAndStartCapture() {
        // 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return
            }
        }
        
        // 如果已经有录屏权限，直接启动服务
        if (viewModel.mediaProjectionResultCode != 0 && viewModel.mediaProjectionResultData != null) {
            viewModel.startScreenCapture()
            return
        }
        
        // 请求屏幕捕获权限
        requestMediaProjection()
    }
    
    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // 保存录屏权限结果到ViewModel
                viewModel.mediaProjectionResultCode = resultCode
                viewModel.mediaProjectionResultData = data
                
                // 启动服务
                viewModel.startScreenCapture()
            } else {
                // 用户取消了屏幕捕获权限
                Toast.makeText(this, "需要屏幕捕获权限才能检测颜色", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1
    }
}