package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A note. When [toggle] is true it shows a red X / green check box ([done]).
 * When [toggle] is false it is a plain note with no box.
 * A battle counter is [total] > 0: the box shows [left], each tap counts down, and at 0 the note is done.
 */
data class Note(
    val id: Long, var text: String, var toggle: Boolean, var done: Boolean,
    var total: Int = 0, var left: Int = 0
)

class NoteStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<Note>()

    init {
        val s = prefs.getString("notes", null)
        if (s != null) try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += Note(o.getLong("id"), o.getString("t"), o.optBoolean("g", true), o.optBoolean("d", false),
                    o.optInt("ct", 0), o.optInt("c", 0))
            }
        } catch (e: Exception) { items.clear() }
    }

    fun add(text: String, count: Int = 0) {
        items += Note(System.nanoTime(), text, toggle = true, done = false, total = count, left = count)
        save()
    }

    fun clear() { items.clear(); save() }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.text).put("g", it.toggle).put("d", it.done).put("ct", it.total).put("c", it.left)) }
        prefs.edit().putString("notes", arr.toString()).apply()
    }
}

/**
 * One tracked drop. [log] has one char per fight: '1' = it dropped, '0' = no drop.
 * Everything shown (fights, drops, since last drop, average) is derived from it, so Undo is just dropLast(1).
 */
class DropItem(val id: Long, var name: String, var log: String = "") {
    val fights: Int get() = log.length
    val drops: Int get() = log.count { it == '1' }
    val since: Int get() = if (drops == 0) log.length else log.length - 1 - log.lastIndexOf('1')
    val average: Double get() = if (drops == 0) 0.0 else fights.toDouble() / drops
}

class DropStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<DropItem>()

    init {
        val s = prefs.getString("drop_items", null)
        if (s != null) try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += DropItem(o.getLong("id"), o.getString("n"), o.optString("l", ""))
            }
        } catch (e: Exception) { items.clear() }
    }

    fun add(name: String) { items += DropItem(System.nanoTime(), name); save() }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("l", it.log)) }
        prefs.edit().putString("drop_items", arr.toString()).apply()
    }
}
