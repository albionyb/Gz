package com.gzbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shows a screenshot with a draggable crop rectangle (drag edges/corners to resize, inside to move). */
class CropView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00B8E6.toInt() }
    private val dst = RectF()
    private val crop = RectF()
    private var scale = 1f
    private var edges = 0   // bitmask: 1 left, 2 top, 4 right, 8 bottom
    private var moving = false
    private var lx = 0f
    private var ly = 0f
    private val hit = 36f * d
    private val minSize = 60f * d

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        val ix = dw * 0.12f
        val iy = dh * 0.12f
        crop.set(dst.left + ix, dst.top + iy, dst.right - ix, dst.bottom - iy)
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(bmp, null, dst, bmpPaint)
        c.drawRect(dst.left, dst.top, dst.right, crop.top, dimPaint)
        c.drawRect(dst.left, crop.bottom, dst.right, dst.bottom, dimPaint)
        c.drawRect(dst.left, crop.top, crop.left, crop.bottom, dimPaint)
        c.drawRect(crop.right, crop.top, dst.right, crop.bottom, dimPaint)
        c.drawRect(crop, linePaint)
        val r = 8f * d
        for (x in floatArrayOf(crop.left, crop.right)) for (y in floatArrayOf(crop.top, crop.bottom))
            c.drawCircle(x, y, r, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                val inY = e.y > crop.top - hit && e.y < crop.bottom + hit
                val inX = e.x > crop.left - hit && e.x < crop.right + hit
                edges = 0
                if (inY && abs(e.x - crop.left) < hit) edges = edges or 1
                if (inX && abs(e.y - crop.top) < hit) edges = edges or 2
                if (inY && abs(e.x - crop.right) < hit) edges = edges or 4
                if (inX && abs(e.y - crop.bottom) < hit) edges = edges or 8
                moving = edges == 0 && crop.contains(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lx
                val dy = e.y - ly
                lx = e.x; ly = e.y
                if (moving) {
                    val mx = min(max(dx, dst.left - crop.left), dst.right - crop.right)
                    val my = min(max(dy, dst.top - crop.top), dst.bottom - crop.bottom)
                    crop.offset(mx, my)
                } else {
                    if (edges and 1 != 0) crop.left = min(max(crop.left + dx, dst.left), crop.right - minSize)
                    if (edges and 2 != 0) crop.top = min(max(crop.top + dy, dst.top), crop.bottom - minSize)
                    if (edges and 4 != 0) crop.right = max(min(crop.right + dx, dst.right), crop.left + minSize)
                    if (edges and 8 != 0) crop.bottom = max(min(crop.bottom + dy, dst.bottom), crop.top + minSize)
                }
                invalidate()
                return true
            }
        }
        return true
    }

    fun cropped(): Bitmap {
        val x = ((crop.left - dst.left) / scale).toInt().coerceIn(0, bmp.width - 1)
        val y = ((crop.top - dst.top) / scale).toInt().coerceIn(0, bmp.height - 1)
        val w = ((crop.width()) / scale).toInt().coerceIn(1, bmp.width - x)
        val h = ((crop.height()) / scale).toInt().coerceIn(1, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }
}
