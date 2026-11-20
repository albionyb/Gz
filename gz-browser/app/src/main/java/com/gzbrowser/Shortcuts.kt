package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Shortcut(var name: String, var url: String, var profile: String = "")

/** Tiles on the home screen. */
class ShortcutStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<Shortcut>()

    init {
        val s = prefs.getString("shortcuts", null)
        if (s == null) defaults() else {
            try {
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items += Shortcut(o.getString("n"), o.getString("u"), o.optString("p", ""))
                }
            } catch (e: Exception) { defaults() }
        }
    }

    fun defaults() {
        items.clear()
        items += Shortcut("mbga.jp", "https://mbga.jp/")
        items += Shortcut("GBF Wiki", "https://gbf.wiki/")
        items += Shortcut("X", "https://x.com/")
    }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url).apply { if (it.profile.isNotEmpty()) put("p", it.profile) }) }
        prefs.edit().putString("shortcuts", arr.toString()).apply()
    }
}
