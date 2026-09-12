package com.vidx.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.vidx.app.BrandConfig
import com.vidx.ui.theme.ThemeEngine

/** Shared UI helpers: dp/sp conversion, text styles, haptics, toasts. */
object Ui {

    fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    fun sp(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    fun text(context: Context, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = Typeface.create("sans-serif" + when (style) {
                Typeface.BOLD -> "-medium"
                else -> ""
            }, Typeface.NORMAL)
            if (style == Typeface.BOLD) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            this.gravity = gravity
            includeFontPadding = false
        }

    fun title(context: Context, text: String, size: Float = 20f): TextView =
        text(context, size, BrandConfig.colorTextPrimary, Typeface.BOLD).apply { this.text = text }

    fun subtitle(context: Context, text: String, size: Float = 13f): TextView =
        text(context, size, BrandConfig.colorTextSecondary).apply { this.text = text }

    fun haptic(view: View, kind: Int = HapticFeedbackConstants.LONG_PRESS) {
        view.performHapticFeedback(kind)
    }

    fun toast(context: Context, message: String, long: Boolean = false) {
        val t = Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        t.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, Ui.dp(context, 96f))
        t.show()
    }

    val mainHandler = Handler(Looper.getMainLooper())
}

/** Glassmorphism card container. */
class GlassCard(context: Context) : LinearLayout(context) {
    init {
        orientation = LinearLayout.VERTICAL
        background = ThemeEngine.glassBackground()
        setPadding(Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        elevation = Ui.dp(context, 2f).toFloat()
    }
}

/** Gradient CTA button with pressed feedback. */
class GradientButton(context: Context, colors: IntArray) : TextView(context) {
    init {
        text = ""
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = ThemeEngine.gradientBackground(colors, 18f)
        isClickable = true
        isFocusable = true
        val pad = Ui.dp(context, 14f)
        setPadding(pad * 2, pad, pad * 2, pad)
        minHeight = Ui.dp(context, 52f)
    }

    fun setGradient(colors: IntArray) {
        background = ThemeEngine.gradientBackground(colors, 18f)
    }
}

/** Secondary (ghost/outline) button. */
class GhostButton(context: Context) : TextView(context) {
    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(BrandConfig.colorTextPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 18f
            setStroke(Ui.dp(context, 1f), ThemeEngine.withAlpha(BrandConfig.colorTextSecondary, 0.4f))
        }
        isClickable = true
        val pad = Ui.dp(context, 12f)
        setPadding(pad * 2, pad, pad * 2, pad)
        minHeight = Ui.dp(context, 46f)
    }
}

/** Small pill chip (platform badge, status badge). */
class Chip(context: Context, color: Int, label: String) : TextView(context) {
    init {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = ThemeEngine.pillBackground(ThemeEngine.withAlpha(color, 0.16f))
        val pad = Ui.dp(context, 6f)
        setPadding(pad * 2, pad, pad * 2, pad)
    }
}

/** Animated circular progress ring. */
class ProgressRing(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeWidth = Ui.dp(context, 4.5f).toFloat()
    }
    private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeWidth = Ui.dp(context, 4.5f).toFloat()
        color = ThemeEngine.withAlpha(BrandConfig.colorTextPrimary, 0.12f)
    }
    private var animatedPct = 0f
    private var targetPct = 0f
    @Volatile private var indeterminate = false
    private var angle = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 300
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            val t = it.animatedValue as Float
            animatedPct = animatedPct + (targetPct - animatedPct) * t * 0.4f
            angle += 2.5f
            invalidate()
        }
        animator.repeatCount = android.animation.ValueAnimator.INFINITE
        animator.repeatMode = android.animation.ValueAnimator.RESTART
        animator.start()
    }

    fun setProgress(pct: Int, isIndeterminate: Boolean) {
        targetPct = pct.coerceIn(0, 100) / 100f
        indeterminate = isIndeterminate
    }

    fun setColors(colors: IntArray) {
        paint.shader = android.graphics.SweepGradient(
            width / 2f, height / 2f,
            colors.getOrElse(0) { ThemeEngine.accentColor() },
            colors.getOrElse(1) { ThemeEngine.accentColor() },
        )
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - paint.strokeWidth - Ui.dp(context, 2f)
        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(rect, trackPaint)
        if (indeterminate) {
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.drawArc(rect, 0f, 120f, false, paint)
            canvas.restore()
        } else {
            paint.shader = android.graphics.SweepGradient(cx, cy, ThemeEngine.primaryGradient().first(), ThemeEngine.primaryGradient().last())
            canvas.drawArc(rect, -90f, animatedPct * 360f, false, paint)
        }
    }
}

/** Section header row: small caps label. */
fun sectionHeader(context: Context, label: String): TextView =
    Ui.text(context, 12f, BrandConfig.colorTextTertiary, Typeface.BOLD).apply {
        text = label.uppercase()
        letterSpacing = 0.12f
    }

/** Standard vertical padding for screen containers. */
fun ViewGroup.verticalPad(context: Context, v: Int = 20) {
    val p = Ui.dp(context, v.toFloat())
    setPadding(p, p, p, p)
}

/** Helper to add a row with weight. */
fun LinearLayout.addRow(block: LinearLayout.() -> Unit) {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    row.block()
}

/** Convenience: icon from vector resource with tint. */
fun iconView(context: Context, resId: Int, tint: Int, sizeDp: Float = 22f): ImageView =
    ImageView(context).apply {
        setImageResource(resId)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        val s = Ui.dp(context, sizeDp)
        layoutParams = FrameLayout.LayoutParams(s, s)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
