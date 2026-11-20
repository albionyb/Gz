package com.gzbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
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
 * Live preview of the four edge-swipe zones on a phone outline.
 * Drag the dashed line to move the split; tap a zone or its label to choose what it does.
 * The edge width comes from the "Edge swipe width" setting and the preview follows it.
 */
class GestureAreaView(private val act: MainActivity) : View(act) {

    private val keys = listOf("edge_left_top", "edge_left_bottom", "edge_right_top", "edge_right_bottom")
    private val names = listOf("Left, upper", "Left, lower", "Right, upper", "Right, lower")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val phone = RectF()
    private val labelRects = Array(4) { RectF() }
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun splitPct() = act.prefs.getInt("gest_split", 50).coerceIn(25, 75)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(330f).toInt())
    }

    private fun layoutPhone() {
        val d = act.window.decorView
        val ratio = if (d.height > 0) d.width.toFloat() / d.height else 0.46f
        val ph = dp(300f)
        val pw = (ph * ratio).coerceIn(dp(120f), dp(170f))
        phone.set((width - pw) / 2f, dp(15f), (width + pw) / 2f, dp(15f) + ph)
    }

    private fun splitY() = phone.top + phone.height() * splitPct() / 100f

    override fun onDraw(c: Canvas) {
        layoutPhone()
        val sy = splitY()
        val scale = phone.height() / (act.window.decorView.height.takeIf { it > 0 } ?: 2400).toFloat()
        val edgePx = (act.prefs.getInt("gest_edge_dp", 20) * resources.displayMetrics.density * scale).coerceAtLeast(dp(6f))

        fill.style = Paint.Style.FILL; fill.color = C_SURFACE
        c.drawRoundRect(phone, dp(20f), dp(20f), fill)
        line.color = C_BORDER; line.strokeWidth = dp(1.5f); line.pathEffect = null
        c.drawRoundRect(phone, dp(20f), dp(20f), line)

        // protected band: the 200 dp Android lets the app keep from its own Back gesture
        val band = 200f * resources.displayMetrics.density * scale / 2f
        line.color = C_MUTED; line.strokeWidth = dp(1f); line.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(3f)), 0f)
        c.drawLine(phone.left + dp(4f), sy - band, phone.right - dp(4f), sy - band, line)
        c.drawLine(phone.left + dp(4f), sy + band, phone.right - dp(4f), sy + band, line)

        // four zones
        val zones = arrayOf(
            RectF(phone.left, phone.top, phone.left + edgePx, sy),
            RectF(phone.left, sy, phone.left + edgePx, phone.bottom),
            RectF(phone.right - edgePx, phone.top, phone.right, sy),
            RectF(phone.right - edgePx, sy, phone.right, phone.bottom)
        )
        for (i in 0..3) {
            fill.color = if (i % 2 == 0) C_ACCENT else C_ICE
            fill.alpha = if (act.gestureAction(keys[i]) != "none") 150 else 70
            c.drawRect(zones[i], fill)
        }

        // split line with a grab handle
        line.color = C_ICE; line.strokeWidth = dp(2f); line.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        c.drawLine(phone.left, sy, phone.right, sy, line)
        line.pathEffect = null
        fill.color = C_ICE; fill.alpha = 255
        c.drawRoundRect(RectF(phone.centerX() - dp(18f), sy - dp(9f), phone.centerX() + dp(18f), sy + dp(9f)), dp(9f), dp(9f), fill)
        line.color = C_SURFACE; line.strokeWidth = dp(1.6f)
        for (k in -1..1) c.drawLine(phone.centerX() - dp(7f), sy + k * dp(3.5f), phone.centerX() + dp(7f), sy + k * dp(3.5f), line)

        // labels beside the phone
        val lw = phone.left - dp(10f)
        for (i in 0..3) {
            val top = i % 2 == 0
            val cy = if (top) (phone.top + sy) / 2f else (sy + phone.bottom) / 2f
            val r = labelRects[i]
            if (i < 2) r.set(dp(4f), cy - dp(26f), dp(4f) + lw - dp(4f), cy + dp(26f))
            else r.set(phone.right + dp(10f), cy - dp(26f), width - dp(4f), cy + dp(26f))
            fill.color = if (top) C_ACCENT else C_ICE; fill.alpha = 45
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
            line.color = if (top) C_ACCENT else C_ICE; line.strokeWidth = dp(1f)
            c.drawRoundRect(r, dp(10f), dp(10f), line)
            txt.textAlign = Paint.Align.CENTER
            txt.color = C_TEXT; txt.textSize = dp(12f)
            c.drawText(names[i], r.centerX(), r.centerY() - dp(3f), txt)
            txt.color = C_MUTED; txt.textSize = dp(11f)
            val a = TextUtils.ellipsize(GestureMap.label(act.gestureAction(keys[i])), txt, r.width() - dp(8f), TextUtils.TruncateAt.END)
            c.drawText(a.toString(), r.centerX(), r.centerY() + dp(14f), txt)
        }
    }

    private fun zoneAt(x: Float, y: Float): Int {
        for (i in 0..3) if (labelRects[i].contains(x, y)) return i
        if (phone.contains(x, y) && (x < phone.left + phone.width() * 0.4f || x > phone.right - phone.width() * 0.4f)) {
            val left = x < phone.centerX()
            val top = y < splitY()
            return (if (left) 0 else 2) + (if (top) 0 else 1)
        }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                dragging = phone.contains(e.x, e.y) && abs(e.y - splitY()) < dp(22f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - downY) > dp(6f) || abs(e.x - downX) > dp(6f)) moved = true
                if (dragging) {
                    val pct = ((e.y - phone.top) / phone.height() * 100f).toInt().coerceIn(25, 75)
                    if (pct != splitPct()) { act.prefs.edit().putInt("gest_split", pct).apply(); invalidate() }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) act.applyGestureExclusion()
                else if (!moved) {
                    val z = zoneAt(e.x, e.y)
                    if (z >= 0) GestureMap.GESTURES.firstOrNull { it.key == keys[z] }?.let { act.chooseGestureAction(it) }
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
        }
        return super.onTouchEvent(e)
    }
}
