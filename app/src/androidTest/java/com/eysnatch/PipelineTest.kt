package com.eysnatch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eysnatch.core.FaceLandmarks
import com.eysnatch.core.FeatureExtractor
import com.eysnatch.core.Frame
import com.eysnatch.core.Gesture
import com.eysnatch.core.GestureEngine
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real MediaPipe model on real Android against photos where the iris was moved, to prove the
 * whole chain (model -> features -> engine) agrees about left/right/up/down.
 * Test photo: official White House portrait (public domain), irises shifted by hand.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var landmarker: FaceLandmarker

    @Before
    fun setUp() {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .build()
        landmarker = FaceLandmarker.createFromOptions(instrumentation.targetContext, options)
    }

    @After
    fun tearDown() {
        landmarker.close()
    }

    private fun frameOf(asset: String): Frame {
        val decoded = instrumentation.context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, false)
        val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val points = result.faceLandmarks().firstOrNull() ?: return Frame(0, false)
        val scores = result.faceBlendshapes().get()[0].associate { it.categoryName() to it.score() }
        val frame = FeatureExtractor.frame(
            0,
            { scores[it] ?: 0f },
            FaceLandmarks(
                FloatArray(points.size) { points[it].x() },
                FloatArray(points.size) { points[it].y() },
                bitmap.width,
                bitmap.height,
            ),
        )
        Log.i(TAG, "$asset -> $frame")
        return frame
    }

    @Test
    fun faceIsFoundInANeutralPhoto() {
        val f = frameOf("neutral.jpg")
        assertTrue("no face found", f.face)
        assertTrue("gazeX ${f.gazeX}", Math.abs(f.gazeX) < 0.3f)
        assertTrue("brow ${f.brow}", f.brow < 0.3f)
    }

    @Test
    fun eyesMovedRightAreReportedAsRight() {
        val neutral = frameOf("neutral.jpg")
        val f = frameOf("gaze_right.jpg")
        assertTrue("gazeX ${f.gazeX} vs ${neutral.gazeX}", f.gazeX - neutral.gazeX > 0.4f)
    }

    @Test
    fun eyesMovedLeftAreReportedAsLeft() {
        val neutral = frameOf("neutral.jpg")
        val f = frameOf("gaze_left.jpg")
        assertTrue("gazeX ${f.gazeX} vs ${neutral.gazeX}", neutral.gazeX - f.gazeX > 0.4f)
    }

    @Test
    fun eyesMovedUpAndDown() {
        val neutral = frameOf("neutral.jpg")
        assertTrue(frameOf("gaze_up.jpg").gazeY - neutral.gazeY > 0.1f)
        assertTrue(neutral.gazeY - frameOf("gaze_down.jpg").gazeY > 0.25f)
    }

    @Test
    fun theWholeChainTurnsAGlanceIntoAGesture() {
        val engine = GestureEngine()
        val neutral = frameOf("neutral.jpg")
        val events = ArrayList<Gesture>()
        var t = 1000L
        fun feed(f: Frame, ms: Int) {
            repeat(ms / 33) {
                t += 33
                engine.onFrame(f.copy(timeMs = t)).filter { !it.repeat }.forEach { events += it.gesture }
            }
        }
        feed(neutral, 1500)
        feed(frameOf("gaze_left.jpg"), 900)
        feed(neutral, 800)
        feed(frameOf("gaze_right.jpg"), 900)
        feed(neutral, 800)
        assertEquals(listOf(Gesture.LOOK_LEFT, Gesture.LOOK_RIGHT), events)
    }

    companion object {
        private const val TAG = "EySnatchTest"
    }
}
