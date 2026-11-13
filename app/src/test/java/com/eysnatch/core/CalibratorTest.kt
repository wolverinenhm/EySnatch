package com.eysnatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorTest {

    private fun frames(n: Int = 40, build: (Int) -> Frame): List<Frame> = List(n) { build(it) }

    private fun Calibrator.pose(step: CalStep, blinkL: Float = 0.1f, blinkR: Float = 0.1f, gx: Float = 0f, gy: Float = 0f,
                                yaw: Float = 0f, pitch: Float = 0f, brow: Float = 0.05f) {
        startStep()
        frames { Frame(it * 33L, true, blinkL, blinkR, gx, gy, yaw, pitch, brow) }.forEach { add(it) }
        assertTrue(finishStep(step))
    }

    @Test
    fun needsEnoughFrames() {
        val c = Calibrator()
        c.startStep()
        repeat(3) { c.add(Frame(it * 33L, true)) }
        assertFalse(c.finishStep(CalStep.NEUTRAL))
        c.startStep()
        repeat(40) { c.add(Frame(it * 33L, false)) }
        assertFalse("frames without a face do not count", c.finishStep(CalStep.NEUTRAL))
    }

    @Test
    fun withoutRestingPoseNothingChanges() {
        val prev = Calibration(calibrated = true, swapEyes = true)
        val r = Calibrator(prev).build()
        assertEquals(prev, r.calibration)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun learnsWhichChannelIsWhichEye() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL, blinkL = 0.1f, blinkR = 0.1f)
        c.pose(CalStep.BOTH_CLOSED, blinkL = 0.8f, blinkR = 0.85f)
        c.pose(CalStep.WINK_LEFT, blinkL = 0.1f, blinkR = 0.8f) // the user's LEFT eye drives the raw RIGHT channel
        c.pose(CalStep.WINK_RIGHT, blinkL = 0.8f, blinkR = 0.1f)
        val r = c.build()
        assertTrue(r.calibration.swapEyes)
        assertEquals(0.85f, r.calibration.closedL, 0.001f)
        assertEquals(0.8f, r.calibration.closedR, 0.001f)
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())

        val s = GestureEngine(EngineConfig(), r.calibration)
        var t = 0L
        val out = ArrayList<GestureEvent>()
        fun run(ms: Int, l: Float, rr: Float) = repeat(ms / 33) { t += 33; out += s.onFrame(Frame(t, true, l, rr)) }
        run(1000, 0.1f, 0.1f); run(700, 0.1f, 0.85f); run(800, 0.1f, 0.1f)
        assertEquals(listOf(Gesture.WINK_LEFT), out.map { it.gesture })
    }

    @Test
    fun standardEyesAreDetectedToo() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL)
        c.pose(CalStep.WINK_LEFT, blinkL = 0.9f, blinkR = 0.1f)
        c.pose(CalStep.WINK_RIGHT, blinkL = 0.1f, blinkR = 0.9f)
        val r = c.build()
        assertFalse(r.calibration.swapEyes)
        assertTrue(r.report.any { it.contains("Left wink") })
    }

    @Test
    fun unclearWinksProduceAWarning() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL)
        c.pose(CalStep.WINK_LEFT, blinkL = 0.7f, blinkR = 0.65f)
        c.pose(CalStep.WINK_RIGHT, blinkL = 0.7f, blinkR = 0.65f)
        val r = c.build()
        assertEquals(2, r.warnings.count { it.contains("wink") })
    }

    @Test
    fun learnsFlippedGazeDirections() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL, gx = 0.02f)
        c.pose(CalStep.LOOK_LEFT, gx = 0.5f) // the model reports "left" as positive on this setup
        c.pose(CalStep.LOOK_RIGHT, gx = -0.45f)
        c.pose(CalStep.LOOK_UP, gy = 0.3f)
        c.pose(CalStep.LOOK_DOWN, gy = -0.5f)
        val r = c.build()
        assertEquals(0.02f, r.calibration.gazeX.base, 0.001f)

        val s = GestureEngine(EngineConfig(), r.calibration)
        var t = 0L
        val out = ArrayList<GestureEvent>()
        fun run(ms: Int, gx: Float) = repeat(ms / 33) { t += 33; out += s.onFrame(Frame(t, true, 0.05f, 0.05f, gx, 0f)) }
        run(1000, 0.02f); run(800, 0.5f); run(600, 0.02f); run(800, -0.45f); run(600, 0.02f)
        assertEquals(listOf(Gesture.LOOK_LEFT, Gesture.LOOK_RIGHT), out.filter { !it.repeat }.map { it.gesture })
    }

    @Test
    fun identicalDirectionsAreRejected() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL)
        c.pose(CalStep.LOOK_LEFT, gx = 0.4f)
        c.pose(CalStep.LOOK_RIGHT, gx = 0.45f)
        val r = c.build()
        assertTrue(r.warnings.any { it.contains("Look left/right") })
        assertEquals(Calibration().gazeX.pos, r.calibration.gazeX.pos, 0.0001f)
    }

    @Test
    fun weakMovementFallsBackToATypicalRange() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL)
        c.pose(CalStep.LOOK_LEFT, gx = -0.02f)
        c.pose(CalStep.LOOK_RIGHT, gx = 0.5f)
        val r = c.build()
        assertTrue(r.warnings.isNotEmpty())
        assertEquals(-0.6f, r.calibration.gazeX.neg, 0.001f)
    }

    @Test
    fun eyebrowRangeIsLearned() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL, brow = 0.05f)
        c.pose(CalStep.BROWS, brow = 0.7f)
        val r = c.build()
        assertEquals(0.7f, r.calibration.brow.pos, 0.001f)
    }

    @Test
    fun lookUpThatAlsoRaisesBrowsIsFlagged() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL, brow = 0.05f)
        c.pose(CalStep.LOOK_UP, gy = 0.4f, brow = 0.6f)
        c.pose(CalStep.BROWS, brow = 0.7f)
        assertTrue(c.build().warnings.any { it.contains("Look up") })
    }

    @Test
    fun headOnlyRunKeepsTheEyeCalibration() {
        val prev = Calibration(calibrated = true, swapEyes = true, openL = 0.3f, closedL = 0.9f)
        val c = Calibrator(prev)
        c.pose(CalStep.NEUTRAL, yaw = 0.01f, pitch = -0.5f)
        c.pose(CalStep.TURN_LEFT, yaw = -0.2f, pitch = -0.5f)
        c.pose(CalStep.TURN_RIGHT, yaw = 0.2f, pitch = -0.5f)
        c.pose(CalStep.TILT_UP, yaw = 0.01f, pitch = -0.38f)
        c.pose(CalStep.TILT_DOWN, yaw = 0.01f, pitch = -0.62f)
        val r = c.build().calibration
        assertTrue(r.swapEyes)
        assertEquals(0.9f, r.closedL, 0.0001f)
        assertEquals(0.2f, r.yaw.pos, 0.001f)
        assertEquals(-0.5f, r.pitch.base, 0.001f)
        assertEquals(-0.38f, r.pitch.pos, 0.001f)
    }

    @Test
    fun eyeOnlyRunKeepsTheHeadRangesUsable() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL, yaw = 0.1f, pitch = -0.55f)
        c.pose(CalStep.BOTH_CLOSED, blinkL = 0.8f, blinkR = 0.8f)
        val cal = c.build().calibration
        assertEquals(-0.55f, cal.pitch.base, 0.0001f)
        assertTrue("pitch ranges must stay on opposite sides of the resting pose", cal.pitch.posSpan > 0f && cal.pitch.negSpan > 0f)
        assertTrue(cal.yaw.posSpan > 0f && cal.yaw.negSpan > 0f)

        val n = Normalizer(cal)
        assertEquals(1f, n.apply(Frame(0, true, pitch = -0.55f + cal.pitch.posSpan), false).hy, 0.01f)
        assertEquals(-1f, n.apply(Frame(33, true, pitch = -0.55f - cal.pitch.negSpan), false).hy, 0.01f)
    }

    @Test
    fun aSingleContradictoryWinkDoesNotFlipTheEyes() {
        val c = Calibrator()
        c.pose(CalStep.NEUTRAL)
        c.pose(CalStep.WINK_LEFT, blinkL = 0.1f, blinkR = 0.9f) // user slipped and winked the wrong eye
        val r = c.build()
        assertFalse(r.calibration.swapEyes)
        assertTrue(r.warnings.any { it.contains("Left wink") })
    }

    @Test
    fun skippingTheClosedEyesPoseKeepsThePreviousClosedLevel() {
        val prev = Calibration(calibrated = true, openL = 0.1f, closedL = 0.9f, openR = 0.1f, closedR = 0.88f)
        val c = Calibrator(prev)
        c.pose(CalStep.NEUTRAL, blinkL = 0.12f, blinkR = 0.12f)
        c.pose(CalStep.WINK_LEFT, blinkL = 0.9f, blinkR = 0.1f)
        c.pose(CalStep.WINK_RIGHT, blinkL = 0.1f, blinkR = 0.9f)
        val r = c.build().calibration
        assertEquals(0.9f, r.closedL, 0.0001f)
        assertEquals(0.88f, r.closedR, 0.0001f)
        assertEquals(0.12f, r.openL, 0.0001f)
    }

    @Test
    fun aPoseWhereTheFaceIsMostlyMissingIsRejected() {
        val c = Calibrator()
        c.startStep()
        repeat(10) { c.add(Frame(it * 33L, true)) }
        repeat(40) { c.add(Frame((10 + it) * 33L, false)) }
        assertFalse(c.finishStep(CalStep.NEUTRAL))
    }

    @Test
    fun calibrationSurvivesStorage() {
        val c = Calibration(
            calibrated = true, swapEyes = true, openL = 0.11f, closedL = 0.81f,
            gazeX = Axis(0.02f, -0.5f, 0.45f), brow = Axis(0.1f, 0.6f, -0.4f),
        )
        assertEquals(c, Calibration.fromMap(c.toMap()))
        assertEquals(Calibration(), Calibration.fromMap(emptyMap()))
    }
}

class FeatureExtractorTest {

    private fun face(noseX: Float = 0.5f, noseY: Float = 0.5f, size: Int = 640): FaceLandmarks {
        val x = FloatArray(478)
        val y = FloatArray(478)
        x[33] = 0.40f; y[33] = 0.40f // subject's right eye (image-left)
        x[263] = 0.60f; y[263] = 0.40f // subject's left eye (image-right)
        x[1] = noseX; y[1] = noseY
        x[13] = 0.50f; y[13] = 0.60f
        x[14] = 0.50f; y[14] = 0.61f
        return FaceLandmarks(x, y, size, size)
    }

    @Test
    fun noseTowardsImageLeftIsHeadTurnedToTheUsersRight() {
        val centre = FeatureExtractor.headPose(face())!!
        val right = FeatureExtractor.headPose(face(noseX = 0.45f))!!
        val left = FeatureExtractor.headPose(face(noseX = 0.55f))!!
        assertEquals(0f, centre.first, 0.001f)
        assertTrue(right.first > 0.2f)
        assertTrue(left.first < -0.2f)
    }

    @Test
    fun noseSlidingDownTheFaceIsHeadTiltedDown() {
        val centre = FeatureExtractor.headPose(face())!!
        val down = FeatureExtractor.headPose(face(noseY = 0.55f))!!
        val up = FeatureExtractor.headPose(face(noseY = 0.45f))!!
        assertTrue(down.second < centre.second)
        assertTrue(up.second > centre.second)
    }

    @Test
    fun headPoseIgnoresSidewaysRoll() {
        // The whole face rotated 20 degrees in the image plane must still read as centred.
        val a = Math.toRadians(20.0)
        val c = Math.cos(a).toFloat()
        val s = Math.sin(a).toFloat()
        val base = face()
        val x = FloatArray(478)
        val y = FloatArray(478)
        for (i in intArrayOf(1, 13, 14, 33, 263)) {
            val dx = base.x[i] - 0.5f
            val dy = base.y[i] - 0.5f
            x[i] = 0.5f + dx * c - dy * s
            y[i] = 0.5f + dx * s + dy * c
        }
        val pose = FeatureExtractor.headPose(FaceLandmarks(x, y, 640, 640))!!
        val straight = FeatureExtractor.headPose(base)!!
        assertEquals(straight.first, pose.first, 0.01f)
        assertEquals(straight.second, pose.second, 0.01f)
    }

    @Test
    fun tinyFacesAreRejected() {
        assertNull(FeatureExtractor.headPose(face(size = 50)))
        assertFalse(FeatureExtractor.frame(5, { 0f }, face(size = 50)).face)
        assertFalse(FeatureExtractor.frame(5, { 0f }, null).face)
    }

    @Test
    fun blendshapesBecomeSignedSignals() {
        val scores = mapOf(
            "eyeBlinkLeft" to 0.8f, "eyeBlinkRight" to 0.1f,
            "eyeLookOutRight" to 0.6f, "eyeLookInLeft" to 0.6f, // both eyes look to the user's right
            "eyeLookUpLeft" to 0.1f, "eyeLookUpRight" to 0.1f,
            "eyeLookDownLeft" to 0.5f, "eyeLookDownRight" to 0.3f,
            "browInnerUp" to 0.3f, "browOuterUpLeft" to 0.6f, "browOuterUpRight" to 0.0f,
        )
        val f = FeatureExtractor.frame(7, { scores[it] ?: 0f }, face())
        assertNotNull(f)
        assertTrue(f.face)
        assertEquals(0.8f, f.blinkL, 0.0001f)
        assertEquals(0.1f, f.blinkR, 0.0001f)
        assertEquals(0.6f, f.gazeX, 0.0001f)
        assertEquals(-0.3f, f.gazeY, 0.0001f)
        assertEquals(0.3f, f.brow, 0.0001f)
    }
}
