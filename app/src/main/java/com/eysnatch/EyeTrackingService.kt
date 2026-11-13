package com.eysnatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.eysnatch.core.FaceLandmarks
import com.eysnatch.core.FeatureExtractor
import com.eysnatch.core.Frame
import com.eysnatch.core.Gesture
import com.eysnatch.core.GestureEngine
import com.eysnatch.core.GestureEvent
import com.eysnatch.core.PhoneAction
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that watches the front camera, turns faces into gestures and hands the
 * resulting actions to [EySnatchAccessibilityService].
 *
 * Threads: CameraX analyzer thread (frames in) -> MediaPipe result thread (gestures out) -> main thread
 * (status, notification, running actions). Everything that touches the UI or runs an action goes through
 * [main] and re-checks [alive], so nothing can run after the service was stopped.
 */
class EyeTrackingService : LifecycleService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val engine = GestureEngine()

    @Volatile private var landmarker: FaceLandmarker? = null
    @Volatile private var alive = true
    @Volatile private var minIntervalMs = FRAME_INTERVAL_MS
    @Volatile private var idle = false
    @Volatile private var lastResultAt = 0L
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var orientation: OrientationEventListener? = null
    private lateinit var notifications: NotificationManager

    private var failed = false
    private var seenVersion = -1
    private var mapping: Map<Gesture, PhoneAction> = emptyMap()

    // Camera thread
    private var rawBitmap: Bitmap? = null
    private var lastSubmit = 0L
    private var lastTimestamp = 0L
    private var thumbCounter = 0

    // Result thread
    private var statWindowStart = 0L
    private var statFrames = 0
    private var lastLog = 0L
    private var startedAt = 0L
    private var lastFaceSeen = 0L
    private var lastPublish = 0L
    private var lastPublishedFace: Boolean? = null

    // Main thread
    private var faceOk = false
    private var stalled = false
    private var cameraBoundAt = 0L
    private var lastNotificationText = ""

    private val watchdog = object : Runnable {
        override fun run() {
            if (!alive) return
            val now = SystemClock.uptimeMillis()
            val last = if (lastResultAt != 0L) lastResultAt else cameraBoundAt
            val nowStalled = cameraBoundAt != 0L && now - last > STALL_MS
            if (nowStalled != stalled) {
                stalled = nowStalled
                if (nowStalled) {
                    Live.fps = 0f
                    Live.note("No pictures from the camera. Is another app using it?")
                }
                refreshStatus()
            }
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        Live.resetSession()
        Live.running = true
        Live.status = "Starting..."
        startedAt = SystemClock.uptimeMillis()
        if (!startAsForeground("Starting the camera...")) return
        try {
            createLandmarker()
        } catch (t: Throwable) {
            fail("Could not load the face model: ${t.message}")
            return
        }
        startCamera()
        EySnatchAccessibilityService.instance?.onTrackingChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        Live.running = false
        Live.status = Live.error ?: "Stopped"
        main.removeCallbacksAndMessages(null)
        orientation?.disable()
        try {
            analysis?.let {
                it.clearAnalyzer()
                provider?.unbind(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "unbind", t)
        }
        worker.shutdown()
        try {
            worker.awaitTermination(300, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            landmarker?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close", t)
        }
        landmarker = null
        EySnatchAccessibilityService.instance?.onTrackingChanged()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    private fun startAsForeground(text: String): Boolean {
        val channel = NotificationChannel(CHANNEL, "Eye control", NotificationManager.IMPORTANCE_LOW)
        notifications.createNotificationChannel(channel)
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            true
        } catch (t: Throwable) {
            fail("Android would not start the camera service: ${t.message}")
            false
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        failed = true
        Live.error = message
        Live.status = message
        Live.note(message)
        stopSelf()
    }

    private fun createLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, input -> onResult(result, input) }
            .setErrorListener { e ->
                Log.e(TAG, "MediaPipe error", e)
                Live.note("Face tracking error: ${e.message}")
            }
            .build()
        landmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!alive || failed) return@addListener
            try {
                val p = future.get()
                provider = p
                if (!p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    fail("This phone has no front camera.")
                    return@addListener
                }
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val ia = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                ia.setAnalyzer(worker) { proxy -> analyze(proxy) }
                analysis = ia
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, ia)
                watchOrientation(ia)
                cameraBoundAt = SystemClock.uptimeMillis()
                main.postDelayed(watchdog, 2000)
                Live.status = "Looking for your face..."
                Log.i(TAG, "camera bound")
            } catch (t: Throwable) {
                fail("Could not open the front camera: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun watchOrientation(ia: ImageAnalysis) {
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                ia.targetRotation = when {
                    degrees >= 315 || degrees < 45 -> Surface.ROTATION_0
                    degrees < 135 -> Surface.ROTATION_270
                    degrees < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        orientation = listener
    }

    // ---------------------------------------------------------------- camera thread

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val now = SystemClock.uptimeMillis()
            // Nobody in front of the phone: look only a few times a second to save battery.
            val interval = if (idle && !Live.uiVisible && !Live.calibrating) IDLE_INTERVAL_MS else minIntervalMs
            if (now - lastSubmit < interval || now <= lastTimestamp) return
            lastSubmit = now
            lastTimestamp = now

            val upright = uprightBitmap(proxy)
            if (Live.uiVisible && thumbCounter++ % 2 == 0) {
                val w = 168
                val h = (w * upright.height.toFloat() / upright.width).toInt().coerceAtLeast(1)
                Live.thumb = Bitmap.createScaledBitmap(upright, w, h, true)
            }
            lm.detectAsync(BitmapImageBuilder(upright).build(), now)
        } catch (t: Throwable) {
            Log.w(TAG, "frame skipped: ${t.message}")
        } finally {
            proxy.close()
        }
    }

    /** RGBA camera frame -> bitmap rotated so the face is upright (the camera image is not mirrored). */
    private fun uprightBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        var raw = rawBitmap
        if (raw == null || raw.width != rowPixels || raw.height != proxy.height) {
            raw = Bitmap.createBitmap(rowPixels, proxy.height, Bitmap.Config.ARGB_8888)
            rawBitmap = raw
        }
        plane.buffer.rewind()
        raw.copyPixelsFromBuffer(plane.buffer)
        val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, proxy.width, proxy.height, matrix, true)
    }

    // ---------------------------------------------------------------- result thread

    private fun onResult(result: FaceLandmarkerResult, input: MPImage) {
        if (!alive) return
        try {
            val t = result.timestampMs()
            val points = result.faceLandmarks().firstOrNull()
            val blend = result.faceBlendshapes().orElse(null)?.firstOrNull()
            val frame = if (points == null || blend == null) {
                Live.landmarks = null
                Frame(t, false)
            } else {
                val scores = HashMap<String, Float>(64)
                for (c in blend) scores[c.categoryName()] = c.score()
                val xs = FloatArray(points.size) { points[it].x() }
                val ys = FloatArray(points.size) { points[it].y() }
                if (Live.uiVisible) {
                    Live.landmarks = FloatArray(points.size * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
                }
                FeatureExtractor.frame(t, { scores[it] ?: 0f }, FaceLandmarks(xs, ys, input.width, input.height))
            }
            handle(frame)
        } catch (t: Throwable) {
            Log.w(TAG, "result error", t)
        }
    }

    private fun handle(frame: Frame) {
        val now = SystemClock.uptimeMillis()
        lastResultAt = now
        if (Prefs.version != seenVersion) {
            seenVersion = Prefs.version
            applySettings()
        }
        Live.frame = frame
        Live.frameListener?.invoke(frame)
        val events = engine.onFrame(frame)
        Live.norm = engine.norm
        Live.framesTotal += 1
        trackStats(frame, now)
        for (e in events) route(e)
    }

    private fun applySettings() {
        engine.configure(Prefs.engineConfig(), Prefs.calibration())
        mapping = Gesture.values().associateWith { Prefs.action(it) }
        minIntervalMs = if (Prefs.batterySaver) SAVER_INTERVAL_MS else FRAME_INTERVAL_MS
        main.post { if (alive) EySnatchAccessibilityService.instance?.onTrackingChanged() }
    }

    private fun trackStats(frame: Frame, now: Long) {
        if (statWindowStart == 0L) statWindowStart = now
        statFrames += 1
        if (now - statWindowStart >= 1000) {
            Live.fps = statFrames * 1000f / (now - statWindowStart)
            statFrames = 0
            statWindowStart = now
        }
        if (frame.face) lastFaceSeen = now
        idle = now - maxOf(lastFaceSeen, startedAt) > IDLE_AFTER_MS
        val ok = lastFaceSeen != 0L && now - lastFaceSeen < 2500
        if (now - lastLog > 5000) {
            lastLog = now
            Log.i(TAG, "STATS frames=${Live.framesTotal} fps=${"%.1f".format(Live.fps)} face=$ok idle=$idle")
        }
        if (ok != lastPublishedFace || now - lastPublish > 1000) {
            lastPublishedFace = ok
            lastPublish = now
            main.post {
                if (!alive) return@post
                faceOk = ok
                refreshStatus()
            }
        }
    }

    private fun route(e: GestureEvent) {
        val action = mapping[e.gesture] ?: PhoneAction.NONE
        if (action == PhoneAction.NONE) return
        if (e.repeat && !action.repeatable) return
        if (Live.paused && action != PhoneAction.TOGGLE_PAUSE) return

        // While an EySnatch screen is open (or calibrating) gestures are only shown: nothing is performed,
        // and "pause" must not trigger either (the calibration's closed-eyes pose looks like it).
        val testOnly = Live.uiVisible || Live.calibrating
        if (!e.repeat) {
            Live.note("${e.gesture.label}  ->  ${action.label}" + if (testOnly) "  (test: not run)" else "")
            Log.i(TAG, "GESTURE ${e.gesture} -> $action testOnly=$testOnly")
        }
        if (testOnly) return

        main.post {
            if (!alive) return@post
            if (action == PhoneAction.TOGGLE_PAUSE) {
                togglePause()
                return@post
            }
            if (Live.paused) return@post
            val acc = EySnatchAccessibilityService.instance
            if (acc == null) Live.note("Turn on the EySnatch accessibility service to run actions")
            else acc.perform(action, e.gesture.label)
        }
    }

    // ---------------------------------------------------------------- main thread

    private fun togglePause() {
        Live.paused = !Live.paused
        refreshStatus()
        EySnatchAccessibilityService.instance?.hint(if (Live.paused) "EySnatch paused" else "EySnatch resumed")
    }

    private fun refreshStatus() {
        if (!alive) return
        val text = when {
            Live.paused -> "Paused"
            stalled -> "No pictures from the camera"
            faceOk -> "Watching your eyes"
            else -> "No face in view - hold the phone so the front camera can see you"
        }
        Live.status = text
        if (text != lastNotificationText) {
            lastNotificationText = text
            notifications.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val pause = PendingIntent.getService(
            this, 1, Intent(this, EyeTrackingService::class.java).setAction(ACTION_TOGGLE_PAUSE), flags,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, EyeTrackingService::class.java).setAction(ACTION_STOP), flags,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (Live.paused) "EySnatch (paused)" else "EySnatch eye control")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (Live.paused) "Resume" else "Pause", pause)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val TAG = "EySnatch"
        const val ACTION_STOP = "com.eysnatch.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.eysnatch.TOGGLE_PAUSE"
        private const val CHANNEL = "eysnatch_running"
        private const val NOTIFICATION_ID = 1
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val FRAME_INTERVAL_MS = 30L
        private const val SAVER_INTERVAL_MS = 80L
        private const val IDLE_INTERVAL_MS = 200L
        private const val IDLE_AFTER_MS = 10_000L
        private const val STALL_MS = 4_000L
    }
}
