package com.vidx.ui.theme

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.vidx.app.BrandConfig
import com.vidx.core.settings.AccentTheme

/**
 * Runtime theme engine — dark, glassy, neon.
 * Accent themes: Black base + Red / Blue / Purple accents (Settings → Appearance).
 */
object ThemeEngine {

    @Volatile
    var accent: AccentTheme = AccentTheme.RED
        private set

    fun setAccent(a: AccentTheme) {
        accent = a
    }

    fun accentColor(): Int = when (accent) {
        AccentTheme.RED -> BrandConfig.colorRed
        AccentTheme.BLUE -> BrandConfig.colorBlue
        AccentTheme.PURPLE -> BrandConfig.colorPurple
    }

    /** Primary CTA: RED / RED-PURPLE gradient (per brand spec). */
    fun primaryGradient(): IntArray = when (accent) {
        AccentTheme.RED -> intArrayOf(0xFFFF2D55.toInt(), 0xFFC026D3.toInt())
        AccentTheme.BLUE -> intArrayOf(0xFF2E7CFF.toInt(), 0xFF7C3AED.toInt())
        AccentTheme.PURPLE -> intArrayOf(0xFFA855F7.toInt(), 0xFF6D28D9.toInt())
    }

    /** Secondary CTA: BLUE / PURPLE gradient (per brand spec). */
    fun secondaryGradient(): IntArray = intArrayOf(0xFF2E7CFF.toInt(), 0xFF8E4DFF.toInt())

    fun accentSoft(): Int = withAlpha(accentColor(), 0.16f)
    fun onAccent(): Int = Color.WHITE

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun applySystemBars(activity: Activity) {
        activity.window.statusBarColor = BrandConfig.colorBackground
        activity.window.navigationBarColor = BrandConfig.colorBackground
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv().and(
                activity.window.decorView.systemUiVisibility
            )
        }
    }

    fun glassBackground(radius: Float = 24f, fillAlpha: Int = 0x16, borderAlpha: Int = 0x22): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(fillAlpha, 255, 255, 255))
            setStroke(dpStroke(1), Color.argb(borderAlpha, 255, 255, 255))
        }

    fun gradientBackground(colors: IntArray, radius: Float = 24f): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = radius
        }

    fun pillBackground(color: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = 1000f; setColor(color) }

    private fun dpStroke(v: Int) = v
}
