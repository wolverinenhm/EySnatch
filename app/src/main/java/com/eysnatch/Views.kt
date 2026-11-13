package com.eysnatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.eysnatch.core.GestureEngine
import com.eysnatch.core.Norm
import kotlin.math.abs
import kotlin.math.min

/** Shows what the camera sees (mirrored like a selfie) with the detected face points on top. */
class CameraView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF10161C.toInt())
        val bmp = Live.thumb
        if (bmp == null) {
            canvas.drawText(
                if (Live.running) "Waiting for the camera..." else "Start eye control to see the camera",
                width / 2f, height / 2f, label,
            )
            return
        }
        val scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f

        canvas.save()
        canvas.translate(left + w, top)
        canvas.scale(-scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()

        val lm = Live.landmarks
        if (lm != null && Live.frame?.face == true) {
            val pts = FloatArray(lm.size)
            for (i in 0 until lm.size / 2) {
                pts[2 * i] = left + (1f - lm[2 * i]) * w
                pts[2 * i + 1] = top + lm[2 * i + 1] * h
            }
            canvas.drawPoints(pts, dots)
        } else if (Live.running) {
            label.color = 0xFFFF8A80.toInt()
            canvas.drawText("No face found", width / 2f, top + context.dp(24), label)
            label.color = Color.WHITE
        }
    }
}

/** Live bars for every signal the engine looks at, with the trigger level marked. */
class BarsView(ctx: Context) : View(ctx) {
    private class Row(val label: String, val signed: Boolean, val fixed: Float?, val value: (Norm) -> Float)

    private val rows = listOf(
        Row("Left eye closed", false, GestureEngine.CLOSE_ON) { it.closeL },
        Row("Right eye closed", false, GestureEngine.CLOSE_ON) { it.closeR },
        Row("Look left  <->  right", true, null) { it.gx },
        Row("Look down  <->  up", true, null) { it.gy },
        Row("Head turn left  <->  right", true, null) { it.hx },
        Row("Head tilt down  <->  up", true, null) { it.hy },
        Row("Eyebrows raised", false, null) { it.brow },
    )
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 128, 128, 128) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00B8D4.toInt() }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF69F0AE.toInt() }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        strokeWidth = context.dp(2).toFloat()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = context.dp(12).toFloat()
    }
    private val rowH = context.dp(36)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowH * rows.size)
    }

    override fun onDraw(canvas: Canvas) {
        val n = Live.norm
        val sensitivityTrigger = (0.6f / Prefs.sensitivity).coerceIn(0.3f, 0.95f)
        val barH = context.dp(10).toFloat()
        rows.forEachIndexed { i, row ->
            val trigger = row.fixed ?: sensitivityTrigger
            val y = i * rowH.toFloat()
            val v = if (n.face) row.value(n) else 0f
            canvas.drawText(row.label, 0f, y + context.dp(13), text)
            val top = y + context.dp(18)
            val r = RectF(0f, top, width.toFloat(), top + barH)
            canvas.drawRoundRect(r, barH / 2, barH / 2, track)

            val full = 1.2f
            val active = abs(v) >= trigger
            if (row.signed) {
                val mid = width / 2f
                val x = mid + (v.coerceIn(-full, full) / full) * mid
                canvas.drawRect(RectF(min(mid, x), top, maxOf(mid, x), top + barH), if (active) hot else fill)
                for (s in floatArrayOf(-trigger, trigger)) {
                    val mx = mid + s / full * mid
                    canvas.drawLine(mx, top - 3, mx, top + barH + 3, mark)
                }
            } else {
                val x = (v.coerceIn(0f, full) / full) * width
                canvas.drawRect(RectF(0f, top, x, top + barH), if (active) hot else fill)
                val mx = trigger / full * width
                canvas.drawLine(mx, top - 3, mx, top + barH + 3, mark)
            }
        }
    }
}
