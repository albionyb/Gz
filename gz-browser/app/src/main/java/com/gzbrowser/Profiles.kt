package com.gzbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A browsing profile: its own cookies, logins and site data .
 * "Main" is not listed here: it is the default storage every existing login already lives in (id "").
 */
class BrowserProfile(val id: String, var name: String, var color: Int)

class BrowserProfileStore(private val prefs: SharedPreferences) {
    val items = mutableListOf<BrowserProfile>()

    /** "Main" can be renamed and recolored like the others; it keeps id "" so existing logins stay put. */
    var mainName: String = prefs.getString("main_name", null)?.takeIf { it.isNotBlank() } ?: "Main"
        private set
    var mainColor: Int = prefs.getInt("main_color", 0xFF607D8B.toInt())
        private set

    fun renameMain(n: String) { mainName = n; prefs.edit().putString("main_name", n).apply() }
    fun recolorMain(c: Int) { mainColor = c; prefs.edit().putInt("main_color", c).apply() }

    /** True when [name] is already used by Main or by another profile ([exceptId] is skipped, "" = Main). */
    fun nameTaken(name: String, exceptId: String? = null): Boolean =
        (exceptId != "" && mainName.equals(name, true)) ||
            items.any { it.id != exceptId && it.name.equals(name, true) }

    companion object {
        val PALETTE = intArrayOf(
            0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFFFFB74D.toInt(),
            0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(), 0xFFF06292.toInt()
        )
        val NAMES = arrayOf("Red", "Green", "Orange", "Purple", "Teal", "Pink")
    }

    init {
        val s = prefs.getString("profiles", null)
        if (s != null) try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += BrowserProfile(o.getString("id"), o.getString("n"), o.getInt("c"))
            }
        } catch (e: Exception) { }
    }

    fun get(id: String): BrowserProfile? = items.firstOrNull { it.id == id }

    fun nameOf(id: String): String = if (id.isEmpty()) mainName else get(id)?.name ?: "Profile"

    fun colorOf(id: String): Int = if (id.isEmpty()) mainColor else get(id)?.color ?: 0xFF607D8B.toInt()

    fun add(name: String): BrowserProfile {
        // letters and digits only: it becomes the engine's container id
        val p = BrowserProfile("p" + System.currentTimeMillis().toString(36), name, PALETTE[items.size % PALETTE.size])
        items += p
        save()
        return p
    }

    fun remove(p: BrowserProfile) { items.remove(p); save() }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("c", it.color)) }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }
}
