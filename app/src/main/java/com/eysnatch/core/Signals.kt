package com.eysnatch.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * One measurement of the face. Left/right are the *user's own* left/right (MediaPipe names them
 * from the subject's point of view and we feed it un-mirrored camera frames).
 * Values are raw: calibration turns them into comparable, normalised numbers.
 */
data class Frame(
    val timeMs: Long,
    val face: Boolean,
    val blinkL: Float = 0f,
    val blinkR: Float = 0f,
    val gazeX: Float = 0f, // + = eyes look to the user's right
    val gazeY: Float = 0f, // + = eyes look up
    val yaw: Float = 0f, // + = head turned to the user's right (nose offset / eye distance)
    val pitch: Float = 0f, // + = head tilted up
    val brow: Float = 0f,
)

/** A raw signal range: [base] is neutral, [pos] / [neg] are the raw values at the two extremes. */
data class Axis(val base: Float, val pos: Float, val neg: Float) {
    val posSpan: Float get() = pos - base
    val negSpan: Float get() = base - neg
}

data class Calibration(
    val calibrated: Boolean = false,
    /** True when the raw "left" blink channel belongs to the user's right eye. */
    val swapEyes: Boolean = false,
    val openL: Float = 0.2f,
    val closedL: Float = 0.75f,
    val openR: Float = 0.2f,
    val closedR: Float = 0.75f,
    val gazeX: Axis = Axis(0f, 0.6f, -0.6f),
    val gazeY: Axis = Axis(0f, 0.35f, -0.5f),
    val yaw: Axis = Axis(0f, 0.15f, -0.15f),
    val pitch: Axis = Axis(0f, 0.12f, -0.12f),
    val brow: Axis = Axis(0f, 0.45f, -0.45f),
) {
    fun toMap(): Map<String, Float> = mapOf(
        "cal" to if (calibrated) 1f else 0f,
        "swap" to if (swapEyes) 1f else 0f,
        "openL" to openL, "closedL" to closedL, "openR" to openR, "closedR" to closedR,
        "gx_b" to gazeX.base, "gx_p" to gazeX.pos, "gx_n" to gazeX.neg,
        "gy_b" to gazeY.base, "gy_p" to gazeY.pos, "gy_n" to gazeY.neg,
        "yw_b" to yaw.base, "yw_p" to yaw.pos, "yw_n" to yaw.neg,
        "pt_b" to pitch.base, "pt_p" to pitch.pos, "pt_n" to pitch.neg,
        "br_b" to brow.base, "br_p" to brow.pos, "br_n" to brow.neg,
    )

    companion object {
        fun fromMap(m: Map<String, Float>): Calibration {
            val d = Calibration()
            fun f(k: String, def: Float) = m[k] ?: def
            fun axis(p: String, def: Axis) = Axis(f("${p}_b", def.base), f("${p}_p", def.pos), f("${p}_n", def.neg))
            return Calibration(
                calibrated = f("cal", 0f) > 0.5f,
                swapEyes = f("swap", 0f) > 0.5f,
                openL = f("openL", d.openL), closedL = f("closedL", d.closedL),
                openR = f("openR", d.openR), closedR = f("closedR", d.closedR),
                gazeX = axis("gx", d.gazeX), gazeY = axis("gy", d.gazeY),
                yaw = axis("yw", d.yaw), pitch = axis("pt", d.pitch), brow = axis("br", d.brow),
            )
        }
    }
}

/**
 * Calibrated frame. Eye closure is 0 (open) .. 1 (closed). Every other value is 0 at the user's
 * neutral pose and +-1 at the extreme the user demonstrated during calibration.
 */
data class Norm(
    val face: Boolean,
    val closeL: Float,
    val closeR: Float,
    val gx: Float,
    val gy: Float,
    val hx: Float,
    val hy: Float,
    val brow: Float,
) {
    companion object {
        val NONE = Norm(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

/** Converts raw frames to [Norm] and slowly follows the user's resting pose (phone moves, user shifts). */
class Normalizer(private val cal: Calibration) {
    private class Tracked(axis: Axis, minSpan: Float) {
        var base = axis.base
        private val posSpan = safe(axis.posSpan, minSpan)
        private val negSpan = safe(axis.negSpan, minSpan)

        fun norm(x: Float): Float {
            val d = x - base
            return if (d * posSpan >= 0f) d / posSpan else d / negSpan
        }

        fun follow(x: Float, n: Float, k: Float) {
            if (abs(n) < 0.3f) base += (x - base) * k
        }

        private fun safe(span: Float, min: Float): Float =
            if (abs(span) >= min) span else (if (span < 0f) -min else min)
    }

    private val gx = Tracked(cal.gazeX, 0.08f)
    private val gy = Tracked(cal.gazeY, 0.08f)
    private val yaw = Tracked(cal.yaw, 0.03f)
    private val pitch = Tracked(cal.pitch, 0.03f)
    private val brow = Tracked(cal.brow, 0.08f)
    private var started = false
    private var lastT = -1L

    fun apply(f: Frame, follow: Boolean): Norm {
        if (!started) {
            started = true
            if (!cal.calibrated) {
                gx.base = f.gazeX; gy.base = f.gazeY; yaw.base = f.yaw; pitch.base = f.pitch; brow.base = f.brow
            }
        }
        val dt = if (lastT < 0) 0L else (f.timeMs - lastT).coerceIn(0L, 500L)
        lastT = f.timeMs

        val rawL = if (cal.swapEyes) f.blinkR else f.blinkL
        val rawR = if (cal.swapEyes) f.blinkL else f.blinkR
        val n = Norm(
            face = true,
            closeL = closure(rawL, cal.openL, cal.closedL),
            closeR = closure(rawR, cal.openR, cal.closedR),
            gx = gx.norm(f.gazeX), gy = gy.norm(f.gazeY),
            hx = yaw.norm(f.yaw), hy = pitch.norm(f.pitch),
            brow = brow.norm(f.brow),
        )
        if (follow && dt > 0) {
            val k = 1f - exp(-dt / FOLLOW_MS)
            gx.follow(f.gazeX, n.gx, k); gy.follow(f.gazeY, n.gy, k)
            yaw.follow(f.yaw, n.hx, k); pitch.follow(f.pitch, n.hy, k)
            brow.follow(f.brow, n.brow, k)
        }
        return n
    }

    private fun closure(raw: Float, open: Float, closed: Float): Float {
        val span = if (abs(closed - open) < 0.15f) 0.15f else closed - open
        return ((raw - open) / span).coerceIn(0f, 1.5f)
    }

    companion object {
        private const val FOLLOW_MS = 10_000f
    }
}

class FaceLandmarks(val x: FloatArray, val y: FloatArray, val width: Int, val height: Int)

/** Turns MediaPipe blendshape scores + landmarks into a [Frame]. Pure Kotlin so it can be unit-tested. */
object FeatureExtractor {
    private const val NOSE = 1
    private const val RIGHT_EYE_OUTER = 33 // the subject's right eye (left side of an un-mirrored image)
    private const val LEFT_EYE_OUTER = 263
    private const val LIP_TOP = 13
    private const val LIP_BOTTOM = 14
    private const val MIN_EYE_DISTANCE_PX = 20f

    fun frame(timeMs: Long, score: (String) -> Float, lm: FaceLandmarks?): Frame {
        if (lm == null || lm.x.size <= LEFT_EYE_OUTER) return Frame(timeMs, false)
        val pose = headPose(lm) ?: return Frame(timeMs, false)

        val gazeRight = (score("eyeLookOutRight") + score("eyeLookInLeft")) / 2f
        val gazeLeft = (score("eyeLookOutLeft") + score("eyeLookInRight")) / 2f
        val up = (score("eyeLookUpLeft") + score("eyeLookUpRight")) / 2f
        val down = (score("eyeLookDownLeft") + score("eyeLookDownRight")) / 2f
        val brow = (score("browInnerUp") + score("browOuterUpLeft") + score("browOuterUpRight")) / 3f
        return Frame(
            timeMs = timeMs,
            face = true,
            blinkL = score("eyeBlinkLeft"),
            blinkR = score("eyeBlinkRight"),
            gazeX = gazeRight - gazeLeft,
            gazeY = up - down,
            yaw = pose.first,
            pitch = pose.second,
            brow = brow,
        )
    }

    /** Returns (yaw, pitch) proxies measured in eye-distances, or null if the face is unusable. */
    fun headPose(lm: FaceLandmarks): Pair<Float, Float>? {
        val w = lm.width.toFloat()
        val h = lm.height.toFloat()
        fun px(i: Int) = lm.x[i] * w
        fun py(i: Int) = lm.y[i] * h

        val ex = px(LEFT_EYE_OUTER) - px(RIGHT_EYE_OUTER)
        val ey = py(LEFT_EYE_OUTER) - py(RIGHT_EYE_OUTER)
        val eyeDist = hypot(ex, ey)
        if (eyeDist < MIN_EYE_DISTANCE_PX) return null
        val ux = ex / eyeDist
        val uy = ey / eyeDist

        val mx = (px(LEFT_EYE_OUTER) + px(RIGHT_EYE_OUTER)) / 2f
        val my = (py(LEFT_EYE_OUTER) + py(RIGHT_EYE_OUTER)) / 2f
        val nx = px(NOSE) - mx
        val ny = py(NOSE) - my

        // Nose sliding towards the image-right (= the user's left) means the head turned left.
        val yawRight = -(nx * ux + ny * uy) / eyeDist

        // Nose position along the eyes -> mouth line (about 0.55). It shrinks when the head tilts up.
        val ax = (px(LIP_TOP) + px(LIP_BOTTOM)) / 2f - mx
        val ay = (py(LIP_TOP) + py(LIP_BOTTOM)) / 2f - my
        val len2 = ax * ax + ay * ay
        if (len2 < 1f) return null
        val ratio = (nx * ax + ny * ay) / len2
        return Pair(yawRight, -ratio)
    }
}
