package com.eysnatch.core

import kotlin.math.abs
import kotlin.math.max

/** The guided calibration, one pose at a time. */
enum class CalStep(val title: String, val prompt: String, val head: Boolean = false) {
    NEUTRAL("Look at the screen", "Relax and look at the screen the way you normally would."),
    BOTH_CLOSED("Close both eyes", "Close both eyes gently and keep them shut."),
    WINK_LEFT("Wink your LEFT eye", "Close only your LEFT eye. Keep the right eye open."),
    WINK_RIGHT("Wink your RIGHT eye", "Close only your RIGHT eye. Keep the left eye open."),
    LOOK_LEFT("Look LEFT", "Keep your head still. Look as far to your left as you can, using only your eyes."),
    LOOK_RIGHT("Look RIGHT", "Keep your head still. Look as far to your right as you can, using only your eyes."),
    LOOK_UP("Look UP", "Keep your head still. Look as high as you can, using only your eyes."),
    LOOK_DOWN("Look DOWN", "Keep your head still. Look as low as you can, using only your eyes."),
    BROWS("Raise your eyebrows", "Lift both eyebrows as high as you can and hold them there."),
    TURN_LEFT("Turn your head LEFT", "Turn your head about 25 degrees to your left and hold it.", head = true),
    TURN_RIGHT("Turn your head RIGHT", "Turn your head about 25 degrees to your right and hold it.", head = true),
    TILT_UP("Tilt your head UP", "Tilt your head up about 20 degrees and hold it.", head = true),
    TILT_DOWN("Tilt your head DOWN", "Tilt your head down about 20 degrees and hold it.", head = true),
}

data class Summary(
    val count: Int,
    val blinkL: Float,
    val blinkR: Float,
    val gazeX: Float,
    val gazeY: Float,
    val yaw: Float,
    val pitch: Float,
    val brow: Float,
)

data class CalibrationResult(
    val calibration: Calibration,
    val report: List<String>,
    val warnings: List<String>,
)

/**
 * Learns the user's personal numbers from a few demonstrated poses. Ranges that were not demonstrated
 * keep their previous shape (only re-centred on the new resting pose).
 */
class Calibrator(private val previous: Calibration = Calibration()) {
    private val samples = ArrayList<Frame>()
    private var total = 0
    private val summaries = LinkedHashMap<CalStep, Summary>()

    val sampleCount: Int get() = samples.size

    fun startStep() {
        samples.clear()
        total = 0
    }

    /** Feed every frame of the capture window, also those without a face (they count against coverage). */
    fun add(f: Frame) {
        total += 1
        if (f.face) samples.add(f)
    }

    fun has(step: CalStep): Boolean = summaries.containsKey(step)

    /** Stores the pose measured since [startStep]; false if the face was not seen for most of it. */
    fun finishStep(step: CalStep): Boolean {
        val enough = samples.size >= MIN_SAMPLES && samples.size >= MIN_COVERAGE * total
        if (!enough) {
            startStep()
            return false
        }
        val usable = samples.drop(samples.size / 4)
        summaries[step] = Summary(
            count = usable.size,
            blinkL = median(usable.map { it.blinkL }),
            blinkR = median(usable.map { it.blinkR }),
            gazeX = median(usable.map { it.gazeX }),
            gazeY = median(usable.map { it.gazeY }),
            yaw = median(usable.map { it.yaw }),
            pitch = median(usable.map { it.pitch }),
            brow = median(usable.map { it.brow }),
        )
        startStep()
        return true
    }

    fun build(): CalibrationResult {
        val rep = ArrayList<String>()
        val warn = ArrayList<String>()
        val neutral = summaries[CalStep.NEUTRAL]
            ?: return CalibrationResult(previous, rep, listOf("The resting pose is needed first - nothing was changed."))

        var cal = previous.copy(calibrated = true)

        // ---- Eyes. Both winks must agree before the left/right blink channels are swapped, so one
        // clumsy wink can never flip them.
        val leftWink = summaries[CalStep.WINK_LEFT]
        val rightWink = summaries[CalStep.WINK_RIGHT]
        val bothClosed = summaries[CalStep.BOTH_CLOSED]

        fun vote(s: Summary?, userLeft: Boolean): Int {
            if (s == null) return 0
            val a = s.blinkL - neutral.blinkL
            val b = s.blinkR - neutral.blinkR
            val diff = if (userLeft) a - b else b - a
            return when {
                diff > WINK_MARGIN -> 1
                diff < -WINK_MARGIN -> -1
                else -> 0
            }
        }

        val vl = vote(leftWink, true)
        val vr = vote(rightWink, false)
        val swap = if (vl != 0 && vl == vr) vl < 0 else previous.swapEyes
        if (leftWink != null) {
            if (vl != 0 && (vl < 0) == swap) rep += "Left wink: recognised"
            else warn += "Left wink was not clearly recognised. Blinks and eye movements still work; you can map winks to nothing."
        }
        if (rightWink != null) {
            if (vr != 0 && (vr < 0) == swap) rep += "Right wink: recognised"
            else warn += "Right wink was not clearly recognised. Blinks and eye movements still work; you can map winks to nothing."
        }

        val openL = if (swap) neutral.blinkR else neutral.blinkL
        val openR = if (swap) neutral.blinkL else neutral.blinkR
        var closedL: Float
        var closedR: Float
        if (bothClosed != null) {
            closedL = if (swap) bothClosed.blinkR else bothClosed.blinkL
            closedR = if (swap) bothClosed.blinkL else bothClosed.blinkR
            if (closedL - openL < MIN_CLOSE_RISE || closedR - openR < MIN_CLOSE_RISE) {
                warn += "Closed eyes were hard to detect (glare on glasses or dim light?). Try again facing a light."
                closedL = max(closedL, openL + 0.4f)
                closedR = max(closedR, openR + 0.4f)
            } else {
                rep += "Blink: recognised"
            }
        } else {
            closedL = max(previous.closedL, openL + 0.4f)
            closedR = max(previous.closedR, openR + 0.4f)
        }
        cal = cal.copy(swapEyes = swap, openL = openL, closedL = closedL, openR = openR, closedR = closedR)

        // ---- Direction axes: both extremes are measured, so left/right/up/down signs are learned.
        fun axis(
            name: String,
            posStep: CalStep,
            negStep: CalStep,
            pick: (Summary) -> Float,
            minSpan: Float,
            previousAxis: Axis,
            typical: Axis,
            neutralValue: Float,
        ): Axis {
            val keep = previousAxis.recentered(neutralValue)
            val p = summaries[posStep]?.let(pick)
            val n = summaries[negStep]?.let(pick)
            if (p == null && n == null) return keep
            if (p == null || n == null) {
                warn += "$name: both directions are needed - kept the previous range."
                return keep
            }
            val dp = p - neutralValue
            val dn = neutralValue - n
            if (dp * dn <= 0f) {
                warn += "$name: the two directions looked the same. Redo it with bigger, clearer movements."
                return keep
            }
            var pos = p
            var neg = n
            if (abs(dp) < minSpan) {
                pos = neutralValue + (if (dp < 0f) -1f else 1f) * abs(typical.posSpan)
                warn += "$name: one direction was weak - used a typical value. Try a bigger movement."
            }
            if (abs(dn) < minSpan) {
                neg = neutralValue - (if (dn < 0f) -1f else 1f) * abs(typical.negSpan)
                warn += "$name: one direction was weak - used a typical value. Try a bigger movement."
            }
            rep += "$name: recognised"
            return Axis(neutralValue, pos, neg)
        }

        val typical = Calibration()
        cal = cal.copy(
            gazeX = axis("Look left/right", CalStep.LOOK_RIGHT, CalStep.LOOK_LEFT, { it.gazeX }, 0.10f, cal.gazeX, typical.gazeX, neutral.gazeX),
            gazeY = axis("Look up/down", CalStep.LOOK_UP, CalStep.LOOK_DOWN, { it.gazeY }, 0.08f, cal.gazeY, typical.gazeY, neutral.gazeY),
            yaw = axis("Turn head", CalStep.TURN_RIGHT, CalStep.TURN_LEFT, { it.yaw }, 0.04f, cal.yaw, typical.yaw, neutral.yaw),
            pitch = axis("Tilt head", CalStep.TILT_UP, CalStep.TILT_DOWN, { it.pitch }, 0.03f, cal.pitch, typical.pitch, neutral.pitch),
        )

        // ---- Eyebrows
        cal = cal.copy(brow = cal.brow.recentered(neutral.brow))
        summaries[CalStep.BROWS]?.let { b ->
            val rise = b.brow - neutral.brow
            if (rise >= MIN_BROW_RISE) {
                cal = cal.copy(brow = Axis(neutral.brow, b.brow, neutral.brow - rise))
                rep += "Eyebrow raise: recognised"
                val up = summaries[CalStep.LOOK_UP]
                if (up != null && (up.brow - neutral.brow) > 0.5f * rise) {
                    warn += "Looking up also raises your brows a lot, so 'Raise eyebrows' and 'Look up' may trigger together. Map only one of them."
                }
            } else {
                warn += "Eyebrow raise was weak. Try lifting them higher, or map that gesture to nothing."
            }
        }

        return CalibrationResult(cal, rep, warn)
    }

    private fun Axis.recentered(base: Float): Axis = Axis(base, base + posSpan, base - negSpan)

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    companion object {
        const val MIN_SAMPLES = 8
        const val MIN_COVERAGE = 0.6f
        const val WINK_MARGIN = 0.25f
        const val MIN_CLOSE_RISE = 0.25f
        const val MIN_BROW_RISE = 0.10f
    }
}
