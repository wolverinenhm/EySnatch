package com.eysnatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Shows exactly what the app sees, and which gestures it recognises (without running them). */
class LiveActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var camera: CameraView
    private lateinit var bars: BarsView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var toggle: Button

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = Ui.column(this)
        root.add(Ui.heading(this, "Live view"))
        root.add(
            Ui.dim(
                Ui.text(
                    this,
                    "This is what EySnatch sees. Try your gestures: when a bar passes the orange line and stays there, " +
                        "the gesture fires. Here they are only shown, not performed.",
                    13f,
                ),
            ),
            2,
        )
        camera = root.add(CameraView(this), 12, dp(260))
        status = root.add(Ui.text(this, "", 14f, bold = true), 8)
        bars = root.add(BarsView(this), 10)
        root.add(Ui.section(this, "Recent gestures"), 14)
        log = root.add(Ui.text(this, "", 14f), 6)
        toggle = root.add(Ui.button(this, "Start eye control") { onToggle() }, 14)
        root.add(Ui.button(this, "Calibrate") { startActivity(Intent(this, CalibrateActivity::class.java)) })
        setContentView(Ui.scroll(this, root))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun onToggle() {
        if (Live.running) {
            stopService(Intent(this, EyeTrackingService::class.java))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Live.note("Allow the camera on the home screen first.")
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, EyeTrackingService::class.java))
        } catch (t: Throwable) {
            Live.note("Could not start: ${t.message}")
        }
    }

    private fun refresh() {
        toggle.text = if (Live.running) "Stop eye control" else "Start eye control"
        status.text = when {
            Live.running -> "${Live.status}   (${"%.0f".format(Live.fps)} frames/s)"
            Live.error != null -> "Problem: ${Live.error}"
            else -> "Eye control is off. Tap Start. (Camera permission is asked on the home screen.)"
        }
        log.text = Live.recent().joinToString("\n").ifEmpty { "Nothing yet" }
        camera.invalidate()
        bars.invalidate()
    }
}
