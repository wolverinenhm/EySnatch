package com.eysnatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { toggle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            if (EyeTrackingService.running) toggle() else
                permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggle() {
        val svc = Intent(this, EyeTrackingService::class.java)
        if (EyeTrackingService.running) stopService(svc) else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            ContextCompat.startForegroundService(this, svc)
        }
        findViewById<TextView>(R.id.status).postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        val acc = EySnatchAccessibilityService.instance != null
        findViewById<TextView>(R.id.status).text =
            "Accessibility: ${if (acc) "enabled" else "NOT enabled"}\nEye control: ${if (EyeTrackingService.running) "running" else "stopped"}"
        findViewById<Button>(R.id.btnToggle).text =
            if (EyeTrackingService.running) "2. Stop eye control" else "2. Start eye control"
    }
}
