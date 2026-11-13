package com.eysnatch

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.eysnatch.core.Gesture
import com.eysnatch.core.PhoneAction

class MainActivity : BaseActivity() {

    private class Check(val text: TextView, val button: Button, val box: View)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var toggle: Button
    private lateinit var cameraCheck: Check
    private lateinit var accessibilityCheck: Check
    private lateinit var restrictedHelp: View
    private lateinit var notificationCheck: Check
    private lateinit var batteryCheck: Check
    private lateinit var mapping: TextView
    private var startAfterPermission = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 600)
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (startAfterPermission && hasCamera()) startTracking()
        startAfterPermission = false
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.column(this)
        root.add(Ui.heading(this, "EySnatch", 30f))
        root.add(Ui.dim(Ui.text(this, "Control your phone with your eyes, hands free.", 15f)), 2)

        status = root.add(Ui.text(this, "", 17f, bold = true), 18)
        hint = root.add(Ui.dim(Ui.text(this, "", 13f)), 2)
        toggle = root.add(Ui.button(this, "Start eye control") { onToggle() }, 8)

        root.add(Ui.section(this, "Setup"), 22)
        cameraCheck = check(root, "Allow camera") { requestPermissions() }
        accessibilityCheck = check(root, "Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        restrictedHelp = Ui.card(this).also { box ->
            box.add(
                Ui.text(
                    this,
                    "Switch greyed out? Android blocks accessibility services of apps installed from a file until you allow it: " +
                        "open App info, tap the three-dot menu (top right), choose \"Allow restricted settings\", " +
                        "then turn the EySnatch service on.",
                    13f,
                ),
            )
            box.add(Ui.button(this, "Open App info") { openAppInfo() }, 6)
            root.add(box, 8)
        }
        notificationCheck = check(root, "Allow notifications") { requestPermissions() }
        batteryCheck = check(root, "Allow background use") { askBatteryExemption() }

        root.add(Ui.section(this, "Use it"), 22)
        root.add(Ui.button(this, "Live view and test") { startActivity(Intent(this, LiveActivity::class.java)) }, 6)
        root.add(Ui.button(this, "Calibrate (recommended)") { startActivity(Intent(this, CalibrateActivity::class.java)) })
        root.add(Ui.button(this, "Gestures and settings") { startActivity(Intent(this, SettingsActivity::class.java)) })

        root.add(Ui.section(this, "Your gestures"), 22)
        mapping = root.add(Ui.text(this, "", 14f), 6)

        root.add(Ui.section(this, "Tips"), 22)
        root.add(
            Ui.dim(
                Ui.text(
                    this,
                    "- Hold the phone about an arm's length away with your face in the middle of the front camera.\n" +
                        "- Use decent light on your face. Avoid a bright window behind you.\n" +
                        "- Calibrate once; it learns how your eyes look to your camera.\n" +
                        "- While an EySnatch screen is open, gestures are only shown (Live view), not performed.\n" +
                        "- Camera images stay on your phone and are never stored or sent anywhere.",
                    13f,
                ),
            ),
            6,
        )
        setContentView(Ui.scroll(this, root))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true && hasCamera() && !Live.running) startTracking()
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            "live" -> startActivity(Intent(this, LiveActivity::class.java))
            "calibrate" -> startActivity(Intent(this, CalibrateActivity::class.java))
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun check(parent: LinearLayout, buttonLabel: String, onClick: () -> Unit): Check {
        val box = Ui.card(this)
        val text = box.add(Ui.text(this, "", 15f))
        val button = box.add(Ui.button(this, buttonLabel, onClick), 6)
        parent.add(box, 8)
        return Check(text, button, box)
    }

    private fun Check.set(ok: Boolean, label: String, show: Boolean = true) {
        box.visibility = if (show) View.VISIBLE else View.GONE
        text.text = (if (ok) "[OK]  " else "[ ]  ") + label
        button.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun refresh() {
        val running = Live.running
        status.text = when {
            running -> Live.status
            Live.error != null -> "Problem: ${Live.error}"
            else -> "Eye control is off"
        }
        hint.text = when {
            running && Live.paused -> "Paused. Do your 'pause / resume' gesture, or use the notification, to continue."
            running && EySnatchAccessibilityService.instance == null ->
                "The accessibility service is off or not connected yet, so actions cannot run. See Setup below."
            running -> "Gestures run in other apps. Leave this screen to use them." +
                (Live.recent().firstOrNull()?.let { "\nLatest: $it" } ?: "")
            Prefs.calibration().calibrated -> ""
            else -> "Tip: run Calibrate once for the best accuracy."
        }
        toggle.text = if (running) "Stop eye control" else "Start eye control"

        cameraCheck.set(hasCamera(), if (hasCamera()) "Camera allowed" else "Camera permission needed")
        val acc = accessibilityEnabled()
        accessibilityCheck.set(
            acc,
            if (acc) "Accessibility service is on" else "Turn on the EySnatch accessibility service (it presses buttons and swipes for you)",
        )
        restrictedHelp.visibility = if (!acc && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        val needsNotification = Build.VERSION.SDK_INT >= 33
        val notificationsOk = !needsNotification || granted(Manifest.permission.POST_NOTIFICATIONS)
        notificationCheck.set(
            notificationsOk,
            if (notificationsOk) "Notifications allowed" else "Notifications let you pause or stop from the shade",
            show = needsNotification,
        )
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryCheck.set(batteryOk, if (batteryOk) "Background use allowed" else "Not restricted by battery saver (recommended)")

        mapping.text = Gesture.values()
            .filter { Prefs.action(it) != PhoneAction.NONE }
            .joinToString("\n") { "${it.label}  ->  ${Prefs.action(it).label}" }
            .ifEmpty { "No gestures are switched on. Open Gestures and settings." }
    }

    private fun onToggle() {
        if (Live.running) {
            stopService(Intent(this, EyeTrackingService::class.java))
            return
        }
        if (!hasCamera()) {
            startAfterPermission = true
            requestPermissions()
            return
        }
        startTracking()
    }

    private fun startTracking() {
        if (Live.running) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, EyeTrackingService::class.java))
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not start: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissions() {
        val wanted = ArrayList<String>()
        if (!hasCamera()) wanted += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isEmpty()) {
            openAppInfo()
        } else {
            askPermissions.launch(wanted.toTypedArray())
        }
    }

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasCamera() = granted(Manifest.permission.CAMERA)

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, EySnatchAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(me.flattenToString(), ignoreCase = true) || it.equals(me.flattenToShortString(), ignoreCase = true)
        }
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_OPEN = "open"
    }
}
