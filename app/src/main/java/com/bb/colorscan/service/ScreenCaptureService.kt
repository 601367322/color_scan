package com.bb.colorscan.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bb.colorscan.MainActivity
import com.bb.colorscan.R
import com.bb.colorscan.data.SettingsRepository
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val COLOR_TOLERANCE = 5
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

    // 保存实际可用高度（减去状态栏和导航栏）
    private var screenHeight: Int = 0

    // 图像分析开关状态
    private val isAnalysisEnabled = AtomicBoolean(true)

    // 倒计时相关
    private val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var countdownTask: ScheduledFuture<*>? = null
    private var countdownSeconds = 0
    private var countdownRunning = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null

    // 监控音频播放状态
    private val isMonitorAudioPlaying = AtomicBoolean(false)
    private var monitorMediaPlayer: MediaPlayer? = null

    // 悬浮窗相关
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var countdownTextView: TextView? = null
    private var analysisButton: ImageButton? = null
    private var countdownButton: ImageButton? = null
    private var stopCountdownAudioButton: ImageButton? = null

    // 十字准星相关
    private var crosshairView: View? = null
    private var crosshairX: Int = 0
    private var crosshairY: Int = 0

    // 添加 AudioManager 相关变量
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = 0
    private var originalRingerMode: Int = 0

    // 添加倒计时音频播放状态标志
    private val isCountdownAudioPlaying = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // 初始化 AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // 获取屏幕尺寸
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)

         var realDisplayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(realDisplayMetrics)

        // 计算实际可用高度
        screenHeight = getActualScreenHeight()

        // 初始化设置仓库
        settingsRepository = SettingsRepository(applicationContext)

        // 初始化悬浮窗
        this.windowManager = windowManager
        initFloatingWindow()
        initCrosshairWindow()
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
        stopCountdownAudioButton = floatingView?.findViewById(R.id.stopCountdownAudioButton)

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

        // 初始位置 - 屏幕中间最右侧
        params.gravity = Gravity.TOP or Gravity.START
        
        // 添加悬浮窗之前需要先测量其大小
        floatingView?.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val floatingViewWidth = floatingView?.measuredWidth ?: 0
        
        // 设置x坐标为屏幕宽度减去悬浮窗宽度
        params.x = displayMetrics.widthPixels - floatingViewWidth
        // 设置y坐标为屏幕高度的一半减去控件高度的一半
        params.y = displayMetrics.heightPixels / 2 - (floatingView?.measuredHeight ?: 0) / 2

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
            if(!newState){
                //停止播放
                stopMonitorAudio()
            }
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

        // 停止倒计时音频按钮
        stopCountdownAudioButton?.setOnClickListener {
            stopCountdownAudio()
            hideStopCountdownAudioButton()
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
        // 获取拖拽区域控件
        val dragHandle = floatingView?.findViewById<View>(R.id.dragHandle)

        // 只为拖拽区域设置触摸监听器
        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
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

                    MotionEvent.ACTION_UP -> {
                        // 检测是否靠近屏幕边缘，如果是则自动贴边
                        snapToEdgeIfNeeded(floatingView)
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 检测是否靠近屏幕边缘，如果是则自动贴边
     */
    private fun snapToEdgeIfNeeded(view: View?) {
        if (view == null || windowManager == null) return

        val params = view.layoutParams as WindowManager.LayoutParams

        // 计算屏幕宽度和高度
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 获取悬浮窗宽高
        val width = view.width
        val height = view.height

        // 贴边吸附的阈值（距离边缘多少像素会自动吸附）
        val snapThreshold = 40

        // 水平方向贴边
        if (params.x < snapThreshold) {
            // 贴左边
            params.x = 0
        } else if (screenWidth - (params.x + width) < snapThreshold) {
            // 贴右边
            params.x = screenWidth - width
        }

        // 垂直方向贴边
        if (params.y < snapThreshold) {
            // 贴顶部
            params.y = 0
        } else if (screenHeight - (params.y + height) < snapThreshold) {
            // 贴底部
            params.y = screenHeight - height
        }

        Log.d(TAG, "Snap to edge: ${params.x}, ${params.y}")
        Log.d(TAG, "Snap to edge: ${params.x + width}, ${params.y}")

        // 更新准星中心点坐标 (准星图像的中心点)
        crosshairX = params.x + 24  // 加上准星宽度的一半
        crosshairY = params.y + 24  // 加上准星高度的一半

        // 更新位置
        windowManager?.updateViewLayout(view, params)
    }

    /**
     * 开始倒计时
     */
    private fun startCountdown() {
        if (countdownRunning.get()) return

        // 从设置中获取倒计时时长（分钟）并转换为秒
        val durationInMinutes = settingsRepository.getCountdownDuration().toDoubleOrNull() ?: 5.0
        countdownSeconds = (durationInMinutes * 60).toInt()

        // 显示倒计时文本
        countdownTextView?.visibility = View.VISIBLE
        countdownTextView?.text = countdownSeconds.toString()

        // 更新按钮状态
        countdownRunning.set(true)
        updateCountdownButtonAppearance(true)

        // 创建倒计时任务
        countdownTask = scheduledExecutor.scheduleWithFixedDelay({
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
            targetRgb = Triple(13, 22, 33) // 默认红色
        }
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // 为Android 11及以上版本注册回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mediaProjection?.registerCallback(mediaProjectionCallback!!, handler)
        }

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

    private fun analyzeImage(bitmap: Bitmap) {
        // 在这里实现颜色分析逻辑
        targetRgb?.let { (r, g, b) ->
            try {

                // 计算移动距离并更新位置
                val params = crosshairView?.layoutParams as WindowManager.LayoutParams

                crosshairX = params.x + crosshairView!!.width / 2  // 加上准星宽度的一半
                crosshairY = params.y + crosshairView!!.width / 2  // 加上准星高度的一半

                // 计算准星在图像中的位置
                val bitmapX = crosshairX
                val bitmapY = crosshairY + getStatusBarHeight()


                // 测试，在bitmapX、bitmapY的位置绘制一个绿色的点
//                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
//                val canvas = Canvas(mutableBitmap)
//                val paint = Paint().apply {
//                    color = Color.GREEN
//                    style = Paint.Style.FILL
//                }
//
//                // 绘制绿色点，半径为5像素
//                canvas.drawCircle(bitmapX.toFloat(), bitmapY.toFloat(), 5f, paint)

                // 可选：保存修改后的图像用于调试
//                saveImageForDebug(mutableBitmap)


                // 获取像素颜色
                val pixel = bitmap.getPixel(bitmapX, bitmapY)
                val pixelR = (pixel shr 16) and 0xff
                val pixelG = (pixel shr 8) and 0xff
                val pixelB = pixel and 0xff

                Log.d(
                    TAG,
                    "Analyzing color at crosshair ($bitmapX, $bitmapY): RGB($pixelR, $pixelG, $pixelB), bitmap (${bitmap.width},${bitmap.height})"
                )

                // 容差匹配，允许一定程度的颜色偏差
                if (Math.abs(pixelR - r) < COLOR_TOLERANCE &&
                    Math.abs(pixelG - g) < COLOR_TOLERANCE &&
                    Math.abs(pixelB - b) < COLOR_TOLERANCE
                ) {

                    Log.d(TAG, "Found target color at crosshair: RGB($pixelR, $pixelG, $pixelB)")

                    // 如果倒计时正在运行，则停止它
                    if (countdownRunning.get()) {
                        stopCountdown()
                    }
                    
                    // 如果倒计时音频正在播放，则停止它
                    stopCountdownAudio()

                    // 播放监控音频
                    playMonitorAudio()
                } else {
                    // 颜色不匹配，停止播放音频
                    stopMonitorAudio()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing pixel at crosshair", e)
            }
        }
    }

    // MediaProjection回调
    private val mediaProjectionCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection已停止")
                stopSelf()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                Log.d(TAG, "屏幕尺寸已更改: $width x $height")
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Log.d(TAG, "内容可见性已更改: $isVisible")
            }
        }
    } else null

    private fun startCapturing() {
        isRunning.set(true)
        Log.d(TAG, "Screen capture started with image listener")
    }


    /**
     * 保存图像到外部存储用于调试
     */
    private fun saveImageForDebug(bitmap: Bitmap) {
        try {
            // 获取应用外部缓存目录
            val dir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }

            // 创建带时间戳的文件名
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "DEBUG_${timeStamp}.jpg"
            val file = File(dir, fileName)

            // 将位图保存为JPEG
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            Log.d(TAG, "调试图像已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存调试图像失败", e)
        }
    }


    /**
     * 设置最大音量
     */
    private fun setMaxVolume() {
        try {
            // 保存原始音量和铃声模式
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            originalRingerMode = audioManager.ringerMode

            // 设置最大音量
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            
            // 设置铃声模式为正常
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            
            Log.d(TAG, "已设置最大音量: $maxVolume")
        } catch (e: Exception) {
            Log.e(TAG, "设置最大音量失败", e)
        }
    }

    /**
     * 恢复原始音量设置
     */
    private fun restoreOriginalVolume() {
        try {
            // 恢复原始音量
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            
            // 恢复原始铃声模式
            audioManager.ringerMode = originalRingerMode
            
            Log.d(TAG, "已恢复原始音量: $originalVolume")
        } catch (e: Exception) {
            Log.e(TAG, "恢复原始音量失败", e)
        }
    }

    /**
     * 播放音频
     */
    private fun playAudio() {
        // 获取音频文件路径
        val audioPath = settingsRepository.getCountdownAudioPath()
        if (audioPath.isBlank()) return

        try {
            // 设置最大音量
            setMaxVolume()

            // 创建媒体播放器
            mediaPlayer = MediaPlayer().apply {
                try {
                    // 检查是否是本地文件路径
                    if (audioPath.startsWith("content://")) {
                        // 处理content URI
                        setDataSource(applicationContext, Uri.parse(audioPath))
                    } else {
                        // 处理本地文件路径
                        setDataSource(audioPath)
                    }

                    isLooping = true

                    // 设置监听器
                    setOnPreparedListener {
                        try {
                            start()
                            // 设置倒计时音频播放状态为true
                            isCountdownAudioPlaying.set(true)
                            // 显示停止音频按钮
                            showStopCountdownAudioButton()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting countdown audio", e)
                            mediaPlayer = null
                            isCountdownAudioPlaying.set(false)
                            hideStopCountdownAudioButton()
                            restoreOriginalVolume()
                        }
                    }

                    setOnCompletionListener {
                        it.release()
                        mediaPlayer = null
                        isCountdownAudioPlaying.set(false)
                        hideStopCountdownAudioButton()
                        restoreOriginalVolume()
                    }

                    setOnErrorListener { _, _, _ ->
                        Log.e(TAG, "Error playing countdown audio")
                        mediaPlayer = null
                        isCountdownAudioPlaying.set(false)
                        hideStopCountdownAudioButton()
                        restoreOriginalVolume()
                        true
                    }

                    // 异步准备
                    prepareAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up countdown audio", e)
                    mediaPlayer = null
                    isCountdownAudioPlaying.set(false)
                    hideStopCountdownAudioButton()
                    restoreOriginalVolume()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating countdown audio player", e)
            mediaPlayer = null
            isCountdownAudioPlaying.set(false)
            hideStopCountdownAudioButton()
            restoreOriginalVolume()
        }
    }
    
    /**
     * 停止倒计时音频播放
     */
    private fun stopCountdownAudio() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                Log.d(TAG, "停止倒计时音频")
                player.stop()
                player.release()
                mediaPlayer = null
                isCountdownAudioPlaying.set(false)
                hideStopCountdownAudioButton()
                restoreOriginalVolume()
            }
        }
    }

    /**
     * 显示停止倒计时音频按钮
     */
    private fun showStopCountdownAudioButton() {
        Handler(Looper.getMainLooper()).post {
            stopCountdownAudioButton?.visibility = View.VISIBLE
        }
    }

    /**
     * 隐藏停止倒计时音频按钮
     */
    private fun hideStopCountdownAudioButton() {
        Handler(Looper.getMainLooper()).post {
            stopCountdownAudioButton?.visibility = View.GONE
        }
    }

    /**
     * 播放监控音频
     */
    private fun playMonitorAudio() {
        // 如果音频正在播放，则跳过
        if (isMonitorAudioPlaying.get()) {
            Log.d(TAG, "监控音频正在播放，跳过新的播放请求")
            return
        }

        // 获取音频文件路径
        val audioPath = settingsRepository.getMonitorAudioPath()
        if (audioPath.isBlank()) return

        try {
            // 设置播放状态为true
            isMonitorAudioPlaying.set(true)

            // 设置最大音量
            setMaxVolume()

            // 创建媒体播放器
            monitorMediaPlayer = MediaPlayer().apply {
                try {
                    // 检查是否是本地文件路径
                    if (audioPath.startsWith("content://")) {
                        // 处理content URI
                        setDataSource(applicationContext, Uri.parse(audioPath))
                    } else {
                        // 处理本地文件路径
                        setDataSource(audioPath)
                    }

                    isLooping = true

                    // 设置监听器
                    setOnPreparedListener {
                        try {
                            if (isMonitorAudioPlaying.get()) {
                                start()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting monitor audio", e)
                            isMonitorAudioPlaying.set(false)
                            monitorMediaPlayer = null
                            restoreOriginalVolume()
                        }
                    }

                    setOnCompletionListener {
                        // 播放完成后释放资源并重置状态
                        it.release()
                        isMonitorAudioPlaying.set(false)
                        monitorMediaPlayer = null
                        restoreOriginalVolume()
                    }

                    setOnErrorListener { _, _, _ ->
                        // 发生错误时释放资源并重置状态
                        Log.e(TAG, "Error playing monitor audio")
                        isMonitorAudioPlaying.set(false)
                        monitorMediaPlayer = null
                        restoreOriginalVolume()
                        true
                    }

                    // 异步准备
                    prepareAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up monitor audio", e)
                    isMonitorAudioPlaying.set(false)
                    monitorMediaPlayer = null
                    restoreOriginalVolume()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating monitor audio player", e)
            // 发生异常时重置状态
            isMonitorAudioPlaying.set(false)
            monitorMediaPlayer = null
            restoreOriginalVolume()
        }
    }

    /**
     * 停止监控音频播放
     */
    private fun stopMonitorAudio() {
        if (isMonitorAudioPlaying.get()) {
            Log.d(TAG, "颜色不匹配，停止播放监控音频")
            monitorMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                monitorMediaPlayer = null
                isMonitorAudioPlaying.set(false)
                restoreOriginalVolume()
            }
        }
    }

    /**
     * 初始化十字准星悬浮窗
     */
    private fun initCrosshairWindow() {
        // 创建十字准星布局
        crosshairView = LayoutInflater.from(this).inflate(R.layout.crosshair_view, null)

        // 设置布局参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // 初始位置 - 屏幕中心
        params.gravity = Gravity.TOP or Gravity.START
        crosshairX = displayMetrics.widthPixels / 2 - 24 // 减去准星宽度的一半
        crosshairY = displayMetrics.heightPixels / 2 - 24 // 减去准星高度的一半
        params.x = crosshairX
        params.y = crosshairY

        // 获取拖拽手柄区域并设置触摸监听器
        val dragHandle = crosshairView?.findViewById<View>(R.id.crosshairDragHandle)
        dragHandle?.setOnTouchListener(crosshairTouchListener)

        // 添加到窗口管理器
        windowManager?.addView(crosshairView, params)

        // 记录准星中心在屏幕上的实际位置
        updateCrosshairPosition()
    }

    /**
     * 更新十字准星位置
     */
    private fun updateCrosshairPosition() {
        // 准星中心点相对于屏幕的绝对位置 (准星图像的中心点)
        crosshairX = displayMetrics.widthPixels / 2
        crosshairY = screenHeight / 2
    }

    /**
     * 准星触摸监听器
     */
    private val crosshairTouchListener = object : View.OnTouchListener {
        private var initialX: Int = 0
        private var initialY: Int = 0
        private var initialTouchX: Float = 0f
        private var initialTouchY: Float = 0f

        override fun onTouch(v: View?, event: MotionEvent?): Boolean {
            when (event?.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录初始位置
                    val params = crosshairView?.layoutParams as WindowManager.LayoutParams
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 计算移动距离并更新位置
                    val params = crosshairView?.layoutParams as WindowManager.LayoutParams
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(crosshairView, params)

                    crosshairX = params.x + crosshairView!!.width / 2  // 加上准星宽度的一半
                    crosshairY = params.y + crosshairView!!.width / 2  // 加上准星高度的一半

                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // 检测是否靠近屏幕边缘，如果是则自动贴边
//                    snapToEdgeIfNeeded(crosshairView)
                    return true
                }
            }
            return false
        }
    }

    /**
     * 获取实际可用屏幕高度（减去状态栏和导航栏）
     */
    private fun getActualScreenHeight(): Int {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        // 获取屏幕的实际高度
        val realHeight = displayMetrics.heightPixels

        // 计算状态栏高度
        val statusBarHeight = getStatusBarHeight()

        // 计算导航栏高度
        val navigationBarHeight = getNavigationBarHeight()

        // 计算实际可用高度
        val actualHeight = realHeight - statusBarHeight - navigationBarHeight

        Log.d(
            TAG,
            "屏幕实际高度: $realHeight, 状态栏高度: $statusBarHeight, 导航栏高度: $navigationBarHeight, 可用高度: $actualHeight"
        )

        return actualHeight
    }

    /**
     * 获取状态栏高度
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    /**
     * 获取导航栏高度
     */
    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")

        // 检查设备是否有导航栏
        val hasNavigationBar =
            resources.getIdentifier("config_showNavigationBar", "bool", "android") > 0 &&
                    resources.getBoolean(
                        resources.getIdentifier(
                            "config_showNavigationBar",
                            "bool",
                            "android"
                        )
                    )

        return if (resourceId > 0 && hasNavigationBar) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
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

        // 释放监控音频播放器
        monitorMediaPlayer?.release()
        monitorMediaPlayer = null

        // 移除悬浮窗
        windowManager?.removeView(floatingView)
        windowManager?.removeView(crosshairView)

        // 如果在Android 11及以上版本注册了回调，在销毁时注销
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mediaProjection?.unregisterCallback(mediaProjectionCallback!!)
        }

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