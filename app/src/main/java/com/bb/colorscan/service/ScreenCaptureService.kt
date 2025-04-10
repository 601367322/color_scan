package com.bb.colorscan.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bb.colorscan.MainActivity
import com.bb.colorscan.R
import com.bb.colorscan.data.SettingsRepository
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    private val handlerThread: HandlerThread by lazy {
        val thread = HandlerThread("ScreenCaptureThread", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        thread
    }

    private val handler: Handler by lazy {
        Handler(handlerThread.looper)
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayMetrics = DisplayMetrics()
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private var targetRgb: Triple<Int, Int, Int>? = null
    private lateinit var settingsRepository: SettingsRepository

    // 图像分析开关状态
    private val isAnalysisEnabled = AtomicBoolean(true)

    // 倒计时相关
    private val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var countdownTask: ScheduledFuture<*>? = null
    private var countdownSeconds = 0
    private var countdownRunning = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null

    // 悬浮窗相关
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var countdownTextView: TextView? = null
    private var analysisButton: ImageButton? = null
    private var countdownButton: ImageButton? = null

    // 图像可用监听器
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        if (!isRunning.get()) return@OnImageAvailableListener
        if (!isAnalysisEnabled.get()) {
            reader.acquireLatestImage()?.close()
            return@OnImageAvailableListener
        }
        executor.execute {
            try {
                val image = reader.acquireLatestImage()
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
                Log.e(TAG, "Error processing image", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 获取屏幕尺寸
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        // 初始化设置仓库
        settingsRepository = SettingsRepository(applicationContext)

        // 初始化悬浮窗
        this.windowManager = windowManager
        initFloatingWindow()
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
     * 初始化悬浮窗
     */
    private fun initFloatingWindow() {
        // 创建悬浮窗布局
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_control_view, null)

        // 获取控件引用
        analysisButton = floatingView?.findViewById(R.id.analysisButton)
        countdownButton = floatingView?.findViewById(R.id.countdownButton)
        countdownTextView = floatingView?.findViewById(R.id.countdownText)

        // 设置布局参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        // 初始位置
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        // 设置按钮点击监听
        setupButtonListeners()

        // 拖动处理
        setupDragHandler()

        // 添加到窗口管理器
        windowManager?.addView(floatingView, params)
    }

    /**
     * 设置按钮点击事件
     */
    private fun setupButtonListeners() {
        // 图像分析开关
        analysisButton?.setOnClickListener {
            val newState = !isAnalysisEnabled.get()
            isAnalysisEnabled.set(newState)
            updateAnalysisButtonAppearance(newState)
        }

        // 倒计时按钮
        countdownButton?.setOnClickListener {
            if (countdownRunning.get()) {
                // 取消当前倒计时
                stopCountdown()
            } else {
                // 开始新的倒计时
                startCountdown()
            }
        }
    }

    /**
     * 更新分析按钮外观
     */
    private fun updateAnalysisButtonAppearance(enabled: Boolean) {
        analysisButton?.setImageResource(
            if (enabled) R.drawable.ic_analysis_enabled
            else R.drawable.ic_analysis_disabled
        )
        // TODO: 替换为实际资源ID
    }

    /**
     * 更新倒计时按钮外观
     */
    private fun updateCountdownButtonAppearance(running: Boolean) {
        countdownButton?.setImageResource(
            if (running) R.drawable.ic_countdown_running
            else R.drawable.ic_countdown_start
        )
        // TODO: 替换为实际资源ID
    }

    /**
     * 设置拖动处理
     */
    private fun setupDragHandler() {
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 记录初始位置
                        val params = floatingView?.layoutParams as WindowManager.LayoutParams
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // 计算移动距离并更新位置
                        val params = floatingView?.layoutParams as WindowManager.LayoutParams
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 开始倒计时
     */
    private fun startCountdown() {
        if (countdownRunning.get()) return

        // 从设置中获取倒计时时长
        countdownSeconds = settingsRepository.getCountdownDuration().toIntOrNull() ?: 5

        // 显示倒计时文本
        countdownTextView?.visibility = View.VISIBLE
        countdownTextView?.text = countdownSeconds.toString()

        // 更新按钮状态
        countdownRunning.set(true)
        updateCountdownButtonAppearance(true)

        // 创建倒计时任务
        countdownTask = scheduledExecutor.scheduleAtFixedRate({
            countdownSeconds--

            // 更新UI需要在主线程执行
            Handler(Looper.getMainLooper()).post {
                countdownTextView?.text = countdownSeconds.toString()
            }

            if (countdownSeconds <= 0) {
                // 倒计时结束
                stopCountdown()
                playAudio()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * 停止倒计时
     */
    private fun stopCountdown() {
        countdownTask?.cancel(false)
        countdownTask = null
        countdownRunning.set(false)

        // 更新UI需要在主线程执行
        Handler(Looper.getMainLooper()).post {
            countdownTextView?.visibility = View.INVISIBLE
            updateCountdownButtonAppearance(false)
        }
    }

    /**
     * 播放音频
     */
    private fun playAudio() {
        // 获取音频文件URI
        val audioUriString = settingsRepository.getCountdownAudioPath()
        if (audioUriString.isBlank()) return

        try {
            // 创建媒体播放器
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, Uri.parse(audioUriString))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }

    /**
     * 解析RGB字符串为Triple对象
     */
    private fun parseRgbString(rgbString: String) {
        try {
            val rgbParts = rgbString.split(",").map { it.trim().toInt() }
            if (rgbParts.size == 3) {
                targetRgb = Triple(rgbParts[0], rgbParts[1], rgbParts[2])
                Log.d(
                    TAG,
                    "Target RGB: ${targetRgb?.first}, ${targetRgb?.second}, ${targetRgb?.third}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RGB: $rgbString", e)
            targetRgb = Triple(255, 0, 0) // 默认红色
        }
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // 设置ImageReader
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        ).apply {
            // 设置图像可用监听器
            setOnImageAvailableListener(imageAvailableListener, handler)
        }

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
        isRunning.set(true)
        Log.d(TAG, "Screen capture started with image listener")
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
                        Math.abs(pixelB - b) < COLOR_TOLERANCE
                    ) {
                        found = true
                        Log.d(TAG, "Found target color at ($x, $y): RGB($pixelR, $pixelG, $pixelB)")
                        // 发送目标颜色被找到的广播
                        sendColorFoundBroadcast(x, y)

                        // 播放监控音频
                        playMonitorAudio()

                        break
                    }
                }

                if (found) break
            }
        }
    }

    /**
     * 播放监控音频
     */
    private fun playMonitorAudio() {
        // 获取音频文件URI
        val audioUriString = settingsRepository.getMonitorAudioPath()
        if (audioUriString.isBlank()) return

        try {
            // 创建媒体播放器
            val player = MediaPlayer().apply {
                setDataSource(applicationContext, Uri.parse(audioUriString))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing monitor audio", e)
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
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

        // 移除监听器
        imageReader?.setOnImageAvailableListener(null, null)

        // 停止倒计时
        stopCountdown()
        scheduledExecutor.shutdown()

        // 释放媒体播放器
        mediaPlayer?.release()
        mediaPlayer = null

        // 移除悬浮窗
        windowManager?.removeView(floatingView)

        // 释放资源
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread.quitSafely()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
} 