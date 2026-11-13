package com.eysnatch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

fun <T : View> LinearLayout.add(v: T, top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): T {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    lp.topMargin = context.dp(top)
    addView(v, lp)
    return v
}

private fun themeColor(ctx: Context, attr: Int): ColorStateList? {
    val tv = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, tv, true)) return null
    return if (tv.resourceId != 0) ctx.getColorStateList(tv.resourceId) else ColorStateList.valueOf(tv.data)
}

/** Plain programmatic UI helpers; the app has few screens and no layout XML to keep in sync. */
object Ui {
    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(28))
    }

    fun scroll(ctx: Context, content: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        addView(content)
    }

    fun text(ctx: Context, s: String, sp: Float = 15f, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        themeColor(ctx, android.R.attr.textColorPrimary)?.let { setTextColor(it) }
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun heading(ctx: Context, s: String, sp: Float = 24f): TextView = text(ctx, s, sp, bold = true)

    fun section(ctx: Context, s: String): TextView = text(ctx, s.uppercase(), 12f, bold = true).apply {
        setTextColor(ctx.getColor(R.color.accent_dark))
        letterSpacing = 0.08f
    }

    fun dim(v: TextView): TextView {
        themeColor(v.context, android.R.attr.textColorSecondary)?.let { v.setTextColor(it) }
        return v
    }

    fun button(ctx: Context, s: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setStroke(ctx.dp(1), Color.argb(60, 128, 128, 128))
        }
    }
}

/** Activity that tells [Live] when an EySnatch screen is showing (gestures are only *tested* then). */
abstract class BaseActivity : AppCompatActivity() {
    override fun onStart() {
        super.onStart()
        Live.uiStarted()
    }

    override fun onStop() {
        Live.uiStopped()
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
