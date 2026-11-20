package com.gzbrowser

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sqrt

/** The gestures Gz Browser knows, the actions they can be mapped to, and the starting mapping. */
object GestureMap {
    class G(val key: String, val label: String, val def: String)

    val GESTURES = listOf(
        G("edge_left_top", "Swipe in from the left edge, upper half", "none"),
        G("edge_left_bottom", "Swipe in from the left edge, lower half", "none"),
        G("edge_right_top", "Swipe in from the right edge, upper half", "none"),
        G("edge_right_bottom", "Swipe in from the right edge, lower half", "none"),
        G("bar_left", "Swipe left on the address bar", "none"),
        G("bar_right", "Swipe right on the address bar", "none"),
        G("bar_double", "Double-tap the address bar", "none"),
        G("dock_up", "Swipe up on the bottom bar", "none"),
        G("dock_down", "Swipe down on the bottom bar", "none"),
        G("two_up", "Two fingers swipe up", "none"),
        G("two_down", "Two fingers swipe down", "none"),
        G("three_up", "Three fingers swipe up", "none"),
        G("three_down", "Three fingers swipe down", "none"),
        G("three_tap", "Three-finger tap", "none")
    )

    val ACTIONS = listOf(
        "none" to "Nothing",
        "back" to "Back",
        "forward" to "Forward",
        "reload" to "Reload",
        "home" to "Home",
        "next_tab" to "Next tab",
        "prev_tab" to "Previous tab",
        "new_tab" to "New tab",
        "close_tab" to "Close tab",
        "tabs" to "Tabs overview",
        "menu" to "Menu",
        "drawer" to "Edge drawer",
        "top" to "Scroll to top",
        "bottom" to "Scroll to bottom",
        "fullscreen" to "Full screen on / off",
        "desktop" to "Desktop site on / off",
        "screenshot" to "Screenshot",
        "find" to "Find in page",
        "notes" to "Notes on / off",
        "drops" to "Drop tracker on / off",
        "spark" to "Spark calculator",
        "exit" to "Exit Gz Browser"
    )

    const val BM = "bm:"   // a saved link: "bm:" + label + \u0001 + url

    fun bmAction(label: String, url: String) = BM + label + "\u0001" + url
    fun bmLabel(action: String) = action.removePrefix(BM).substringBefore('\u0001')
    fun bmUrl(action: String) = action.substringAfter('\u0001', "")

    fun label(action: String): String =
        if (action.startsWith(BM)) "Bookmark: " + bmLabel(action).ifBlank { bmUrl(action) }
        else ACTIONS.firstOrNull { it.first == action }?.second ?: "Nothing"
}

/**
 * Reads the raw touch stream before the page sees it. Only gestures that start on a screen edge, on the
 * address bar, on the bottom bar, or use two / three fingers are looked at, and only gestures that have an
 * action mapped are ever claimed, so normal taps and swipes on a page (and in games) pass straight through.
 */
class GestureEngine(
    private val density: Float,
    private val widthPx: () -> Int,
    private val heightPx: () -> Int,
    private val splitFrac: () -> Float,
    private val edgePx: () -> Int,
    private val barRect: () -> Rect?,
    private val dockRect: () -> Rect?,
    private val mapped: (String) -> Boolean,
    private val fire: (String) -> Unit
) {
    companion object {
        const val PASS = 0    // let the page / views have the event
        const val CLAIM = 1   // a gesture was recognised: cancel what the page started, eat the rest
        const val EAT = 2     // gesture in progress: swallow the event
    }

    private fun dp(v: Float) = v * density

    private var region = 0            // 0 none, 1 left edge, 2 right edge, 3 address bar, 4 bottom bar
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var claimed = false
    private var pendingKey: String? = null
    private var maxPointers = 0
    private var mStartX = 0f
    private var mStartY = 0f
    private var mStartSpread = 0f
    private var mStartN = 0
    private var mStartT = 0L
    private var lastBarTap = 0L
    private var edgeKey = ""

    private fun inside(r: Rect?, ev: MotionEvent) = r != null && r.contains(ev.x.toInt(), ev.y.toInt())

    private fun reset() {
        region = 0; claimed = false; pendingKey = null; maxPointers = 0; mStartN = 0
    }

    private fun baseline(ev: MotionEvent) {
        val n = ev.pointerCount
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += ev.getX(i); cy += ev.getY(i) }
        cx /= n; cy /= n
        mStartX = cx; mStartY = cy; mStartN = n; mStartT = SystemClock.uptimeMillis()
        mStartSpread = spreadOf(ev, cx, cy)
    }

    private fun spreadOf(ev: MotionEvent, cx: Float, cy: Float): Float {
        var s = 0f
        for (i in 0 until ev.pointerCount) {
            val dx = ev.getX(i) - cx; val dy = ev.getY(i) - cy
            s += sqrt(dx * dx + dy * dy)
        }
        return s / ev.pointerCount
    }

    fun onTouch(ev: MotionEvent): Int {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                maxPointers = 1
                x0 = ev.x; y0 = ev.y; t0 = SystemClock.uptimeMillis()
                val w = widthPx(); val e = edgePx()
                val upper = ev.y < splitFrac() * heightPx()
                val kL = if (upper) "edge_left_top" else "edge_left_bottom"
                val kR = if (upper) "edge_right_top" else "edge_right_bottom"
                region = when {
                    ev.x <= e && mapped(kL) -> { edgeKey = kL; 1 }
                    ev.x >= w - e && mapped(kR) -> { edgeKey = kR; 2 }
                    inside(barRect(), ev) && (mapped("bar_left") || mapped("bar_right") || mapped("bar_double")) -> 3
                    inside(dockRect(), ev) && (mapped("dock_up") || mapped("dock_down")) -> 4
                    else -> 0
                }
                return PASS
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (claimed) return EAT
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                region = 0   // a second finger ends any edge / bar candidate
                if (ev.pointerCount in 2..3) baseline(ev)
                return PASS
            }
            MotionEvent.ACTION_MOVE -> {
                if (claimed) { checkPending(ev); return EAT }
                return if (ev.pointerCount == 1) singleMove(ev) else multiMove(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (claimed) return EAT
                // quick three-finger tap
                if (maxPointers == 3 && mStartN == 3 && mapped("three_tap") &&
                    SystemClock.uptimeMillis() - mStartT < 350 && drift(ev) < dp(24f)
                ) {
                    claimed = true
                    fire("three_tap")
                    return CLAIM
                }
                return PASS
            }
            MotionEvent.ACTION_UP -> {
                var result = if (claimed) EAT else PASS
                if (!claimed && region == 3 && mapped("bar_double")) {
                    val now = SystemClock.uptimeMillis()
                    if (abs(ev.x - x0) < dp(10f) && abs(ev.y - y0) < dp(10f) && now - t0 < 300) {
                        if (now - lastBarTap < 400) { lastBarTap = 0; fire("bar_double"); result = CLAIM }
                        else lastBarTap = now
                    }
                }
                reset()
                return result
            }
            MotionEvent.ACTION_CANCEL -> {
                val r = if (claimed) EAT else PASS
                reset()
                return r
            }
        }
        return if (claimed) EAT else PASS
    }

    private fun drift(ev: MotionEvent): Float {
        val n = ev.pointerCount
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += ev.getX(i); cy += ev.getY(i) }
        cx /= n; cy /= n
        val dx = cx - mStartX; val dy = cy - mStartY
        return sqrt(dx * dx + dy * dy)
    }

    private fun singleMove(ev: MotionEvent): Int {
        val dx = ev.x - x0
        val dy = ev.y - y0
        when (region) {
            1, 2 -> {
                val inward = if (region == 1) dx else -dx
                if (inward > dp(14f) && abs(dy) < inward * 0.8f) {
                    claimed = true
                    pendingKey = edgeKey
                    checkPending(ev)
                    return CLAIM
                }
            }
            3 -> if (abs(dx) > dp(48f) && abs(dy) < abs(dx) * 0.6f) {
                val key = if (dx < 0) "bar_left" else "bar_right"
                if (mapped(key)) { claimed = true; fire(key); return CLAIM }
                region = 0
            }
            4 -> if (abs(dy) > dp(36f) && abs(dx) < abs(dy) * 0.6f) {
                val key = if (dy < 0) "dock_up" else "dock_down"
                if (mapped(key)) { claimed = true; fire(key); return CLAIM }
                region = 0
            }
        }
        return PASS
    }

    /** Edge swipes are claimed early (so the page does not pan) and fire once the finger has travelled far enough. */
    private fun checkPending(ev: MotionEvent) {
        val k = pendingKey ?: return
        val dx = ev.x - x0
        val ok = if (k.startsWith("edge_left")) dx >= dp(56f) else -dx >= dp(56f)
        if (ok) { pendingKey = null; fire(k) }
    }

    private fun multiMove(ev: MotionEvent): Int {
        val n = ev.pointerCount
        if (n != mStartN || n < 2 || n > 3) return PASS
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += ev.getX(i); cy += ev.getY(i) }
        cx /= n; cy /= n
        val dx = cx - mStartX
        val dy = cy - mStartY
        if (abs(spreadOf(ev, cx, cy) - mStartSpread) > dp(60f)) return PASS   // a pinch, not a swipe
        if (abs(dy) > dp(90f) && abs(dx) < abs(dy) * 0.6f) {
            val key = (if (n == 2) "two_" else "three_") + (if (dy < 0) "up" else "down")
            if (mapped(key)) { claimed = true; fire(key); return CLAIM }
        }
        return PASS
    }
}
