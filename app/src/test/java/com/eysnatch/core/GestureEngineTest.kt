package com.eysnatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the engine with synthetic frames at a fixed frame rate. */
private class Sim(
    val engine: GestureEngine = GestureEngine(),
    private val fps: Int = 30,
) {
    var t = 1000L
    val events = ArrayList<GestureEvent>()
    var blinkL = 0.05f
    var blinkR = 0.05f
    var gx = 0f
    var gy = 0f
    var yaw = 0f
    var pitch = 0f
    var brow = 0f
    var face = true

    fun run(ms: Int) {
        repeat(ms * fps / 1000) {
            t += 1000L / fps
            events += engine.onFrame(Frame(t, face, blinkL, blinkR, gx, gy, yaw, pitch, brow))
        }
    }

    fun open() { blinkL = 0.05f; blinkR = 0.05f }
    fun closeBoth() { blinkL = 0.85f; blinkR = 0.85f }
    fun first() = events.filter { !it.repeat }.map { it.gesture }
}

class GestureEngineTest {

    @Test
    fun doubleBlinkFiresOnce() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(200); s.open(); s.run(250)
        s.closeBoth(); s.run(200); s.open(); s.run(1000)
        assertEquals(listOf(Gesture.DOUBLE_BLINK), s.first())
    }

    @Test
    fun naturalQuickBlinksAreIgnored() {
        val s = Sim()
        s.run(1000)
        repeat(5) { s.closeBoth(); s.run(100); s.open(); s.run(300) }
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun singleFirmBlinkDoesNothing() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(250); s.open(); s.run(1500)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun longBlinkIsRecognisedWhenEyesReopen() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(1000)
        assertTrue("fires on release, not while closed", s.events.isEmpty())
        s.open(); s.run(500)
        assertEquals(listOf(Gesture.LONG_BLINK), s.first())
    }

    @Test
    fun eyesShutForTwoSeconds() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(2500); s.open(); s.run(500)
        assertEquals(listOf(Gesture.EYES_SHUT), s.first())
    }

    @Test
    fun tooLongClosureIsIgnored() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(9000); s.open(); s.run(500)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun winkLeftAndRight() {
        val s = Sim()
        s.run(1000)
        s.blinkL = 0.85f; s.run(700); s.open(); s.run(800)
        s.blinkR = 0.85f; s.run(700); s.open(); s.run(800)
        assertEquals(listOf(Gesture.WINK_LEFT, Gesture.WINK_RIGHT), s.first())
    }

    @Test
    fun winkFiresOncePerWink() {
        val s = Sim()
        s.run(1000)
        s.blinkR = 0.85f; s.run(3000); s.open(); s.run(500)
        assertEquals(listOf(Gesture.WINK_RIGHT), s.first())
    }

    @Test
    fun blinkWithEyesClosingSlightlyApartIsNotAWink() {
        val s = Sim()
        s.run(1000)
        s.blinkL = 0.85f; s.run(66)
        s.closeBoth(); s.run(200)
        s.blinkR = 0.05f; s.run(66)
        s.open(); s.run(1000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun squintingOneEyeASmidgeIsNotAWink() {
        val s = Sim()
        s.run(1000)
        s.blinkL = 0.45f; s.run(2000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun lookEachDirection() {
        val cases = listOf(
            Triple(Gesture.LOOK_LEFT, -0.7f, 0f),
            Triple(Gesture.LOOK_RIGHT, 0.7f, 0f),
            Triple(Gesture.LOOK_UP, 0f, 0.5f),
            Triple(Gesture.LOOK_DOWN, 0f, -0.6f),
        )
        for ((g, x, y) in cases) {
            val s = Sim()
            s.run(1000)
            s.gx = x; s.gy = y; s.run(700)
            s.gx = 0f; s.gy = 0f; s.run(700)
            assertEquals("$g", listOf(g), s.first())
        }
    }

    @Test
    fun glanceThatIsTooShortDoesNothing() {
        val s = Sim()
        s.run(1000)
        s.gx = -0.7f; s.run(250); s.gx = 0f; s.run(1000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun readingEyeMovementsAreNotGestures() {
        val s = Sim()
        s.run(1000)
        repeat(30) { i ->
            s.gx = if (i % 2 == 0) -0.15f else 0.15f
            s.gy = -0.1f
            s.run(200)
        }
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun holdingARepeatsAfterADelay() {
        val s = Sim()
        s.run(1000)
        s.gy = -0.6f; s.run(3000)
        assertEquals(1, s.events.count { !it.repeat })
        val repeats = s.events.count { it.repeat }
        assertTrue("expected 2-3 repeats, got $repeats", repeats in 2..3)
        assertTrue(s.events.all { it.gesture == Gesture.LOOK_DOWN })
    }

    @Test
    fun jitterAroundTheThresholdDoesNotFireTwice() {
        val s = Sim()
        s.run(1000)
        repeat(40) { i ->
            s.gx = -(0.42f + if (i % 2 == 0) 0.05f else -0.04f)
            s.run(100)
        }
        assertTrue(s.events.count { !it.repeat } <= 1)
    }

    @Test
    fun gazeIsIgnoredWhileTheEyesAreClosed() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.gx = -0.9f; s.run(300)
        s.open(); s.gx = 0f; s.run(1000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun diagonalGlancePicksTheStrongestDirection() {
        val s = Sim()
        s.run(1000)
        s.gx = -0.4f; s.gy = 0.5f; s.run(800)
        s.gx = 0f; s.gy = 0f; s.run(500)
        assertEquals(listOf(Gesture.LOOK_UP), s.first())
    }

    @Test
    fun aNonZeroRestingPoseIsLearned() {
        val s = Sim()
        s.gx = 0.3f; s.gy = -0.4f; s.pitch = -0.45f; s.yaw = 0.05f; s.brow = 0.2f
        s.blinkL = 0.1f; s.blinkR = 0.1f
        s.run(20_000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun slowDriftOfTheRestingPoseIsFollowed() {
        val s = Sim()
        s.run(1000)
        repeat(200) { i ->
            s.gy = -0.002f * i
            s.run(500)
        }
        assertTrue("drift should not trigger anything: ${s.events}", s.events.isEmpty())
    }

    @Test
    fun losingTheFaceMidBlinkResetsTheBlink() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(300)
        s.face = false; s.run(1500)
        s.face = true; s.open(); s.run(1000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun sensitivityChangesHowBigAMovementMustBe() {
        val weak = -0.45f
        val normal = Sim()
        normal.run(1000); normal.gx = weak; normal.run(800)
        assertEquals(listOf(Gesture.LOOK_LEFT), normal.first())

        val careful = Sim(GestureEngine(EngineConfig(sensitivity = 0.5f)))
        careful.run(1000); careful.gx = weak; careful.run(800)
        assertTrue(careful.events.isEmpty())
    }

    @Test
    fun holdScaleLengthensTheHold() {
        val patient = Sim(GestureEngine(EngineConfig(holdScale = 2f)))
        patient.run(1000); patient.gx = -0.7f; patient.run(700)
        assertTrue(patient.events.isEmpty())
        patient.run(600)
        assertEquals(listOf(Gesture.LOOK_LEFT), patient.first())
    }

    @Test
    fun onlyEnabledGesturesAreReported() {
        val s = Sim(GestureEngine(EngineConfig(enabled = setOf(Gesture.LOOK_LEFT))))
        s.run(1000)
        s.closeBoth(); s.run(1000); s.open(); s.run(500)
        s.gx = 0.8f; s.run(800)
        assertTrue(s.events.isEmpty())
        s.gx = 0f; s.run(500)
        s.gx = -0.8f; s.run(800)
        assertEquals(listOf(Gesture.LOOK_LEFT), s.first())
    }

    @Test
    fun headTurnsAndTiltsAreReportedAndSuppressEyeGestures() {
        val s = Sim()
        s.run(1000)
        s.yaw = 0.2f; s.gx = -0.7f; s.run(800)
        assertEquals(listOf(Gesture.TURN_RIGHT), s.first())
        s.yaw = 0f; s.gx = 0f; s.run(500)
        s.pitch = 0.2f; s.run(800)
        s.pitch = 0f; s.run(500)
        s.pitch = -0.2f; s.run(800)
        assertEquals(listOf(Gesture.TURN_RIGHT, Gesture.TILT_UP, Gesture.TILT_DOWN), s.first())
    }

    @Test
    fun eyebrowRaise() {
        val s = Sim()
        s.run(1000)
        s.brow = 0.5f; s.run(800); s.brow = 0f; s.run(500)
        assertEquals(listOf(Gesture.RAISE_BROWS), s.first())
    }

    @Test
    fun lookingUpDoesNotAlsoCountAsEyebrowRaise() {
        val s = Sim()
        s.run(1000)
        s.gy = 0.5f; s.brow = 0.5f; s.run(800)
        assertEquals(listOf(Gesture.LOOK_UP), s.first())
    }

    @Test
    fun worksAtLowFrameRate() {
        val s = Sim(fps = 12)
        s.run(1000)
        s.gx = -0.7f; s.run(900); s.gx = 0f; s.run(600)
        s.blinkL = 0.85f; s.run(900); s.open(); s.run(600)
        assertEquals(listOf(Gesture.LOOK_LEFT, Gesture.WINK_LEFT), s.first())
    }

    @Test
    fun swappedEyeChannelsAreHandledByCalibration() {
        val s = Sim(GestureEngine(EngineConfig(), Calibration(calibrated = true, swapEyes = true)))
        s.run(1000)
        s.blinkR = 0.85f; s.run(700); s.open(); s.run(800)
        assertEquals(listOf(Gesture.WINK_LEFT), s.first())
    }

    @Test
    fun aStalledCameraDoesNotInventAGesture() {
        val s = Sim()
        s.run(1000)
        s.closeBoth(); s.run(100)
        s.t += 3000
        s.open(); s.run(1000)
        assertTrue(s.events.isEmpty())
    }
}
