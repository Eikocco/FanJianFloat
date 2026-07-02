package com.fanjian.floatocr

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer

class FloatWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private var layoutParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionManager: MediaProjectionManager? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnToggle: Button
    private lateinit var scrollResult: ScrollView

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var captureRunnable: Runnable? = null
    private val CAPTURE_INTERVAL = 2500L // 2.5 seconds

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var lastResult = ""

    companion object {
        const val CHANNEL_ID = "fanjian_float"
        const val NOTIFICATION_ID = 1
        var instance: FloatWindowService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            startForeground(NOTIFICATION_ID, buildNotification("运行中..."))
            projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager?.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            setupFloatWindow()
            startScreenCapture()

            isRunning = true
            btnToggle.text = "⏸"
            tvStatus.text = "自动识别中..."
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        removeFloatWindow()
        instance = null
        super.onDestroy()
    }

    // ─── Float Window ────────────────────────────────

    private fun setupFloatWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatView = inflater.inflate(R.layout.float_window, null)

        tvStatus = floatView.findViewById(R.id.tv_status)
        tvResult = floatView.findViewById(R.id.tv_result)
        btnToggle = floatView.findViewById(R.id.btn_toggle)
        scrollResult = floatView.findViewById(R.id.scroll_result)
        val btnClose: Button = floatView.findViewById(R.id.btn_close)

        btnToggle.setOnClickListener { toggleCapture() }
        btnClose.setOnClickListener { stopSelf() }

        // Make draggable
        makeDraggable(floatView.findViewById(R.id.title_bar))

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        windowManager.addView(floatView, layoutParams)
    }

    private fun makeDraggable(dragView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeFloatWindow() {
        try {
            if (::floatView.isInitialized) {
                windowManager.removeView(floatView)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // ─── Screen Capture ──────────────────────────────

    private fun startScreenCapture() {
        imageReader = ImageReader.newInstance(
            screenWidth / 2, screenHeight / 2,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FanJianCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        scheduleNextCapture()
    }

    private fun scheduleNextCapture() {
        captureRunnable = Runnable {
            if (isRunning) {
                captureAndOCR()
                handler.postDelayed(captureRunnable, CAPTURE_INTERVAL)
            }
        }
        handler.postDelayed(captureRunnable, 1000)
    }

    private fun captureAndOCR() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // Create bitmap (scale down for faster OCR)
            val scale = 2
            val bmpWidth = image.width
            val bmpHeight = image.height
            val bitmap = Bitmap.createBitmap(
                bmpWidth + rowPadding / pixelStride, bmpHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual width, then scale down
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, bmpWidth, bmpHeight)
            val scaledW = bmpWidth / scale
            val scaledH = bmpHeight / scale
            val scaled = Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)

            bitmap.recycle()
            cropped.recycle()

            // Run OCR
            val inputImage = InputImage.fromBitmap(scaled, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val allText = visionText.text
                    if (allText.isNotBlank() && allText != lastResult) {
                        val converted = FanJianConverter.convert(allText)
                        if (converted != allText) {
                            lastResult = allText
                            handler.post {
                                tvResult.text = converted
                                tvStatus.text = "已转换  字"
                                scrollResult.fullScroll(View.FOCUS_UP)
                            }
                        }
                    }
                    scaled.recycle()
                }
                .addOnFailureListener {
                    scaled.recycle()
                }
        } catch (e: Exception) {
            // Skip frame on error
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        isRunning = false
        captureRunnable?.let { handler.removeCallbacks(it) }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    private fun toggleCapture() {
        if (isRunning) {
            isRunning = false
            btnToggle.text = "▶"
            tvStatus.text = "已暂停"
        } else {
            isRunning = true
            btnToggle.text = "⏸"
            tvStatus.text = "自动识别中..."
            scheduleNextCapture()
        }
    }

    // ─── Notification ────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "繁转简悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "繁转简 OCR 后台服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("繁转简")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}