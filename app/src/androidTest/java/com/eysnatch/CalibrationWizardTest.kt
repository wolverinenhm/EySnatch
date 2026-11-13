package com.eysnatch

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rules.GrantPermissionRule
import com.eysnatch.core.CalStep
import com.eysnatch.core.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real calibration screen through every pose with simulated face data (as if a user with a
 * normal, un-swapped camera performed each pose) and checks that the saved calibration is right.
 */
@RunWith(AndroidJUnit4::class)
class CalibrationWizardTest {

    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun pose(step: CalStep): Frame = when (step) {
        CalStep.NEUTRAL -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.00f, 0.00f, -0.55f, 0.05f)
        CalStep.BOTH_CLOSED -> Frame(0, true, 0.85f, 0.85f, 0.00f, 0.00f, 0.00f, -0.55f, 0.05f)
        CalStep.WINK_LEFT -> Frame(0, true, 0.85f, 0.10f, 0.00f, 0.00f, 0.00f, -0.55f, 0.05f)
        CalStep.WINK_RIGHT -> Frame(0, true, 0.10f, 0.85f, 0.00f, 0.00f, 0.00f, -0.55f, 0.05f)
        CalStep.LOOK_LEFT -> Frame(0, true, 0.10f, 0.10f, -0.60f, 0.00f, 0.00f, -0.55f, 0.05f)
        CalStep.LOOK_RIGHT -> Frame(0, true, 0.10f, 0.10f, 0.60f, 0.00f, 0.00f, -0.55f, 0.05f)
        CalStep.LOOK_UP -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.35f, 0.00f, -0.55f, 0.05f)
        CalStep.LOOK_DOWN -> Frame(0, true, 0.10f, 0.10f, 0.00f, -0.50f, 0.00f, -0.55f, 0.05f)
        CalStep.BROWS -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.00f, 0.00f, -0.55f, 0.60f)
        CalStep.TURN_LEFT -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.00f, -0.15f, -0.55f, 0.05f)
        CalStep.TURN_RIGHT -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.00f, 0.15f, -0.55f, 0.05f)
        CalStep.TILT_UP -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.00f, 0.00f, -0.43f, 0.05f)
        CalStep.TILT_DOWN -> Frame(0, true, 0.10f, 0.10f, 0.00f, 0.00f, 0.00f, -0.67f, 0.05f)
    }

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(scenario: ActivityScenario<CalibrateActivity>): Pair<String, List<Button>> {
        var title = ""
        var buttons = emptyList<Button>()
        scenario.onActivity { a ->
            val all = views(a.findViewById(android.R.id.content))
            title = all.filterIsInstance<TextView>().filter { it !is Button }.map { it.text.toString() }
                .firstOrNull { Regex("^\\d+ of \\d+:").containsMatchIn(it) || it == "Calibration finished" } ?: ""
            buttons = all.filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
        }
        return Pair(title, buttons)
    }

    private fun click(scenario: ActivityScenario<CalibrateActivity>, label: String): Boolean {
        var done = false
        scenario.onActivity { a ->
            views(a.findViewById(android.R.id.content)).filterIsInstance<Button>()
                .firstOrNull { it.visibility == View.VISIBLE && it.text.toString().startsWith(label) }
                ?.let { it.performClick(); done = true }
        }
        return done
    }

    @Test
    fun wizardWalksThroughEveryPoseAndSavesTheRightCalibration() {
        Prefs.resetCalibration()
        Live.resetSession()
        Live.running = true // pretend the camera service is running; we play the camera ourselves
        try {
            ActivityScenario.launch(CalibrateActivity::class.java).use { scenario ->
                assertTrue("could not press Start", click(scenario, "Start: eyes and head"))
                val deadline = System.currentTimeMillis() + 150_000
                var finished = false
                var t = 0L
                while (System.currentTimeMillis() < deadline) {
                    val (title, _) = texts(scenario)
                    if (title == "Calibration finished") {
                        finished = true
                        break
                    }
                    val step = CalStep.values().firstOrNull { title.endsWith(":  ${it.title}") }
                    val frame = (if (step != null) pose(step) else pose(CalStep.NEUTRAL)).copy(timeMs = t)
                    t += 33
                    Live.frame = frame
                    Live.frameListener?.invoke(frame)
                    Thread.sleep(33)
                }
                assertTrue("the wizard did not reach the end in time", finished)
                assertTrue("could not press Save", click(scenario, "Save"))
            }
        } finally {
            Live.running = false
            Live.resetSession()
        }

        val cal = Prefs.calibration()
        assertTrue(cal.calibrated)
        assertFalse("eyes were demonstrated un-swapped", cal.swapEyes)
        assertEquals(0.10f, cal.openL, 0.03f)
        assertEquals(0.85f, cal.closedL, 0.03f)
        assertEquals(0.85f, cal.closedR, 0.03f)
        assertEquals(0.60f, cal.gazeX.pos, 0.03f)
        assertEquals(-0.60f, cal.gazeX.neg, 0.03f)
        assertEquals(0.35f, cal.gazeY.pos, 0.03f)
        assertEquals(-0.50f, cal.gazeY.neg, 0.03f)
        assertEquals(0.60f, cal.brow.pos, 0.03f)
        assertEquals(0.15f, cal.yaw.pos, 0.02f)
        assertEquals(-0.15f, cal.yaw.neg, 0.02f)
        assertEquals(-0.55f, cal.pitch.base, 0.02f)
        assertEquals(-0.43f, cal.pitch.pos, 0.02f)
        assertEquals(-0.67f, cal.pitch.neg, 0.02f)
        assertFalse("normal gesture handling must be back", Live.calibrating)
        Prefs.resetCalibration()
    }
}
