package com.vidx.ui.nav

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import android.widget.TextView
import com.vidx.app.BrandConfig
import com.vidx.ui.components.Ui
import com.vidx.ui.theme.ThemeEngine

/**
 * Custom bottom navigation — Home / Downloads / Transcript / History / Settings.
 * Large touch targets, screen-reader labels, animated selection indicator.
 */
class BottomNav(context: Context) : LinearLayout(context) {

    enum class Tab(val label: String, val icon: Int, val contentDescription: String) {
        HOME("Home", com.vidx.app.R.drawable.ic_home, "Home screen"),
        DOWNLOADS("Downloads", com.vidx.app.R.drawable.ic_download, "Downloads screen"),
        TRANSCRIPT("Transcript", com.vidx.app.R.drawable.ic_transcript, "Transcript screen"),
        HISTORY("History", com.vidx.app.R.drawable.ic_history, "History screen"),
        SETTINGS("Settings", com.vidx.app.R.drawable.ic_settings, "Settings screen"),
    }

    var onTabSelected: ((Tab) -> Unit)? = null
    var selected: Tab = Tab.HOME
        private set

    private data class Item(val tab: Tab, val icon: ImageView, val label: TextView, val pill: LinearLayout)

    private val items = mutableListOf<Item>()

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BrandConfig.colorSurface)
        elevation = Ui.dp(context, 14f).toFloat()
        val pad = Ui.dp(context, 6f)
        setPadding(pad, pad, pad, pad)

        for (tab in Tab.entries) {
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val p = Ui.dp(context, 6f)
                setPadding(p, p, p, p)
                layoutParams = LayoutParams(0, Ui.dp(context, 52f), 1f)
                isClickable = true
                isFocusable = true
                contentDescription = tab.contentDescription
                background = ThemeEngine.glassBackground(radius = 14f, fillAlpha = 0x00, borderAlpha = 0x00)
                setOnClickListener {
                    Ui.haptic(this)
                    select(tab)
                    onTabSelected?.invoke(tab)
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(tab.icon)
                val s = Ui.dp(context, 21f)
                layoutParams = LayoutParams(s, s)
            }
            val label = Ui.text(context, 10f, BrandConfig.colorTextTertiary, Typeface.BOLD, Gravity.CENTER).apply {
                text = tab.label
            }
            pill.addView(icon)
            pill.addView(label)
            addView(pill)
            items.add(Item(tab, icon, label, pill))
        }
        select(Tab.HOME, animate = false)
    }

    fun select(tab: Tab, animate: Boolean = true) {
        selected = tab
        for (item in items) {
            val active = item.tab == tab
            val accent = ThemeEngine.accentColor()
            item.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (active) accent else BrandConfig.colorTextTertiary
            )
            item.label.setTextColor(if (active) accent else BrandConfig.colorTextTertiary)
            item.pill.background = if (active) {
                ThemeEngine.glassBackground(radius = 14f, fillAlpha = 0x16, borderAlpha = 0x14)
            } else {
                ThemeEngine.glassBackground(radius = 14f, fillAlpha = 0x00, borderAlpha = 0x00)
            }
            if (animate && active) {
                item.pill.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).withEndAction {
                    item.pill.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // keep the five tabs equally spaced
        var x = paddingLeft
        val w = (width - paddingLeft - paddingRight) / items.size
        for (item in items) {
            item.pill.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            item.pill.layout(x, paddingTop, x + w, height - paddingBottom)
            x += w
        }
    }
}
