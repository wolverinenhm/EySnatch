package com.eysnatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.eysnatch.core.PhoneAction

/** Carries out the actions. Android only lets an accessibility service press Back/Home or swipe for you. */
class EySnatchAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var windows: WindowManager? = null
    private var hudView: TextView? = null
    private var awakeView: View? = null
    private val hideHud = Runnable { hudView?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "accessibility service connected")
        onTrackingChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        removeOverlays()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeOverlays()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- called by the camera service

    /** Keeps the screen awake while eye control is running (so the phone does not lock mid-use). */
    fun onTrackingChanged() {
        val keep = Prefs.keepAwake && Live.running
        main.post { setKeepAwake(keep) }
    }

    fun hint(text: String) {
        main.post { showHud(text) }
    }

    fun perform(action: PhoneAction, why: String) {
        main.post {
            showHud("$why  ->  ${action.label}")
            buzz()
            execute(action)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun execute(action: PhoneAction) {
        when (action) {
            PhoneAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            PhoneAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            PhoneAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            PhoneAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            PhoneAction.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            PhoneAction.SCREENSHOT ->
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                else hint("Screenshots need Android 9 or newer")
            PhoneAction.LOCK_SCREEN ->
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                else hint("Locking needs Android 9 or newer")
            PhoneAction.SWIPE_LEFT -> swipe(0.85f, 0.55f, 0.15f, 0.55f, 220)
            PhoneAction.SWIPE_RIGHT -> swipe(0.15f, 0.55f, 0.85f, 0.55f, 220)
            PhoneAction.SCROLL_DOWN -> swipe(0.5f, 0.72f, 0.5f, 0.28f, 350)
            PhoneAction.SCROLL_UP -> swipe(0.5f, 0.28f, 0.5f, 0.72f, 350)
            PhoneAction.FORWARD -> if (!scrollNode(true)) swipe(0.85f, 0.55f, 0.15f, 0.55f, 220)
            PhoneAction.BACKWARD -> if (!scrollNode(false)) swipe(0.15f, 0.55f, 0.85f, 0.55f, 220)
            PhoneAction.PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            PhoneAction.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            PhoneAction.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            PhoneAction.TOGGLE_PAUSE, PhoneAction.NONE -> Unit
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val (w, h) = screenSize()
        val path = Path().apply {
            moveTo(w * x1, h * y1)
            lineTo(w * x2, h * y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        if (!dispatchGesture(gesture, null, null)) Log.w(TAG, "swipe was not accepted by Android")
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val m = resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    /** "Forward" = scroll the biggest scrollable thing on screen one page; falls back to a swipe. */
    private fun scrollNode(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val want = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        val bounds = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited += 1
            if (node.isScrollable && node.isVisibleToUser && node.actionList.any { it.id == want }) {
                node.getBoundsInScreen(bounds)
                val area = bounds.width() * bounds.height()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return best?.performAction(want) ?: false
    }

    private fun mediaKey(code: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun volume(direction: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    // ---------------------------------------------------------------- feedback

    private fun buzz() {
        if (!Prefs.vibrate) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate: ${t.message}")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showHud(text: String) {
        if (!Prefs.hud) return
        val wm = windows ?: return
        try {
            var view = hudView
            if (view == null) {
                view = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        setColor(0xE6101820.toInt())
                    }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = dp(96)
                wm.addView(view, lp)
                hudView = view
            }
            view.text = text
            view.visibility = View.VISIBLE
            main.removeCallbacks(hideHud)
            main.postDelayed(hideHud, 900)
        } catch (t: Throwable) {
            Log.w(TAG, "hud: ${t.message}")
        }
    }

    private fun setKeepAwake(on: Boolean) {
        val wm = windows ?: return
        try {
            if (on && awakeView == null) {
                val v = View(this)
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm.addView(v, lp)
                awakeView = v
            } else if (!on) {
                awakeView?.let { wm.removeView(it) }
                awakeView = null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "keep awake: ${t.message}")
        }
    }

    private fun removeOverlays() {
        val wm = windows ?: return
        try {
            hudView?.let { wm.removeView(it) }
            awakeView?.let { wm.removeView(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "overlay cleanup: ${t.message}")
        }
        hudView = null
        awakeView = null
    }

    companion object {
        private const val TAG = "EySnatch"

        @Volatile
        var instance: EySnatchAccessibilityService? = null
    }
}
