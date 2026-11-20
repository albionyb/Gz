package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(var title: String, val url: String, var time: Long)

class HistoryStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<HistoryItem>()
    private val max = 500

    init {
        try {
            val arr = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += HistoryItem(o.optString("t"), o.getString("u"), o.optLong("d"))
            }
        } catch (e: Exception) { items.clear() }
    }

    fun add(url: String) {
        if (url.isEmpty() || url.startsWith("about:")) return
        val first = items.firstOrNull()
        if (first != null && first.url == url) { first.time = System.currentTimeMillis() }
        else {
            items.add(0, HistoryItem("", url, System.currentTimeMillis()))
            while (items.size > max) items.removeAt(items.size - 1)
        }
        save()
    }

    fun setTitle(url: String, title: String) {
        val it = items.firstOrNull { x -> x.url == url } ?: return
        if (it.title != title) { it.title = title; save() }
    }

    fun clear() { items.clear(); save() }

    fun remove(item: HistoryItem) { items.remove(item); save() }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}
