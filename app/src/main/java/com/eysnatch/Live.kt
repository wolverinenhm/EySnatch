package com.eysnatch

import android.graphics.Bitmap
import com.eysnatch.core.Frame
import com.eysnatch.core.Norm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** State shared between the camera service and the screens that display it (same process). */
object Live {
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var status = "Stopped"
    @Volatile var error: String? = null
    @Volatile var frame: Frame? = null
    @Volatile var norm: Norm = Norm.NONE
    @Volatile var thumb: Bitmap? = null
    @Volatile var landmarks: FloatArray? = null
    @Volatile var fps = 0f
    @Volatile var framesTotal = 0L
    @Volatile var calibrating = false

    /** Called on the tracker thread for every frame (used by the calibration wizard). */
    @Volatile var frameListener: ((Frame) -> Unit)? = null

    private val uiCount = AtomicInteger(0)
    val uiVisible: Boolean get() = uiCount.get() > 0

    fun uiStarted() {
        uiCount.incrementAndGet()
    }

    fun uiStopped() {
        uiCount.decrementAndGet()
    }

    private val log = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun note(msg: String) {
        synchronized(log) {
            log.addFirst("${clock.format(Date())}  $msg")
            while (log.size > 8) log.removeLast()
        }
    }

    fun recent(): List<String> = synchronized(log) { log.toList() }

    fun resetSession() {
        error = null
        paused = false
        frame = null
        norm = Norm.NONE
        thumb = null
        landmarks = null
        fps = 0f
        framesTotal = 0L
    }
}
