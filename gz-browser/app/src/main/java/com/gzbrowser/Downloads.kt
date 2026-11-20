package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One entry in the download list. */
class DlItem(
    val id: Long,
    var name: String,
    val url: String,
    val referer: String,
    var mime: String,
    var size: Long,          // -1 = unknown
    var time: Long,
    var state: Int,          // STATE_*
    var uri: String,         // content:// (Android 10+) or file:// of the saved file
    var where: String,       // human readable folder
    var note: String         // error message
) {
    companion object {
        const val RUNNING = 0
        const val DONE = 1
        const val FAILED = 2
        const val CANCELED = 3
    }

    var profile: String = ""   // profile the download came from (its cookies are used)
    @Volatile var done: Long = 0
    @Volatile var cancel = false
}

class DownloadStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<DlItem>()      // newest first
    private val max = 200

    init {
        try {
            val arr = JSONArray(prefs.getString("downloads", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var st = o.optInt("s", DlItem.DONE)
                var note = o.optString("n")
                if (st == DlItem.RUNNING) { st = DlItem.FAILED; note = "Interrupted" }   // app was closed mid-download
                items += DlItem(
                    o.optLong("id"), o.optString("f", "file"), o.optString("u"), o.optString("r"),
                    o.optString("m"), o.optLong("z", -1), o.optLong("d"), st,
                    o.optString("p"), o.optString("w"), note
                ).also { it.profile = o.optString("pf"); if (st == DlItem.DONE) it.done = it.size.coerceAtLeast(0) }
            }
        } catch (e: Exception) { items.clear() }
    }

    fun add(item: DlItem) {
        items.add(0, item)
        while (items.size > max) items.removeAt(items.size - 1)
        save()
    }

    fun remove(item: DlItem) { items.remove(item); save() }

    /** Removes everything that is not downloading right now. */
    fun clearFinished() { items.removeAll { it.state != DlItem.RUNNING }; save() }

    fun anyRunning() = items.any { it.state == DlItem.RUNNING }

    fun save() {
        val arr = JSONArray()
        synchronized(items) {
            items.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("f", it.name).put("u", it.url).put("r", it.referer)
                        .put("m", it.mime).put("z", it.size).put("d", it.time).put("s", it.state)
                        .put("p", it.uri).put("w", it.where).put("n", it.note).put("pf", it.profile)
                )
            }
        }
        prefs.edit().putString("downloads", arr.toString()).apply()
    }
}

/** Where a file ended up after saving. */
class Saved(val where: String, val uri: String)
