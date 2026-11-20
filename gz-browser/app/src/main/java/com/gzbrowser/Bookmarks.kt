package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A bubble. If [children] is non-null it is a folder (url unused). */
data class Bookmark(
    var label: String,
    var url: String,
    var color: Int,
    val children: MutableList<Bookmark>? = null,
    var icon: String = "",
    var profile: String = ""   // "" = open in the current tab's profile, "main" = Main, else a profile id
) {
    val isFolder: Boolean get() = children != null
}

class BookmarkStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<Bookmark>()

    init {
        val s = prefs.getString("bookmarks", null)
        if (s != null) items += parse(s) ?: emptyList()
    }

    fun parse(s: String): List<Bookmark>? = try {
        parseArray(JSONArray(s))
    } catch (e: Exception) { null }

    private fun parseArray(arr: JSONArray): List<Bookmark> = (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        val kids = if (o.has("children")) parseArray(o.getJSONArray("children")).toMutableList() else null
        Bookmark(o.getString("label"), o.optString("url", ""), o.getInt("color"), kids, o.optString("icon", ""), o.optString("p", ""))
    }

    fun toJson(): String = toArray(items).toString()

    private fun toArray(list: List<Bookmark>): JSONArray {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("label", it.label).put("url", it.url).put("color", it.color)
            if (it.icon.isNotEmpty()) o.put("icon", it.icon)
            if (it.profile.isNotEmpty()) o.put("p", it.profile)
            if (it.children != null) o.put("children", toArray(it.children))
            arr.put(o)
        }
        return arr
    }

    /** The list (bubble row or a folder's children) that contains [b]. */
    fun parentList(b: Bookmark): MutableList<Bookmark>? {
        if (items.any { it === b }) return items
        for (f in items) {
            val c = f.children ?: continue
            if (c.any { it === b }) return c
        }
        return null
    }

    fun folders(): List<Bookmark> = items.filter { it.isFolder }

    fun save() { prefs.edit().putString("bookmarks", toJson()).apply() }
}
