package com.bb.colorscan.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bb.colorscan.MainActivity
import com.bb.colorscan.data.SettingsRepository
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕捕获服务，负责后台截取屏幕并分析颜色
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        
        // 服务启动Intent的键
        const val RESULT_CODE = "RESULT_CODE"
        const val DATA = "DATA"
        const val COLOR_RGB = "COLOR_RGB"

        // 默认的截屏间隔 (毫秒)
        private const val DEFAULT_CAPTURE_INTERVAL = 500L
        
        // 颜色匹配容差
        private const val COLOR_TOLERANCE = 20
    }

    private val serviceHandler: ServiceHandler by lazy {
        val thread = HandlerThread("ScreenCaptureThread", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        
        ServiceHandler(thread.looper, WeakReference(this))
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayMetrics = DisplayMetrics()
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private var targetRgb: Triple<Int, Int, Int>? = null
    private lateinit var settingsRepository: SettingsRepository

    // 用于定期截屏的Handler
    private class ServiceHandler(looper: Looper, private val service: WeakReference<ScreenCaptureService>) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            service.get()?.captureScreen()
            service.get()?.let {
                if (it.isRunning.get()) {
                    sendEmptyMessageDelayed(0, DEFAULT_CAPTURE_INTERVAL)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 获取屏幕尺寸
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        // 初始化设置仓库
        settingsRepository = SettingsRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 从Intent中获取MediaProjection数据
        val resultCode = intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(DATA)
        
        // 从Intent或Repository获取RGB值
        val rgbString = intent.getStringExtra(COLOR_RGB) 
            ?: settingsRepository.getCurrentRgb()
        
        // 解析RGB值
        parseRgbString(rgbString)

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Missing or invalid data for media projection")
            stopSelf()
            return START_NOT_STICKY
        }

        // 初始化服务
        startForeground(NOTIFICATION_ID, createNotification())
        initMediaProjection(resultCode, data)
        startCapturing()

        return START_REDELIVER_INTENT
    }
    
    /**
     * 解析RGB字符串为Triple对象
     */
    private fun parseRgbString(rgbString: String) {
        try {
            val rgbParts = rgbString.split(",").map { it.trim().toInt() }
            if (rgbParts.size == 3) {
                targetRgb = Triple(rgbParts[0], rgbParts[1], rgbParts[2])
                Log.d(TAG, "Target RGB: ${targetRgb?.first}, ${targetRgb?.second}, ${targetRgb?.third}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RGB: $rgbString", e)
            targetRgb = Triple(255, 0, 0) // 默认红色
        }
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        // 设置ImageReader
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        
        // 创建虚拟显示
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun startCapturing() {
        if (!isRunning.getAndSet(true)) {
            serviceHandler.sendEmptyMessage(0)
        }
    }

    private fun captureScreen() {
        if (!isRunning.get() || imageReader == null) {
            return
        }

        executor.execute {
            try {
                // 获取最新的图像
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    // 将图像转换为Bitmap
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    
                    // 创建Bitmap
                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    // 分析图像
                    analyzeImage(bitmap)
                    
                    // 释放资源
                    image.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen", e)
            }
        }
    }

    private fun analyzeImage(bitmap: Bitmap) {
        // 在这里可以实现颜色分析逻辑
        targetRgb?.let { (r, g, b) ->
            // 简单示例: 扫描图像中心区域查找目标颜色
            val centerX = bitmap.width / 2
            val centerY = bitmap.height / 2
            val scanRadius = 100
            
            var found = false
            
            // 扫描中心区域
            for (x in centerX - scanRadius until centerX + scanRadius) {
                if (x < 0 || x >= bitmap.width) continue
                
                for (y in centerY - scanRadius until centerY + scanRadius) {
                    if (y < 0 || y >= bitmap.height) continue
                    
                    val pixel = bitmap.getPixel(x, y)
                    val pixelR = (pixel shr 16) and 0xff
                    val pixelG = (pixel shr 8) and 0xff
                    val pixelB = pixel and 0xff
                    
                    // 容差匹配，允许一定程度的颜色偏差
                    if (Math.abs(pixelR - r) < COLOR_TOLERANCE && 
                        Math.abs(pixelG - g) < COLOR_TOLERANCE && 
                        Math.abs(pixelB - b) < COLOR_TOLERANCE) {
                        found = true
                        Log.d(TAG, "Found target color at ($x, $y): RGB($pixelR, $pixelG, $pixelB)")
                        // 发送目标颜色被找到的广播
                        sendColorFoundBroadcast(x, y)
                        break
                    }
                }
                
                if (found) break
            }
        }
    }

    private fun sendColorFoundBroadcast(x: Int, y: Int) {
        val intent = Intent("com.bb.colorscan.COLOR_FOUND")
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        // 创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕捕获",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // 创建通知
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("颜色扫描器正在运行")
            .setContentText("正在监控屏幕颜色变化")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        // 停止服务
        isRunning.set(false)
        serviceHandler.removeCallbacksAndMessages(null)
        
        // 释放资源
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
} 