package com.fanjian.floatocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer

class FloatActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnToggle: Button
    private lateinit var scrollResult: ScrollView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var lastResult = ""

    private val captureTask = object : Runnable {
        override fun run() {
            if (isRunning) {
                captureAndOCR()
                handler.postDelayed(this, 2500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make this activity look like a floating window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            window.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        window.setFormat(PixelFormat.TRANSLUCENT)

        setContentView(R.layout.float_window)

        tvStatus = findViewById(R.id.tv_status)
        tvResult = findViewById(R.id.tv_result)
        btnToggle = findViewById(R.id.btn_toggle)
        scrollResult = findViewById(R.id.scroll_result)
        val btnClose: Button = findViewById(R.id.btn_close)

        btnToggle.setOnClickListener { toggleCapture() }
        btnClose.setOnClickListener { finish() }

        // Make draggable via title bar
        makeDraggable(findViewById(R.id.title_bar))

        // Get MediaProjection from the intent
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data = intent.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi

            // Start capture
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

            isRunning = true
            btnToggle.text = "\u23F8"
            tvStatus.text = "Auto detecting..."
            handler.postDelayed(captureTask, 1000)
        } else {
            tvStatus.text = "No capture data!"
            Toast.makeText(this, "Error: no screen capture data", Toast.LENGTH_LONG).show()
        }
    }

    private fun makeDraggable(dragView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = window.attributes.x
                    initialY = window.attributes.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = window.attributes
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    window.attributes = params
                    true
                }
                else -> false
            }
        }
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
            val bitmap = android.graphics.Bitmap.createBitmap(
                bmpWidth + rowPadding / pixelStride, bmpHeight,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bmpWidth, bmpHeight)
            val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, bmpWidth / 2, bmpHeight / 2, true)
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
                .addOnFailureListener {
                    scaled.recycle()
                }
        } catch (e: Exception) {
        } finally {
            image.close()
        }
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

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(captureTask)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }
}