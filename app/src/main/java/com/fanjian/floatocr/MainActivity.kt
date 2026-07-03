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

        btnStart.setOnClickListener {
            when {
                !Settings.canDrawOverlays(this) -> requestOverlayPermission()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED -> {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
                }
                else -> requestProjection()
            }
        }
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
        startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            REQUEST_OVERLAY
        )
    }

    private fun requestProjection() {
        Toast.makeText(this, "Grant screen recording for OCR", Toast.LENGTH_SHORT).show()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) requestProjection()
    }

    @Deprecated("Override onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay OK! Click again \uD83D\uDC46", Toast.LENGTH_SHORT).show()
                    tvHint.text = "Overlay granted!\nClick button to continue"
                    btnStart.text = "\uD83D\uDE80 Continue"
                }
            }
            REQUEST_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Start FloatActivity instead of Service
                    val intent = Intent(this, FloatActivity::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Float window launched!", Toast.LENGTH_SHORT).show()
                    finish() // close main activity
                } else {
                    Toast.makeText(this, "Screen recording permission needed!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}