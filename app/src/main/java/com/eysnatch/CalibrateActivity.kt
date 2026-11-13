package com.eysnatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.eysnatch.core.CalStep
import com.eysnatch.core.Calibrator
import com.eysnatch.core.CalibrationResult

/** Guided calibration: shows one pose at a time and learns the user's own numbers from it. */
class CalibrateActivity : BaseActivity() {

    private enum class Phase { INTRO, WAIT_FACE, READY, CAPTURE, REVIEW }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var camera: CameraView
    private lateinit var title: TextView
    private lateinit var prompt: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var tertiary: Button

    private var phase = Phase.INTRO
    private var steps: List<CalStep> = emptyList()
    private var index = 0
    private var phaseStart = 0L
    private var waitingSince = 0L
    private var retries = 0
    private var calibrator = Calibrator()
    private var startedService = false
    private var pendingHead = false

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 50)
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin(pendingHead) else detail.text = "The camera permission is needed to calibrate."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = Ui.column(this)
        camera = root.add(CameraView(this), 0, dp(220))
        title = root.add(Ui.heading(this, "", 21f), 14)
        prompt = root.add(Ui.text(this, "", 17f), 6)
        progress = root.add(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }, 12)
        detail = root.add(Ui.dim(Ui.text(this, "", 14f)), 6)
        primary = root.add(Ui.button(this, "") {}, 14)
        secondary = root.add(Ui.button(this, "") {})
        tertiary = root.add(Ui.button(this, "") {})
        setContentView(Ui.scroll(this, root))
        showIntro()
    }

    override fun onStop() {
        if (phase != Phase.INTRO && phase != Phase.REVIEW) {
            cleanup()
            showIntro()
        }
        super.onStop()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- flow

    private fun showIntro() {
        phase = Phase.INTRO
        title.text = "Calibrate"
        prompt.text = "Hold your phone the way you normally use it, with good light on your face. " +
            "I will ask for one pose at a time (about 4 seconds each). This teaches EySnatch how YOUR eyes look to YOUR camera, " +
            "so gestures are reliable and left/right are never mixed up."
        detail.text = ""
        progress.visibility = View.GONE
        show(primary, "Start: eyes (about 45 seconds)") { begin(false) }
        show(secondary, "Start: eyes and head turns (about 70 seconds)") { begin(true) }
        show(tertiary, "Close") { finish() }
    }

    private fun begin(withHead: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingHead = withHead
            askCamera.launch(Manifest.permission.CAMERA)
            return
        }
        steps = buildList {
            addAll(listOf(CalStep.NEUTRAL, CalStep.BOTH_CLOSED, CalStep.WINK_LEFT, CalStep.WINK_RIGHT))
            addAll(listOf(CalStep.LOOK_LEFT, CalStep.LOOK_RIGHT, CalStep.LOOK_UP, CalStep.LOOK_DOWN, CalStep.BROWS))
            if (withHead) addAll(listOf(CalStep.TURN_LEFT, CalStep.TURN_RIGHT, CalStep.TILT_UP, CalStep.TILT_DOWN))
        }
        calibrator = Calibrator(Prefs.calibration())
        index = 0
        retries = 0
        Live.calibrating = true
        Live.frameListener = { f -> handler.post { if (phase == Phase.CAPTURE) calibrator.add(f) } }
        if (!Live.running) {
            startedService = true
            ContextCompat.startForegroundService(this, Intent(this, EyeTrackingService::class.java))
        }
        phase = Phase.WAIT_FACE
        waitingSince = SystemClock.uptimeMillis()
        title.text = "Getting ready"
        prompt.text = "Look at the front camera so I can find your face."
        progress.visibility = View.VISIBLE
        progress.progress = 0
        primary.visibility = View.GONE
        secondary.visibility = View.GONE
        show(tertiary, "Cancel") { abort() }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun startReady() {
        val step = steps[index]
        phase = Phase.READY
        phaseStart = SystemClock.uptimeMillis()
        title.text = "${index + 1} of ${steps.size}:  ${step.title}"
        prompt.text = step.prompt
        progress.progress = 0
        primary.visibility = View.GONE
        show(secondary, "Cancel") { abort() }
        if (step == CalStep.NEUTRAL) tertiary.visibility = View.GONE
        else show(tertiary, "Skip this pose") { index += 1; next() }
    }

    private fun onTick() {
        val now = SystemClock.uptimeMillis()
        when (phase) {
            Phase.WAIT_FACE -> {
                if (Live.running && Live.frame?.face == true) {
                    startReady()
                } else if (!Live.running && now - waitingSince > 4000) {
                    detail.text = "The camera did not start: ${Live.error ?: "no reason given"}"
                } else {
                    detail.text = if (Live.running) "Looking for your face..." else "Starting the camera..."
                }
            }
            Phase.READY -> {
                val left = READY_MS - (now - phaseStart)
                if (left <= 0) {
                    calibrator.startStep()
                    phase = Phase.CAPTURE
                    phaseStart = now
                    detail.text = "Hold it!"
                } else {
                    detail.text = "Get ready...  ${left / 1000 + 1}"
                }
            }
            Phase.CAPTURE -> {
                val elapsed = now - phaseStart
                progress.progress = (100 * elapsed / CAPTURE_MS).toInt().coerceAtMost(100)
                if (elapsed >= CAPTURE_MS) finishStep()
            }
            else -> Unit
        }
        camera.invalidate()
    }

    private fun finishStep() {
        val step = steps[index]
        if (calibrator.finishStep(step)) {
            index += 1
            retries = 0
            next()
            return
        }
        retries += 1
        when {
            step == CalStep.NEUTRAL && retries >= 3 -> {
                Toast.makeText(this, "I could not see your face. Check the light and try again.", Toast.LENGTH_LONG).show()
                abort()
            }
            retries >= 3 -> {
                index += 1
                retries = 0
                next()
            }
            else -> {
                startReady()
                detail.text = "I lost your face for a moment, so let's repeat that pose."
            }
        }
    }

    private fun next() {
        if (index >= steps.size) review() else startReady()
    }

    private fun review() {
        val result: CalibrationResult = calibrator.build()
        phase = Phase.REVIEW
        handler.removeCallbacks(ticker)
        progress.visibility = View.GONE
        title.text = "Calibration finished"
        prompt.text = buildString {
            result.report.forEach { appendLine("OK:  $it") }
            result.warnings.forEach { appendLine("Note:  $it") }
            if (result.report.isEmpty() && result.warnings.isEmpty()) append("Nothing to report.")
        }.trim()
        detail.text = "Save it, then open Live view to check that every gesture responds."
        show(primary, "Save") {
            Prefs.saveCalibration(result.calibration)
            Toast.makeText(this, "Calibration saved", Toast.LENGTH_SHORT).show()
            cleanup()
            finish()
        }
        show(secondary, "Discard") {
            cleanup()
            finish()
        }
        tertiary.visibility = View.GONE
    }

    private fun abort() {
        cleanup()
        showIntro()
    }

    private fun cleanup() {
        Live.calibrating = false
        Live.frameListener = null
        handler.removeCallbacksAndMessages(null)
        if (startedService) {
            startedService = false
            stopService(Intent(this, EyeTrackingService::class.java))
        }
    }

    private fun show(b: Button, label: String, onClick: () -> Unit) {
        b.text = label
        b.visibility = View.VISIBLE
        b.setOnClickListener { onClick() }
    }

    companion object {
        private const val READY_MS = 1800L
        private const val CAPTURE_MS = 2600L
    }
}
