package com.fanjian.floatocr

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQUEST_OVERLAY = 100
    private val REQUEST_PROJECTION = 101

    private lateinit var btnStart: Button
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btn_start)
        tvHint = findViewById(R.id.tv_hint)

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                requestProjection()
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:")
        )
        startActivityForResult(intent, REQUEST_OVERLAY)
    }

    private fun requestProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Override onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已获取 ✅", Toast.LENGTH_SHORT).show()
                    tvHint.text = "请再次点击按钮，授予屏幕录制权限"
                } else {
                    Toast.makeText(this, "需要悬浮窗权限才能使用 ⚠️", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Start service
                    val intent = Intent(this, FloatWindowService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }

                    Toast.makeText(this, "繁转简悬浮窗已启动 😽", Toast.LENGTH_SHORT).show()
                    tvHint.text = "悬浮窗已在屏幕显示\n打开漫画/小说自动转换繁体→简体"
                    btnStart.text = "✅ 已启动"

                    // Minimize app
                    moveTaskToBack(true)
                } else {
                    Toast.makeText(this, "需要屏幕录制权限才能使用", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}