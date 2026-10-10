package app.orbit.launcher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Orbit's widget host: every widget on home is one of its views. */
class OrbitWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        OrbitWidgetView(context)

    companion object {
        const val HOST_ID = 1024
    }
}

/**
 * A widget that still works normally (taps, scrolling), but a long press anywhere
 * on it opens Orbit's menu, and moving after the long press drags it.
 */
class OrbitWidgetView(context: Context) : AppWidgetHostView(context) {
    interface Gestures {
        fun onLongPress()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var gestures: Gestures? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var dragging = false
    private var sizeDp = SizeF(0f, 0f)

    private val longPress = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        cancelChildren()
        gestures?.onLongPress()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                longPressed = false
                dragging = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (longPressed) {
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) gestures?.onDrag(dx, dy)
                    return true
                }
                if (hypot(dx, dy) > slop) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (longPressed) {
                    if (dragging) gestures?.onDragEnd()
                    longPressed = false
                    dragging = false
                    return true
                }
            }
        }
        super.dispatchTouchEvent(ev)
        // Keep the whole gesture even when the widget itself ignores it, so a long press works anywhere.
        return true
    }

    /** The widget's buttons shouldn't also fire once the long press has taken over. */
    private fun cancelChildren() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** Tells the widget how big it is, only when that changes (each call wakes the app that owns it). */
    fun setSizeDp(w: Float, h: Float) {
        val size = SizeF(w, h)
        if (size == sizeDp) return
        sizeDp = size
        runCatching { updateAppWidgetSize(Bundle(), listOf(size)) }
    }
}

/** How many home cells a widget wants, from what its app says. */
fun widgetSpan(info: AppWidgetProviderInfo, density: Float, cols: Int, rows: Int): Pair<Int, Int> {
    // Android's classic rule for widgets without a target size: 40dp is 1 cell, 110dp is 2, 180dp is 3...
    fun cells(px: Int) = ((px / density + 30f) / 70f).toInt().coerceAtLeast(1)
    val w = if (info.targetCellWidth > 0) info.targetCellWidth else cells(info.minWidth)
    val h = if (info.targetCellHeight > 0) info.targetCellHeight else cells(info.minHeight)
    return w.coerceIn(1, cols) to h.coerceIn(1, rows)
}
