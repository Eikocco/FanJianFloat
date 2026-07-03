package com.fanjian.floatocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_OVERLAY = 100
    private val REQUEST_PROJECTION = 101
    private val REQUEST_NOTIFICATION = 102

    private lateinit var btnStart: Button
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btn_start)
        tvHint = findViewById(R.id.tv_hint)

        // Check overlay permission on start
        if (Settings.canDrawOverlays(this)) {
            btnStart.text = "\uD83D\uDE80 Start"
            tvHint.text = "Overlay: Granted \u2705\nClick to grant screen recording"
        }

        btnStart.setOnClickListener {
            when {
                !Settings.canDrawOverlays(this) -> {
                    requestOverlayPermission()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED -> {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION
                    )
                }
                else -> {
                    requestProjection()
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY)
    }

    private fun requestProjection() {
        Toast.makeText(this, "Grant screen recording for OCR", Toast.LENGTH_SHORT).show()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification OK \u2705", Toast.LENGTH_SHORT).show()
                requestProjection()
            } else {
                requestProjection() // try anyway
            }
        }
    }

    @Deprecated("Override onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay OK \u2705", Toast.LENGTH_SHORT).show()
                    tvHint.text = "Overlay: OK \u2705\nClick again to grant screen recording"
                    btnStart.text = "\uD83D\uDE80 Continue"
                } else {
                    Toast.makeText(this, "Overlay permission required!", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Toast.makeText(this, "Starting float window...", Toast.LENGTH_SHORT).show()

                    val intent = Intent(this, FloatWindowService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        Toast.makeText(this, "Float window started! \uD83D\uDE3D", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }

                    tvHint.text = "Float window should be on screen now\nOpen your manga app!"
                    btnStart.text = "\u2705 Started"

                    // Minimize
                    moveTaskToBack(true)
                } else {
                    Toast.makeText(this, "Screen recording permission needed!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}