package com.eysnatch.core

import kotlin.math.abs
import kotlin.math.max

data class EngineConfig(
    /** > 1 triggers earlier / with smaller movements, < 1 needs bigger movements. */
    val sensitivity: Float = 1f,
    /** > 1 needs longer holds, < 1 shorter ones. */
    val holdScale: Float = 1f,
    /** Gestures that are mapped to an action; the others are ignored completely. */
    val enabled: Set<Gesture> = Gesture.values().toSet(),
)

/**
 * Turns a stream of face frames into gesture events. Not thread-safe: feed it from one thread.
 * Everything is driven by the frame timestamps, so it behaves identically at 10 or 30 fps.
 */
class GestureEngine(config: EngineConfig = EngineConfig(), calibration: Calibration = Calibration()) {

    @Volatile
    var norm: Norm = Norm.NONE
        private set

    private var cfg = config
    private var cal = calibration
    private var normalizer = Normalizer(calibration)

    private var lastT = -1L
    private var lastFaceT = -1L
    private var lastEventT = -COOLDOWN_MS

    private var leftClosed = false
    private var rightClosed = false
    private var bothSince = -1L
    private var lastBlinkEnd = -1L
    private var winkSince = -1L
    private var winkSide = 0
    private var winkFired = false

    /** After a real both-eyes closure, one eye reopening must not turn the other still-closed eye into a wink. */
    private var winkBlocked = false

    private class Dwell {
        var since = -1L
        var lastAbove = -1L
        var armed = true
        var nextRepeat = Long.MAX_VALUE
    }

    private val dwell: Map<Gesture, Dwell> =
        Gesture.values().filter { it.directional || it == Gesture.RAISE_BROWS }.associateWith { Dwell() }

    fun configure(config: EngineConfig, calibration: Calibration) {
        cfg = config
        if (calibration != cal) {
            cal = calibration
            normalizer = Normalizer(calibration)
        }
        resetTransient()
    }

    fun onFrame(f: Frame): List<GestureEvent> {
        val t = f.timeMs
        if (lastT >= 0 && t - lastT > GAP_RESET_MS) resetTransient()
        lastT = t

        if (!f.face) {
            norm = Norm.NONE
            if (lastFaceT >= 0 && t - lastFaceT > FACE_LOST_RESET_MS) resetTransient()
            return emptyList()
        }
        // A face that comes back after being gone for a while must not inherit half-finished gestures.
        if (lastFaceT >= 0 && t - lastFaceT > FACE_LOST_RESET_MS) resetTransient()
        lastFaceT = t

        val n = normalizer.apply(f, follow = !leftClosed && !rightClosed)
        norm = n

        val out = ArrayList<GestureEvent>(2)
        detectEyes(t, n, out)
        detectDirections(t, n, out)
        return out
    }

    private fun resetTransient() {
        leftClosed = false
        rightClosed = false
        bothSince = -1L
        lastBlinkEnd = -1L
        winkSince = -1L
        winkSide = 0
        winkFired = false
        winkBlocked = false
        for (d in dwell.values) {
            d.since = -1L
            d.lastAbove = -1L
            d.armed = true
            d.nextRepeat = Long.MAX_VALUE
        }
    }

    private fun scaled(ms: Long): Long = (ms * cfg.holdScale).toLong()

    /**
     * The only place events are created. A gesture that is switched off never counts, and a gesture that
     * is ready while another one is cooling down simply waits: the caller keeps its state and retries.
     */
    private fun fire(g: Gesture, t: Long, repeat: Boolean, out: MutableList<GestureEvent>): Boolean {
        if (g !in cfg.enabled) return false
        if (t - lastEventT < COOLDOWN_MS) return false
        lastEventT = t
        out += GestureEvent(g, t, repeat)
        return true
    }

    private fun detectEyes(t: Long, n: Norm, out: MutableList<GestureEvent>) {
        leftClosed = if (leftClosed) n.closeL > CLOSE_OFF else n.closeL >= CLOSE_ON
        rightClosed = if (rightClosed) n.closeR > CLOSE_OFF else n.closeR >= CLOSE_ON

        if (leftClosed && rightClosed) {
            if (bothSince < 0) bothSince = t
        } else if (bothSince >= 0) {
            val start = bothSince
            bothSince = -1L
            bothReopened(start, t, out)
        }
        if (!leftClosed && !rightClosed) winkBlocked = false

        val side = when {
            winkBlocked -> 0
            leftClosed && !rightClosed && n.closeR < WINK_OTHER_MAX -> -1
            rightClosed && !leftClosed && n.closeL < WINK_OTHER_MAX -> 1
            else -> 0
        }
        if (side == 0) {
            winkSince = -1L
            winkSide = 0
            winkFired = false
        } else {
            if (side != winkSide || winkSince < 0) {
                winkSide = side
                winkSince = t
                winkFired = false
            }
            if (!winkFired && t - winkSince >= scaled(WINK_MS)) {
                val g = if (side < 0) Gesture.WINK_LEFT else Gesture.WINK_RIGHT
                if (fire(g, t, false, out)) winkFired = true
            }
        }
    }

    private fun bothReopened(start: Long, end: Long, out: MutableList<GestureEvent>) {
        val dur = end - start
        if (dur >= BLINK_MIN_MS) winkBlocked = true
        when {
            dur in BLINK_MIN_MS..BLINK_MAX_MS -> {
                if (lastBlinkEnd >= 0 && start - lastBlinkEnd <= DOUBLE_GAP_MS) {
                    lastBlinkEnd = -1L
                    fire(Gesture.DOUBLE_BLINK, end, false, out)
                } else {
                    lastBlinkEnd = end
                }
            }
            dur >= LONG_MIN_MS && dur < SHUT_MIN_MS -> {
                lastBlinkEnd = -1L
                fire(Gesture.LONG_BLINK, end, false, out)
            }
            dur in SHUT_MIN_MS..SHUT_MAX_MS -> {
                lastBlinkEnd = -1L
                fire(Gesture.EYES_SHUT, end, false, out)
            }
            else -> lastBlinkEnd = -1L
        }
    }

    private fun detectDirections(t: Long, n: Norm, out: MutableList<GestureEvent>) {
        val eyesOpen = !leftClosed && !rightClosed && n.closeL < OPEN_MAX && n.closeR < OPEN_MAX
        val headStill = abs(n.hx) < HEAD_STILL && abs(n.hy) < HEAD_STILL
        val trigger = (TRIGGER / cfg.sensitivity).coerceIn(0.3f, 0.95f)

        // Eyes: only the strongest direction triggers, and only while both eyes are open and the head is still.
        val gl = max(0f, -n.gx)
        val gr = max(0f, n.gx)
        val gu = max(0f, n.gy)
        val gd = max(0f, -n.gy)
        val gMax = max(max(gl, gr), max(gu, gd))
        val gazeOk = eyesOpen && headStill
        val gDwell = scaled(DWELL_GAZE_MS)
        step(Gesture.LOOK_LEFT, if (gl == gMax) gl else 0f, gl, gazeOk, t, trigger, gDwell, out)
        step(Gesture.LOOK_RIGHT, if (gr == gMax) gr else 0f, gr, gazeOk, t, trigger, gDwell, out)
        step(Gesture.LOOK_UP, if (gu == gMax) gu else 0f, gu, gazeOk, t, trigger, gDwell, out)
        step(Gesture.LOOK_DOWN, if (gd == gMax) gd else 0f, gd, gazeOk, t, trigger, gDwell, out)

        // Head.
        val hl = max(0f, -n.hx)
        val hr = max(0f, n.hx)
        val hu = max(0f, n.hy)
        val hd = max(0f, -n.hy)
        val hMax = max(max(hl, hr), max(hu, hd))
        val hDwell = scaled(DWELL_HEAD_MS)
        step(Gesture.TURN_LEFT, if (hl == hMax) hl else 0f, hl, true, t, trigger, hDwell, out)
        step(Gesture.TURN_RIGHT, if (hr == hMax) hr else 0f, hr, true, t, trigger, hDwell, out)
        step(Gesture.TILT_UP, if (hu == hMax) hu else 0f, hu, true, t, trigger, hDwell, out)
        step(Gesture.TILT_DOWN, if (hd == hMax) hd else 0f, hd, true, t, trigger, hDwell, out)

        // Eyebrows: looking up also lifts the brows a little, so a strong upward glance wins.
        step(Gesture.RAISE_BROWS, n.brow, n.brow, eyesOpen && gu < 0.5f, t, trigger, scaled(DWELL_BROW_MS), out)
    }

    /**
     * [value] is the strength that may trigger (0 when another direction is stronger), [raw] is the real
     * strength of this direction, used for letting go. [gate] is false while the measurement is unreliable
     * (eyes closed, head moving): then nothing changes except that the hold timer restarts.
     */
    private fun step(
        g: Gesture,
        value: Float,
        raw: Float,
        gate: Boolean,
        t: Long,
        trigger: Float,
        dwellMs: Long,
        out: MutableList<GestureEvent>,
    ) {
        val d = dwell.getValue(g)
        if (g !in cfg.enabled) {
            d.since = -1L
            return
        }
        if (!gate) {
            d.since = -1L
            return
        }
        if (raw < trigger * RELEASE_RATIO) {
            d.armed = true
            d.since = -1L
            d.nextRepeat = Long.MAX_VALUE
            return
        }
        if (value >= trigger) {
            if (d.since < 0) d.since = t
            d.lastAbove = t
            if (d.armed) {
                if (t - d.since >= dwellMs && fire(g, t, false, out)) {
                    d.armed = false
                    d.nextRepeat = t + REPEAT_DELAY_MS
                }
            } else if (g.directional && t >= d.nextRepeat) {
                d.nextRepeat = if (fire(g, t, true, out)) t + REPEAT_INTERVAL_MS else t + RETRY_MS
            }
        } else if (d.since >= 0 && t - d.lastAbove > DROPOUT_MS) {
            d.since = -1L
        }
    }

    companion object {
        const val CLOSE_ON = 0.6f
        const val CLOSE_OFF = 0.4f
        const val WINK_OTHER_MAX = 0.5f
        const val OPEN_MAX = 0.45f
        const val TRIGGER = 0.6f
        const val RELEASE_RATIO = 0.5f
        const val HEAD_STILL = 0.7f

        const val DWELL_GAZE_MS = 450L
        const val DWELL_HEAD_MS = 350L
        const val DWELL_BROW_MS = 450L
        const val WINK_MS = 350L
        const val BLINK_MIN_MS = 130L
        const val BLINK_MAX_MS = 450L
        const val DOUBLE_GAP_MS = 600L
        const val LONG_MIN_MS = 650L
        const val SHUT_MIN_MS = 1800L
        const val SHUT_MAX_MS = 7000L
        const val REPEAT_DELAY_MS = 1000L
        const val REPEAT_INTERVAL_MS = 700L
        const val RETRY_MS = 100L
        const val DROPOUT_MS = 160L
        const val COOLDOWN_MS = 450L
        const val FACE_LOST_RESET_MS = 350L
        const val GAP_RESET_MS = 500L
    }
}
