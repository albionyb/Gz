package com.gzbrowser

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.gzbrowser.MainActivity.Companion.C_ACCENT
import com.gzbrowser.MainActivity.Companion.C_BORDER
import com.gzbrowser.MainActivity.Companion.C_ICE
import com.gzbrowser.MainActivity.Companion.C_MUTED
import com.gzbrowser.MainActivity.Companion.C_SURFACE
import com.gzbrowser.MainActivity.Companion.C_TEXT
import kotlin.math.abs

/**
 * Same look as the edge-swipe preview, for the other gestures: address bar ("bar"), bottom bar ("dock"),
 * "two" fingers and "three" fingers. Tap a chip or its spot on the phone to choose the action.
 */
class GestureGroupView(private val act: MainActivity, private val kind: String) : View(act) {

    private class Chip(val key: String, val name: String, val left: Boolean, val yf: Float)

    private val chips: List<Chip> = when (kind) {
        "bar" -> listOf(Chip("bar_left", "Swipe left", true, 0.22f), Chip("bar_double", "Double-tap", true, 0.62f), Chip("bar_right", "Swipe right", false, 0.22f))
        "dock" -> listOf(Chip("dock_up", "Swipe up", true, 0.6f), Chip("dock_down", "Swipe down", false, 0.6f))
        "two" -> listOf(Chip("two_up", "Swipe up", true, 0.3f), Chip("two_down", "Swipe down", false, 0.7f))
        else -> listOf(Chip("three_up", "Swipe up", true, 0.25f), Chip("three_tap", "Tap", false, 0.5f), Chip("three_down", "Swipe down", true, 0.75f))
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val phone = RectF()
    private val bar = RectF()
    private val rects = Array(chips.size) { RectF() }
    private var downX = 0f
    private var downY = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(240f).toInt())
    }

    private fun arrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, line)
        val dx = x2 - x1; val dy = y2 - y1
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / len; val uy = dy / len
        val s = dp(5f)
        c.drawLine(x2, y2, x2 - ux * s - uy * s, y2 - uy * s + ux * s, line)
        c.drawLine(x2, y2, x2 - ux * s + uy * s, y2 - uy * s - ux * s, line)
    }

    private fun layoutPhone() {
        val d = act.window.decorView
        val ratio = if (d.height > 0) d.width.toFloat() / d.height else 0.46f
        val ph = dp(210f)
        val pw = (ph * ratio).coerceIn(dp(100f), dp(130f))
        phone.set((width - pw) / 2f, dp(15f), (width + pw) / 2f, dp(15f) + ph)
        val bh = if (kind == "dock") dp(30f) else dp(26f)
        if (kind == "bar") bar.set(phone.left + dp(8f), phone.top + dp(18f), phone.right - dp(8f), phone.top + dp(18f) + bh)
        else bar.set(phone.left + dp(8f), phone.bottom - dp(18f) - bh, phone.right - dp(8f), phone.bottom - dp(18f))
    }

    override fun onDraw(c: Canvas) {
        layoutPhone()
        fill.style = Paint.Style.FILL; fill.color = C_SURFACE; fill.alpha = 255
        c.drawRoundRect(phone, dp(20f), dp(20f), fill)
        line.color = C_BORDER; line.strokeWidth = dp(1.5f)
        c.drawRoundRect(phone, dp(20f), dp(20f), line)

        val cx = phone.centerX(); val cy = phone.centerY()
        line.color = C_ICE; line.strokeWidth = dp(2f)
        when (kind) {
            "bar", "dock" -> {
                fill.color = C_ACCENT; fill.alpha = 70
                c.drawRoundRect(bar, dp(8f), dp(8f), fill)
                line.color = C_ACCENT; line.strokeWidth = dp(1f)
                c.drawRoundRect(bar, dp(8f), dp(8f), line)
                line.color = C_ICE; line.strokeWidth = dp(2f)
                val by = bar.centerY()
                if (kind == "bar") {
                    arrow(c, cx - dp(14f), by, bar.left + dp(8f), by)
                    arrow(c, cx + dp(14f), by, bar.right - dp(8f), by)
                    line.strokeWidth = dp(1.5f)
                    c.drawCircle(cx, by, dp(7f), line)
                    txt.textAlign = Paint.Align.CENTER; txt.color = C_ICE; txt.textSize = dp(9f)
                    c.drawText("2", cx, by + dp(3.2f), txt)
                } else {
                    val lx = (bar.left + cx) / 2f; val rx = (cx + bar.right) / 2f
                    arrow(c, lx, by + dp(9f), lx, by - dp(9f))
                    arrow(c, rx, by - dp(9f), rx, by + dp(9f))
                }
            }
            else -> {
                val n = if (kind == "two") 2 else 3
                fill.color = C_ICE; fill.alpha = 130
                for (i in 0 until n) c.drawCircle(cx + (i - (n - 1) / 2f) * dp(24f), cy, dp(7f), fill)
                arrow(c, cx, cy - dp(16f), cx, cy - dp(52f))
                arrow(c, cx, cy + dp(16f), cx, cy + dp(52f))
            }
        }

        // chips
        val lw = phone.left - dp(10f)
        for (i in chips.indices) {
            val ch = chips[i]
            val cyc = phone.top + phone.height() * ch.yf
            val r = rects[i]
            if (ch.left) r.set(dp(4f), cyc - dp(26f), dp(4f) + lw - dp(4f), cyc + dp(26f))
            else r.set(phone.right + dp(10f), cyc - dp(26f), width - dp(4f), cyc + dp(26f))
            fill.color = C_ACCENT; fill.alpha = if (act.gestureAction(ch.key) != "none") 70 else 35
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
            line.color = C_ACCENT; line.strokeWidth = dp(1f)
            c.drawRoundRect(r, dp(10f), dp(10f), line)
            txt.textAlign = Paint.Align.CENTER
            txt.color = C_TEXT; txt.textSize = dp(12f)
            c.drawText(ch.name, r.centerX(), r.centerY() - dp(3f), txt)
            txt.color = C_MUTED; txt.textSize = dp(11f)
            val a = TextUtils.ellipsize(GestureMap.label(act.gestureAction(ch.key)), txt, r.width() - dp(8f), TextUtils.TruncateAt.END)
            c.drawText(a.toString(), r.centerX(), r.centerY() + dp(14f), txt)
        }
    }

    private fun hit(x: Float, y: Float): String? {
        for (i in chips.indices) if (rects[i].contains(x, y)) return chips[i].key
        if (!phone.contains(x, y)) return null
        val cx = phone.centerX(); val cy = phone.centerY()
        return when (kind) {
            "bar" -> if (abs(y - bar.centerY()) < dp(18f)) (if (x < cx - dp(14f)) "bar_left" else if (x > cx + dp(14f)) "bar_right" else "bar_double") else null
            "dock" -> if (abs(y - bar.centerY()) < dp(20f)) (if (x < cx) "dock_up" else "dock_down") else null
            "two" -> if (y < cy) "two_up" else "two_down"
            else -> if (abs(x - cx) < dp(16f) && abs(y - cy) < dp(16f)) "three_tap" else if (y < cy) "three_up" else "three_down"
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) < dp(8f) && abs(e.y - downY) < dp(8f)) {
                    val k = hit(e.x, e.y)
                    if (k != null) GestureMap.GESTURES.firstOrNull { it.key == k }?.let { act.chooseGestureAction(it) }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
