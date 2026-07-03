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

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnToggle: Button
    private lateinit var scrollResult: ScrollView

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val CAPTURE_INTERVAL = 2500L

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var lastResult = ""

    private val captureTask = object : Runnable {
        override fun run() {
            if (isRunning) {
                captureAndOCR()
                handler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "fanjian_float"
        const val NOTIFICATION_ID = 1
        var instance: FloatWindowService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Toast.makeText(this, "Service created", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        android.util.Log.d("FanJianFloat", "onStartCommand: resultCode=$resultCode, data=${data != null}")

        if (resultCode != -1 && data != null) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification("Running..."))
            } catch (e: Exception) {
                android.util.Log.e("FanJianFloat", "startForeground failed: ${e.message}")
            }

            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(resultCode, data)

                val metrics = resources.displayMetrics
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                screenDensity = metrics.densityDpi

                setupFloatWindow()
                startScreenCapture()

                isRunning = true
                btnToggle.text = "\u23F8"
                tvStatus.text = "Auto detecting..."
                Toast.makeText(this, "Float window ready!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("FanJianFloat", "Setup failed: ${e.message}", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        } else {
            android.util.Log.w("FanJianFloat", "No valid intent extras, stopping")
            stopSelf()
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

    @Suppress("DEPRECATION")
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

        makeDraggable(floatView.findViewById(R.id.title_bar))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        windowManager.addView(floatView, layoutParams)
        android.util.Log.d("FanJianFloat", "Float window added to WindowManager")
    }

    private fun makeDraggable(dragView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    layoutParams?.let { lp ->
                        initialX = lp.x
                        initialY = lp.y
                    }
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.let { lp ->
                        lp.x = initialX + (event.rawX - initialTouchX).toInt()
                        lp.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatView, lp)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeFloatWindow() {
        try {
            if (::floatView.isInitialized && ::windowManager.isInitialized) {
                windowManager.removeView(floatView)
            }
        } catch (e: Exception) {
            android.util.Log.e("FanJianFloat", "removeFloatWindow: ${e.message}")
        }
    }

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

        handler.postDelayed(captureTask, 1000)
        android.util.Log.d("FanJianFloat", "Screen capture started")
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

            val bmpWidth = image.width
            val bmpHeight = image.height
            val bitmap = Bitmap.createBitmap(
                bmpWidth + rowPadding / pixelStride, bmpHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, bmpWidth, bmpHeight)
            val scaled = Bitmap.createScaledBitmap(cropped, bmpWidth / 2, bmpHeight / 2, true)

            bitmap.recycle()
            cropped.recycle()

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
                                tvStatus.text = "Converted ${converted.length} chars"
                                scrollResult.fullScroll(View.FOCUS_UP)
                            }
                        }
                    }
                    scaled.recycle()
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("FanJianFloat", "OCR failed: ${e.message}")
                    scaled.recycle()
                }
        } catch (e: Exception) {
            android.util.Log.e("FanJianFloat", "captureAndOCR: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        isRunning = false
        handler.removeCallbacks(captureTask)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    private fun toggleCapture() {
        if (isRunning) {
            isRunning = false
            handler.removeCallbacks(captureTask)
            btnToggle.text = "\u25B6"
            tvStatus.text = "Paused"
        } else {
            isRunning = true
            btnToggle.text = "\u23F8"
            tvStatus.text = "Auto detecting..."
            handler.postDelayed(captureTask, 500)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FanJianFloat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Traditional to Simplified OCR Service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FanJianFloat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}