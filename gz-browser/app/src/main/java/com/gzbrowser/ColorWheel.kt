package com.gzbrowser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.gzbrowser.MainActivity.Companion.C_ACCENT
import com.gzbrowser.MainActivity.Companion.C_BORDER
import com.gzbrowser.MainActivity.Companion.C_ICE
import com.gzbrowser.MainActivity.Companion.C_MUTED
import com.gzbrowser.MainActivity.Companion.C_TEXT
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Hue (angle) and saturation (distance from the center) wheel. Brightness is set from outside with [setValue]. */
class ColorWheelView(ctx: Context) : View(ctx) {
    private val hsv = floatArrayOf(0f, 0f, 1f)
    var onColor: ((Int) -> Unit)? = null

    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sat = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val d = resources.displayMetrics.density
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    val color: Int get() = Color.HSVToColor(hsv)
    val value: Float get() = hsv[2]

    fun setColor(c: Int) { Color.colorToHSV(c, hsv); invalidate() }
    fun setValue(v: Float) { hsv[2] = v.coerceIn(0f, 1f); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w)
        val cap = (260 * d).toInt()
        val s = if (maxW > 0) minOf(maxW, cap) else cap
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f; r = minOf(w, h) / 2f - 12 * d
        val cols = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        wheel.shader = SweepGradient(cx, cy, cols, null)
        sat.shader = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rim.strokeWidth = 1 * d
        knobRing.strokeWidth = 2 * d
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, r, wheel)
        canvas.drawCircle(cx, cy, r, sat)
        dim.color = Color.argb(((1f - hsv[2]) * 255).toInt().coerceIn(0, 255), 0, 0, 0)
        canvas.drawCircle(cx, cy, r, dim)
        canvas.drawCircle(cx, cy, r, rim)
        val a = Math.toRadians(hsv[0].toDouble())
        val kx = cx + (cos(a) * hsv[1] * r).toFloat()
        val ky = cy + (sin(a) * hsv[1] * r).toFloat()
        knobFill.color = color
        canvas.drawCircle(kx, ky, 11 * d, knobFill)
        knobRing.color = 0x99000000.toInt()
        canvas.drawCircle(kx, ky, 13 * d, knobRing)
        knobRing.color = Color.WHITE
        canvas.drawCircle(kx, ky, 11 * d, knobRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = e.x - cx
                val dy = e.y - cy
                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (deg < 0) deg += 360f
                hsv[0] = deg.coerceIn(0f, 360f)
                hsv[1] = (sqrt(dx * dx + dy * dy) / r).coerceIn(0f, 1f)
                invalidate()
                onColor?.invoke(color)
            }
        }
        return true
    }
}

private fun hexOf(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

/**
 * Color wheel dialog used everywhere a color is picked: theme colors, accent, profiles, tab groups, bookmarks and folders.
 * [neutralLabel] adds a third button (for example "Theme default") that runs [onNeutral].
 */
internal fun MainActivity.colorDialog(
    title: String,
    initial: Int,
    neutralLabel: String? = null,
    onNeutral: (() -> Unit)? = null,
    onPick: (Int) -> Unit
) {
    var cur = initial or 0xFF000000.toInt()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(4))
    }
    val wheel = ColorWheelView(this).apply { setColor(cur) }
    val preview = View(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat(); setColor(cur); setStroke(dp(1), C_BORDER)
        }
    }
    val hex = EditText(this).apply {
        setSingleLine()
        setTextColor(C_TEXT); setHintTextColor(C_MUTED)
        hint = "#RRGGBB"
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(7))
        setText(hexOf(cur))
    }
    val seek = SeekBar(this).apply {
        max = 100
        progress = (wheel.value * 100).roundToInt()
        progressTintList = android.content.res.ColorStateList.valueOf(C_ACCENT)
        thumbTintList = android.content.res.ColorStateList.valueOf(C_ICE)
    }
    var syncing = false
    fun paint() { (preview.background as GradientDrawable).setColor(cur) }
    fun setHexText() { syncing = true; hex.setText(hexOf(cur)); syncing = false }
    fun setAll(c: Int) {
        cur = c or 0xFF000000.toInt()
        wheel.setColor(cur)
        seek.progress = (wheel.value * 100).roundToInt()
        paint(); setHexText()
    }

    wheel.onColor = { c -> cur = c; paint(); setHexText() }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            wheel.setValue(p / 100f)
            cur = wheel.color
            paint(); setHexText()
        }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    })
    hex.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (syncing) return
            val t = s.toString().trim().removePrefix("#")
            if (t.length != 6) return
            val v = t.toLongOrNull(16) ?: return
            cur = 0xFF000000.toInt() or v.toInt()
            wheel.setColor(cur)
            seek.progress = (wheel.value * 100).roundToInt()
            paint()
        }
    })

    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    top.addView(preview, LinearLayout.LayoutParams(dp(56), dp(40)).apply { rightMargin = dp(12) })
    top.addView(hex, LinearLayout.LayoutParams(0, -2, 1f))
    box.addView(top)
    box.addView(wheel, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
    box.addView(TextView(this).apply {
        text = "Brightness"; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(8), 0, 0)
    })
    box.addView(seek)

    val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    Theme.swatches.forEach { sw ->
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(sw.color); setStroke(dp(1), C_BORDER)
            }
            setOnClickListener { setAll(sw.color) }
        }
        quick.addView(dot, LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(3), dp(6), dp(3), dp(6)) })
    }
    box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) })

    val b = AlertDialog.Builder(this).setTitle(title)
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Apply") { _, _ -> onPick(cur or 0xFF000000.toInt()) }
    if (neutralLabel != null) b.setNeutralButton(neutralLabel) { _, _ -> onNeutral?.invoke() }
    b.showT()
}
