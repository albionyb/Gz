package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A floating shortcut button drawn above the page.
 * [fx]/[fy] are 0..1 positions inside the free space of the screen, so the button stays on screen
 * when the phone rotates. [locked] buttons cannot be dragged (tap still works).
 */
data class FloatBtn(
    val id: Long,
    var action: String,      // back, forward, reload, home, tabs, menu, shot, full, bubbles, bookmark
    var label: String,
    var url: String,
    var color: Int,
    var icon: String,
    var fx: Float,
    var fy: Float,
    var size: Int,           // dp
    var alpha: Int,          // 20..100 %
    var locked: Boolean
)

class FloatStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<FloatBtn>()

    init {
        val s = prefs.getString("float_buttons", null)
        if (s != null) try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += FloatBtn(
                    o.getLong("id"), o.getString("a"), o.optString("l", ""), o.optString("u", ""),
                    o.optInt("c", 0xFF00B8E6.toInt()), o.optString("i", ""),
                    o.optDouble("x", 0.9).toFloat(), o.optDouble("y", 0.5).toFloat(),
                    o.optInt("s", 48), o.optInt("al", 90), o.optBoolean("k", false)
                )
            }
        } catch (e: Exception) { items.clear() }
    }

    fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("a", it.action).put("l", it.label).put("u", it.url)
                    .put("c", it.color).put("i", it.icon).put("x", it.fx.toDouble()).put("y", it.fy.toDouble())
                    .put("s", it.size).put("al", it.alpha).put("k", it.locked)
            )
        }
        prefs.edit().putString("float_buttons", arr.toString()).apply()
    }
}

/** A Notes / Drop tracker panel that is currently open (overlay or popup). */
class PanelHandle(val dismiss: () -> Unit)

/** Vertical layout that is as tall as its content but never taller than [maxH]. */
class CapLinear(ctx: android.content.Context) : android.widget.LinearLayout(ctx) {
    var maxH = Int.MAX_VALUE
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val cap = android.view.View.MeasureSpec.makeMeasureSpec(
            minOf(android.view.View.MeasureSpec.getSize(heightSpec), maxH), android.view.View.MeasureSpec.AT_MOST
        )
        super.onMeasure(widthSpec, cap)
    }
}
