package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A user-made group of tabs. Tabs point to it with [Tab.group]; the order of [TabGroupStore.items] is the order shown. */
class TabGroup(val id: String, var name: String, var color: Int, var collapsed: Boolean = false)

class TabGroupStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<TabGroup>()

    companion object {
        val PALETTE = intArrayOf(
            0xFF64B5F6.toInt(), 0xFF81C784.toInt(), 0xFFFFB74D.toInt(),
            0xFFBA68C8.toInt(), 0xFFE57373.toInt(), 0xFF4DB6AC.toInt(), 0xFFF06292.toInt()
        )
        val NAMES = arrayOf("Blue", "Green", "Orange", "Purple", "Red", "Teal", "Pink")
    }

    init {
        val s = prefs.getString("tab_groups", null)
        if (s != null) try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += TabGroup(o.getString("id"), o.getString("n"), o.getInt("c"), o.optBoolean("x"))
            }
        } catch (e: Exception) { }
    }

    fun get(id: String): TabGroup? = if (id.isEmpty()) null else items.firstOrNull { it.id == id }

    fun nameTaken(name: String, except: TabGroup? = null): Boolean =
        items.any { it !== except && it.name.equals(name, true) }

    fun add(name: String): TabGroup {
        val g = TabGroup("g" + System.nanoTime().toString(36), name, PALETTE[items.size % PALETTE.size])
        items += g
        save()
        return g
    }

    fun remove(g: TabGroup) { items.remove(g); save() }

    /** Puts [g] at index [to] (the index the target group had before the move). */
    fun move(g: TabGroup, to: Int) {
        val from = items.indexOf(g)
        if (from < 0 || to < 0 || to >= items.size || from == to) return
        items.removeAt(from)
        items.add(to, g)
        save()
    }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("c", it.color).put("x", it.collapsed)) }
        prefs.edit().putString("tab_groups", arr.toString()).apply()
    }
}
