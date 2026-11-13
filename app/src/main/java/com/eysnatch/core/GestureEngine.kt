package com.eysnatch.core

import kotlin.math.abs
import kotlin.math.max

data class EngineConfig(
    /** > 1 triggers earlier / with smaller movements, < 1 needs bigger movements. */
    val sensitivity: Float = 1f,
    /** > 1 needs longer holds, < 1 shorter ones. */
    val holdScale: Float = 1f,
    /** Gestures that are mapped to an action; the others are ignored. */
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
        lastFaceT = t

        val n = normalizer.apply(f, follow = !leftClosed && !rightClosed)
        norm = n

        val raw = ArrayList<GestureEvent>(2)
        detectEyes(t, n, raw)
        detectDirections(t, n, raw)
        if (raw.isEmpty()) return raw

        val out = ArrayList<GestureEvent>(raw.size)
        for (e in raw) {
            if (e.gesture !in cfg.enabled) continue
            if (!e.repeat) {
                if (t - lastEventT < COOLDOWN_MS) continue
                lastEventT = t
            }
            out += e
        }
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
        for (d in dwell.values) {
            d.since = -1L
            d.lastAbove = -1L
            d.armed = true
            d.nextRepeat = Long.MAX_VALUE
        }
    }

    private fun scaled(ms: Long): Long = (ms * cfg.holdScale).toLong()

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

        val side = when {
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
                winkFired = true
                out += GestureEvent(if (side < 0) Gesture.WINK_LEFT else Gesture.WINK_RIGHT, t)
            }
        }
    }

    private fun bothReopened(start: Long, end: Long, out: MutableList<GestureEvent>) {
        val dur = end - start
        when {
            dur in BLINK_MIN_MS..BLINK_MAX_MS -> {
                if (lastBlinkEnd >= 0 && start - lastBlinkEnd <= DOUBLE_GAP_MS) {
                    lastBlinkEnd = -1L
                    out += GestureEvent(Gesture.DOUBLE_BLINK, end)
                } else {
                    lastBlinkEnd = end
                }
            }
            dur >= LONG_MIN_MS && dur < SHUT_MIN_MS -> {
                lastBlinkEnd = -1L
                out += GestureEvent(Gesture.LONG_BLINK, end)
            }
            dur in SHUT_MIN_MS..SHUT_MAX_MS -> {
                lastBlinkEnd = -1L
                out += GestureEvent(Gesture.EYES_SHUT, end)
            }
            else -> lastBlinkEnd = -1L
        }
    }

    private fun detectDirections(t: Long, n: Norm, out: MutableList<GestureEvent>) {
        val eyesOpen = !leftClosed && !rightClosed && n.closeL < OPEN_MAX && n.closeR < OPEN_MAX
        val headStill = abs(n.hx) < HEAD_STILL && abs(n.hy) < HEAD_STILL
        val trigger = (TRIGGER / cfg.sensitivity).coerceIn(0.3f, 0.95f)

        // Eyes: only the strongest direction counts, and only while both eyes are open and the head is still.
        val gl = max(0f, -n.gx)
        val gr = max(0f, n.gx)
        val gu = max(0f, n.gy)
        val gd = max(0f, -n.gy)
        val gMax = max(max(gl, gr), max(gu, gd))
        val gazeOk = eyesOpen && headStill
        val gDwell = scaled(DWELL_GAZE_MS)
        step(Gesture.LOOK_LEFT, if (gl == gMax) gl else 0f, gazeOk, t, trigger, gDwell, out)
        step(Gesture.LOOK_RIGHT, if (gr == gMax) gr else 0f, gazeOk, t, trigger, gDwell, out)
        step(Gesture.LOOK_UP, if (gu == gMax) gu else 0f, gazeOk, t, trigger, gDwell, out)
        step(Gesture.LOOK_DOWN, if (gd == gMax) gd else 0f, gazeOk, t, trigger, gDwell, out)

        // Head.
        val hl = max(0f, -n.hx)
        val hr = max(0f, n.hx)
        val hu = max(0f, n.hy)
        val hd = max(0f, -n.hy)
        val hMax = max(max(hl, hr), max(hu, hd))
        val hDwell = scaled(DWELL_HEAD_MS)
        step(Gesture.TURN_LEFT, if (hl == hMax) hl else 0f, true, t, trigger, hDwell, out)
        step(Gesture.TURN_RIGHT, if (hr == hMax) hr else 0f, true, t, trigger, hDwell, out)
        step(Gesture.TILT_UP, if (hu == hMax) hu else 0f, true, t, trigger, hDwell, out)
        step(Gesture.TILT_DOWN, if (hd == hMax) hd else 0f, true, t, trigger, hDwell, out)

        // Eyebrows: looking up also lifts the brows a little, so a strong upward glance wins.
        step(Gesture.RAISE_BROWS, n.brow, eyesOpen && gu < 0.5f, t, trigger, scaled(DWELL_BROW_MS), out)
    }

    private fun step(
        g: Gesture,
        v: Float,
        gate: Boolean,
        t: Long,
        trigger: Float,
        dwellMs: Long,
        out: MutableList<GestureEvent>,
    ) {
        val d = dwell.getValue(g)
        if (v < trigger * RELEASE_RATIO) {
            d.armed = true
            d.since = -1L
            d.nextRepeat = Long.MAX_VALUE
            return
        }
        if (!gate) {
            d.since = -1L
            return
        }
        if (v >= trigger) {
            if (d.since < 0) d.since = t
            d.lastAbove = t
            if (d.armed) {
                if (t - d.since >= dwellMs) {
                    d.armed = false
                    d.nextRepeat = t + REPEAT_DELAY_MS
                    out += GestureEvent(g, t)
                }
            } else if (g.directional && t >= d.nextRepeat) {
                d.nextRepeat = t + REPEAT_INTERVAL_MS
                out += GestureEvent(g, t, repeat = true)
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
        const val DROPOUT_MS = 160L
        const val COOLDOWN_MS = 450L
        const val FACE_LOST_RESET_MS = 350L
        const val GAP_RESET_MS = 500L
    }
}
