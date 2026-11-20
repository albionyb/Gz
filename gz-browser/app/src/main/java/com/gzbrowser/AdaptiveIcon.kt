package com.gzbrowser

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser

/**
 * One of the vector icons (ic_bm_*, ic_m_*) redrawn with the current theme colors.
 *
 * The icon files keep their original Ocean colors. When drawing, those colors are swapped for the live theme:
 * light lines -> icon color, bright details -> accent color, tile fill -> tile color. Any other color
 * (the pink gem, gold bits) is drawn as it is. The colors are read while drawing, so a new theme or accent
 * shows up as soon as the UI is rebuilt.
 */
class AdaptiveIcon private constructor(private val ic: Parsed, private val density: Float) : Drawable() {

    private class Node(val path: Path, val fill: Int, val stroke: Int, val strokeW: Float, val cap: Paint.Cap, val join: Paint.Join)
    private class Parsed(val vw: Float, val vh: Float, val nodes: List<Node>)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaVal = 255

    private fun themed(c: Int): Int = when (c) {
        0xFFCFEEFF.toInt() -> MainActivity.C_ICE
        0xFFE6F6FF.toInt() -> MainActivity.C_TEXT
        0xFF3FB7E8.toInt() -> MainActivity.C_ACCENT
        0xFF0F2A45.toInt() -> MainActivity.C_TILE
        else -> c
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / ic.vw, b.height() / ic.vh)
        for (n in ic.nodes) {
            if (n.fill != 0) {
                val c = themed(n.fill)
                paint.style = Paint.Style.FILL
                paint.color = c
                paint.alpha = Color.alpha(c) * alphaVal / 255
                canvas.drawPath(n.path, paint)
            }
            if (n.stroke != 0) {
                val c = themed(n.stroke)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = n.strokeW
                paint.strokeCap = n.cap
                paint.strokeJoin = n.join
                paint.color = c
                paint.alpha = Color.alpha(c) * alphaVal / 255
                canvas.drawPath(n.path, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { alphaVal = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = (ic.vw * density).toInt()
    override fun getIntrinsicHeight(): Int = (ic.vh * density).toInt()

    companion object {
        private const val NS = "http://schemas.android.com/apk/res/android"
        private val cache = HashMap<Int, Parsed>()

        /** The adaptive version of drawable [res]. Falls back to the plain drawable if it can't be read. */
        fun of(ctx: Context, res: Int): Drawable {
            val parsed = cache[res] ?: parse(ctx.resources, res)?.also { cache[res] = it }
            return if (parsed != null) AdaptiveIcon(parsed, ctx.resources.displayMetrics.density)
            else ctx.getDrawable(res)!!
        }

        private fun colorAttr(p: XmlResourceParser, name: String): Int {
            val s = p.getAttributeValue(NS, name) ?: return 0
            return try { Color.parseColor(s) } catch (e: Exception) { p.getAttributeIntValue(NS, name, 0) }
        }

        private fun parse(res: Resources, id: Int): Parsed? = try {
            val p = res.getXml(id)
            var vw = 48f
            var vh = 48f
            val nodes = ArrayList<Node>()
            var rot = 0f
            var px = 0f
            var py = 0f
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "vector" -> {
                            vw = p.getAttributeFloatValue(NS, "viewportWidth", 48f)
                            vh = p.getAttributeFloatValue(NS, "viewportHeight", 48f)
                        }
                        "group" -> {
                            rot = p.getAttributeFloatValue(NS, "rotation", 0f)
                            px = p.getAttributeFloatValue(NS, "pivotX", 0f)
                            py = p.getAttributeFloatValue(NS, "pivotY", 0f)
                        }
                        "path" -> {
                            val data = p.getAttributeValue(NS, "pathData")
                            if (data != null) {
                                val path = PathParser.createPathFromPathData(data)
                                if (rot != 0f) path.transform(Matrix().apply { setRotate(rot, px, py) })
                                val cap = when (p.getAttributeValue(NS, "strokeLineCap")) {
                                    "round" -> Paint.Cap.ROUND
                                    "square" -> Paint.Cap.SQUARE
                                    else -> Paint.Cap.BUTT
                                }
                                val join = when (p.getAttributeValue(NS, "strokeLineJoin")) {
                                    "round" -> Paint.Join.ROUND
                                    "bevel" -> Paint.Join.BEVEL
                                    else -> Paint.Join.MITER
                                }
                                nodes += Node(
                                    path, colorAttr(p, "fillColor"), colorAttr(p, "strokeColor"),
                                    p.getAttributeFloatValue(NS, "strokeWidth", 0f), cap, join
                                )
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "group") {
                    rot = 0f
                }
                ev = p.next()
            }
            p.close()
            if (nodes.isEmpty()) null else Parsed(vw, vh, nodes)
        } catch (e: Throwable) { null }
    }
}
