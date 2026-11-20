package com.gzbrowser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Treasure chest icon drawn on a canvas.
 *  - [shake] wobbles the closed chest (used for "No drop"). Calling it again restarts it, so every tap animates.
 *  - [open] opens the lid, shows gold and a glow, holds a moment, then closes again (used for "Dropped").
 *  - [sparkly] shows three twinkling stars, but only while the chest is open (not when idle).
 */
class ChestView(context: Context, private val sparkly: Boolean = false) : View(context) {

    private var openT = 0f
    private var angle = 0f
    private var twinkle = 0f

    private var shakeAnim: ValueAnimator? = null
    private var openAnim: ValueAnimator? = null
    private var twinkleAnim: ValueAnimator? = null
    private val closeRun = Runnable { startClose() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    private val lidPath = Path().apply {
        moveTo(10f, 32f); lineTo(10f, 26f)
        arcTo(RectF(10f, 13f, 38f, 39f), 180f, 90f)
        lineTo(40f, 13f)
        arcTo(RectF(26f, 13f, 54f, 39f), 270f, 90f)
        lineTo(54f, 32f); close()
    }
    private val backLidPath = Path().apply { moveTo(14f, 26f); lineTo(18f, 6f); lineTo(46f, 6f); lineTo(50f, 26f); close() }
    private val star = Path().apply {
        moveTo(0f, -6f); lineTo(1.8f, -1.8f); lineTo(6f, 0f); lineTo(1.8f, 1.8f)
        lineTo(0f, 6f); lineTo(-1.8f, 1.8f); lineTo(-6f, 0f); lineTo(-1.8f, -1.8f); close()
    }
    private val sparkPos = arrayOf(floatArrayOf(10f, 12f), floatArrayOf(54f, 8f), floatArrayOf(32f, 2f))

    private fun paintFill(color: Int, a: Float = 1f): Paint { fill.color = color; fill.alpha = (255 * a).toInt().coerceIn(0, 255); return fill }
    private fun paintLine(color: Int, w: Float, a: Float = 1f): Paint {
        line.color = color; line.strokeWidth = w; line.alpha = (255 * a).toInt().coerceIn(0, 255); return line
    }

    // ----- animations -----

    fun shake() {
        shakeAnim?.cancel()
        angle = 0f
        shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener {
                val t = it.animatedValue as Float
                angle = -13f * (1f - t) * sin(t * 2f * PI.toFloat() * 2.5f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { angle = 0f; invalidate() }
            })
            start()
        }
    }

    fun open() {
        removeCallbacks(closeRun)
        openAnim?.cancel()
        openT = 0f
        shake()
        startTwinkle()
        openAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = DecelerateInterpolator()
            addUpdateListener { openT = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) postDelayed(closeRun, 1300) }
            })
            start()
        }
    }

    private fun startClose() {
        openAnim?.cancel()
        openAnim = ValueAnimator.ofFloat(openT, 0f).apply {
            duration = 380
            addUpdateListener { openT = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) { twinkleAnim?.cancel(); twinkleAnim = null; invalidate() } }
            })
            start()
        }
    }

    private fun startTwinkle() {
        if (!sparkly) return
        twinkleAnim?.cancel()
        twinkleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { twinkle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(closeRun)
        shakeAnim?.cancel(); openAnim?.cancel(); twinkleAnim?.cancel(); twinkleAnim = null
        super.onDetachedFromWindow()
    }

    // ----- drawing (64 x 64 virtual units, with a margin so sparkles are not clipped) -----

    override fun onDraw(c: Canvas) {
        val sz = minOf(width, height).toFloat()
        if (sz <= 0f) return
        c.save()
        c.translate((width - sz) / 2f, (height - sz) / 2f)
        c.scale(sz / 72f, sz / 72f)
        c.translate(4f, 6f)
        c.rotate(angle, 32f, 58f)

        val t = openT
        val ice = 0xFFCFEEFF.toInt()      // same line colour as the bookmark icons
        val cyan = 0xFF3FB7E8.toInt()     // same accent as the bookmark icons

        if (t > 0f) c.drawCircle(32f, 24f, 22f, paintFill(cyan, 0.16f * t))

        if (t > 0f) {
            c.save()
            c.translate(0f, 14f * (1f - t))
            c.scale(1f, 0.2f + 0.8f * t, 32f, 26f)
            c.drawPath(backLidPath, paintLine(ice, 2.2f, t))
            c.restore()

            val ct = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
            if (ct > 0f) {
                c.save()
                c.translate(0f, 14f * (1f - ct))
                for (p in arrayOf(floatArrayOf(23f, 30f, 6f), floatArrayOf(41f, 30f, 6f), floatArrayOf(32f, 25f, 6.5f))) {
                    c.drawCircle(p[0], p[1], p[2], paintFill(cyan, ct))
                    c.drawCircle(p[0], p[1], p[2], paintLine(ice, 1.6f, ct))
                }
                c.restore()
            }
        }

        // body: outline only, one centre strap
        c.drawRoundRect(10f, 32f, 54f, 54f, 3f, 3f, paintLine(ice, 2.2f))
        c.drawLine(32f, 40f, 32f, 54f, paintLine(ice, 2.2f))

        // lid (flattens and fades as the chest opens)
        if (t < 1f) {
            val a = 1f - t
            c.save()
            c.scale(1f, 1f - 0.9f * t, 32f, 32f)
            c.drawPath(lidPath, paintLine(ice, 2.2f, a))
            c.drawLine(10f, 32f, 54f, 32f, paintLine(ice, 2.2f, a))
            c.drawRoundRect(28f, 30f, 36f, 40f, 1.5f, 1.5f, paintFill(cyan, a))
            c.drawRoundRect(28f, 30f, 36f, 40f, 1.5f, 1.5f, paintLine(ice, 1.6f, a))
            c.restore()
        }

        if (sparkly && t > 0f) {
            for (i in sparkPos.indices) {
                val ph = (((twinkle - i * 0.2778f) % 1f) + 1f) % 1f
                val tri = 0.5f - 0.5f * cos(2f * PI.toFloat() * ph)
                val s = 0.3f + 0.7f * tri
                c.save()
                c.translate(sparkPos[i][0], sparkPos[i][1])
                c.scale(s, s)
                c.drawPath(star, paintFill(0xFFCFEEFF.toInt(), (0.2f + 0.8f * tri) * t))
                c.restore()
            }
        }
        c.restore()
    }
}

/** Horizontal pager with no libraries: one page fills the width, a swipe snaps to the next or previous page. */
class SnapHScroll(context: Context) : HorizontalScrollView(context) {
    var pageW = 0
    var onPage: ((Int) -> Unit)? = null
    private var flung = false

    init { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }

    private fun pages(): Int = (getChildAt(0) as? ViewGroup)?.childCount?.coerceAtLeast(1) ?: 1

    fun snapTo(i: Int, smooth: Boolean = true) {
        val p = i.coerceIn(0, pages() - 1)
        if (smooth) smoothScrollTo(p * pageW, 0) else scrollTo(p * pageW, 0)
        onPage?.invoke(p)
    }

    override fun fling(velocityX: Int) {
        flung = true
        if (pageW <= 0) return
        val base = scrollX / pageW.toFloat()
        snapTo(when {
            velocityX > 300 -> floor(base).toInt() + 1
            velocityX < -300 -> ceil(base).toInt() - 1
            else -> base.roundToInt()
        })
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        val r = super.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && !flung && pageW > 0) {
            snapTo((scrollX / pageW.toFloat()).roundToInt())
        }
        return r
    }
}
