package com.gzbrowser
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewOutlineProvider
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.gzbrowser.MainActivity.Companion.REQ_EXPORT
import com.gzbrowser.MainActivity.Companion.REQ_IMPORT
import com.gzbrowser.MainActivity.Companion.C_BG
import com.gzbrowser.MainActivity.Companion.C_SURFACE
import com.gzbrowser.MainActivity.Companion.C_BORDER
import com.gzbrowser.MainActivity.Companion.C_ACCENT
import com.gzbrowser.MainActivity.Companion.C_ICE
import com.gzbrowser.MainActivity.Companion.C_MUTED
import com.gzbrowser.MainActivity.Companion.C_TEXT
import com.gzbrowser.MainActivity.Companion.C_DIV

internal fun MainActivity.openSettings(page: String = "") {
    settingsPage = page
    val d = object : Dialog(this@openSettings, android.R.style.Theme_Material_NoActionBar) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            if (settingsPage.isNotEmpty()) goSettings("") else super.onBackPressed()
        }
    }
    settingsDialog = d
    d.setContentView(buildSettings(d))
    d.setOnDismissListener { settingsDialog = null; settingsPage = "" }
    d.show()
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}

internal fun MainActivity.refreshSettings() {
    val d = settingsDialog ?: return
    d.setContentView(buildSettings(d))
}

internal fun MainActivity.header(parent: LinearLayout, text: String) {
    parent.addView(TextView(this).apply {
        this.text = text.uppercase(); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.05f
        setTextColor(C_MUTED); setPadding(dp(22), dp(20), dp(16), dp(8))
    })
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER)
        }
    }
    parent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(dp(10), 0, dp(10), 0)
    })
    settingsGroup = card
}

internal fun MainActivity.groupFor(parent: LinearLayout): LinearLayout {
    val g = settingsGroup ?: return parent
    if (g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(C_DIV) }, LinearLayout.LayoutParams(-1, dp(1)))
    return g
}

internal fun MainActivity.rowBase(parent: LinearLayout, title: String, sub: String?, trailing: View?, click: (() -> Unit)?) {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(14), dp(12))
        if (click != null) setOnClickListener { click() }
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(C_TEXT) })
    if (!sub.isNullOrEmpty()) col.addView(TextView(this).apply {
        text = sub; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, 0)
    })
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (trailing != null) row.addView(trailing)
    else if (click != null) row.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_nav_fwd); setColorFilter(C_MUTED)
    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(dp(8), 0, 0, 0) })
    groupFor(parent).addView(row)
}

internal fun MainActivity.switchRow(
    parent: LinearLayout, title: String, sub: String?, key: String, def: Boolean,
    onChange: (Boolean) -> Unit = {}
) {
    val sw = Switch(this).apply {
        isChecked = prefs.getBoolean(key, def)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = android.content.res.ColorStateList(states, intArrayOf(C_ICE, C_MUTED))
        trackTintList = android.content.res.ColorStateList(states, intArrayOf(C_ACCENT, C_BORDER))
        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply(); onChange(c) }
    }
    rowBase(parent, title, sub, sw) { sw.toggle() }
}

internal fun MainActivity.actionRow(parent: LinearLayout, title: String, sub: String?, click: () -> Unit) =
    rowBase(parent, title, sub, null, click)

internal fun MainActivity.sliderRow(parent: LinearLayout, title: String, key: String, min: Int, max: Int, def: Int, onChange: () -> Unit) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(10)) }
    val label = TextView(this).apply { textSize = 15f; setTextColor(C_TEXT) }
    val seek = SeekBar(this).apply {
        this.max = max - min; progress = prefs.getInt(key, def) - min
        progressTintList = android.content.res.ColorStateList.valueOf(C_ACCENT)
        thumbTintList = android.content.res.ColorStateList.valueOf(C_ICE)
    }
    label.text = "$title: ${seek.progress + min}%"
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { label.text = "$title: ${p + min}%" }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {
            prefs.edit().putInt(key, s.progress + min).apply(); onChange()
        }
    })
    box.addView(label); box.addView(seek)
    groupFor(parent).addView(box)
}

internal fun MainActivity.goSettings(page: String) { settingsPage = page; refreshSettings() }

internal fun MainActivity.settingsTitle(): String = when (settingsPage) {
    "browsing" -> "Browsing"
    "profiles" -> "Profiles"
    "privacy" -> "Privacy & safety"
    "appearance" -> "Appearance"
    "screen" -> "Screen & bars"
    "buttons" -> "Bubbles & floating buttons"
    "gestures" -> "Gestures"
    "backup" -> "Bookmarks & backup"
    "storage" -> "Downloads & storage"
    else -> "Settings"
}

internal fun MainActivity.buildSettings(d: Dialog): View {
    settingsGroup = null
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG) }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(16), dp(4))
    }
    head.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_nav_back)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { if (settingsPage.isEmpty()) d.dismiss() else goSettings("") }
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    head.addView(TextView(this).apply {
        text = settingsTitle(); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C_TEXT)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(head)
    root.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(28)) }
    root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

    when (settingsPage) {
        "browsing" -> pageBrowsing(list)
        "profiles" -> pageProfiles(list)
        "privacy" -> pagePrivacy(list)
        "appearance" -> pageAppearance(list)
        "screen" -> pageScreen(list)
        "buttons" -> pageButtons(list)
        "gestures" -> pageGestures(list)
        "backup" -> pageBackup(list)
        "storage" -> pageStorage(list)
        else -> settingsHub(list)
    }
    list.addView(View(this), LinearLayout.LayoutParams(-1, dp(40)))
    return root
}

/** Top level: a short list of topics, each opening its own page. */
internal fun MainActivity.settingsHub(list: LinearLayout) {
    header(list, "Browser")
    actionRow(list, "Browsing", "Home page, tabs, zoom, page width") { goSettings("browsing") }
    actionRow(
        list, "Profiles",
        when {
            !profilesSupported() -> "Needs a newer Android System WebView"
            profiles.items.isEmpty() -> "Separate logins, e.g. a second account"
            else -> "${profiles.mainName} + ${profiles.items.joinToString { it.name }}"
        }
    ) { goSettings("profiles") }
    actionRow(list, "Privacy & safety", "Ad blocking ${if (prefs.getBoolean("block_on", true)) "on" else "off"}, exit guard") { goSettings("privacy") }

    header(list, "Look & feel")
    actionRow(list, "Appearance", "${Theme.presets[presetIndex()].name} theme, accent color") { goSettings("appearance") }
    actionRow(list, "Screen & bars", "Address bar, full screen, orientation") { goSettings("screen") }
    actionRow(list, "Bubbles & floating buttons", "Bubble row, ${floats.items.size} floating button(s)") { goSettings("buttons") }

    header(list, "Controls")
    actionRow(list, "Gestures", if (gesturesOn()) "On" else "Off") { goSettings("gestures") }

    header(list, "Your data")
    actionRow(list, "Bookmarks & backup", "Export or import a file") { goSettings("backup") }
    actionRow(list, "Downloads & storage", "${downloads.items.size} download(s), clear cache and data") { goSettings("storage") }
}

internal fun MainActivity.pageBrowsing(list: LinearLayout) {
    header(list, "Start")
    val homeLabel = when (prefs.getInt("home_mode", 0)) {
        1 -> "Last visited page"
        2 -> "Custom: ${prefs.getString("home_url", "")}"
        3 -> "Game (Granblue Fantasy)"
        else -> "Home screen"
    }
    actionRow(list, "Home page", homeLabel) { chooseHome() }
    actionRow(list, "Reset home shortcuts", "Back to mbga.jp, GBF Wiki and X") {
        confirm("Reset home shortcuts to the defaults?", "Reset") { shortcuts.defaults(); shortcuts.save(); refreshHome(); toast("Shortcuts reset") }
    }
    switchRow(list, "Restore tabs on start", "Reopens your tabs after the app is closed. Pages load when you open them", "restore_tabs", true)

    header(list, "Pages")
    switchRow(list, "Keep background tabs running", "Off pauses tabs you aren't looking at", "bg_run", true)
    switchRow(list, "Text mode (no images)", "Reloads the current page", "text_mode", false) {
        applyWebPrefsAll(); current?.takeIf { !it.isHome }?.web?.reload()
    }
    switchRow(list, "Allow pinch zoom", "Only on pages that allow zooming", "zoom", true) { applyWebPrefsAll() }
    actionRow(list, "Page width", "${prefs.getInt("width_pct", 100)}%") { widthDialog() }
}

internal fun MainActivity.pageProfiles(list: LinearLayout) {
    header(list, "Profiles")
    if (!profilesSupported()) {
        rowBase(list, "Not available", "Profiles need a newer Android System WebView. Update it in the Play Store, then restart the app", null, null)
        return
    }
    rowBase(list, profiles.mainName, "Default. Holds the logins you already have", accentDot(profiles.mainColor)) { mainOptions() }
    profiles.items.forEach { p ->
        val n = tabs.count { it.profile == p.id }
        rowBase(list, p.name, if (n == 0) "No tabs open" else "$n tab(s) open", accentDot(p.color)) { profileOptions(p) }
    }
    actionRow(list, "Add profile", "A separate set of logins and site data") { addProfile() }
    list.addView(TextView(this).apply {
        text = "Long-press + in the tab list to open a tab in a profile. Bookmarks and home shortcuts can be tied to a profile when you edit them."
        textSize = 12f; setTextColor(C_MUTED); setPadding(dp(22), dp(12), dp(22), 0)
    })
}

internal fun MainActivity.pagePrivacy(list: LinearLayout) {
    header(list, "Ads & trackers")
    switchRow(list, "Block ads & trackers", "Built-in blocklist. Never active on the game's own sites. Switch off per site from the menu", "block_on", true) {
        current?.takeIf { !it.isHome }?.web?.reload()
    }
    header(list, "Safety")
    switchRow(list, "Confirm before exit", "Press back twice to exit, and ask first when exiting from a gesture, button or bubble. Stops accidental exits mid-battle", "back_guard", true)
}

internal fun MainActivity.pageAppearance(list: LinearLayout) {
    header(list, "Theme")
    val p = Theme.presets[presetIndex()]
    rowBase(list, "Color theme", p.name, accentDot(p.surface)) { themeDialog() }
    val custom = prefs.getInt("theme_accent", 0)
    val accName = if (custom == 0) "Theme default" else
        (Theme.swatches.firstOrNull { it.color == custom }?.name ?: String.format("#%06X", custom and 0xFFFFFF))
    rowBase(list, "Accent color", accName, accentDot(C_ACCENT)) { accentDialog() }
}

internal fun MainActivity.pageScreen(list: LinearLayout) {
    header(list, "Address bar")
    switchRow(list, "Auto-hide address bar", "Tap the thin line at the top to bring it back", "auto_hide", true) {
        barShown = true; updateChrome(); scheduleHide(); refreshSettings()
    }
    if (autoHide()) actionRow(list, "Hide after", "${prefs.getInt("hide_delay", 3)} seconds") {
        val opts = intArrayOf(2, 3, 5, 10)
        AlertDialog.Builder(this).setItems(opts.map { "$it seconds" }.toTypedArray()) { _, i ->
            prefs.edit().putInt("hide_delay", opts[i]).apply(); scheduleHide(); refreshSettings()
        }.showT()
    }
    header(list, "Full screen")
    switchRow(list, "Full screen", "Hides the status and navigation bars", "fullscreen", false) {
        setFullscreen(it); refreshSettings()
    }
    switchRow(list, "Hide all bars in full screen", "Bottom bar and bubbles too; the edge drawer handle stays", "fs_hide_all", false) {
        updateChrome()
    }
    actionRow(list, "Edge drawer handle", drawerModeLabel()) { chooseDrawerMode() }
    header(list, "Screen")
    val orient = when (prefs.getInt("orient", 0)) { 1 -> "Portrait"; 2 -> "Landscape"; else -> "Auto" }
    actionRow(list, "Orientation", orient) {
        AlertDialog.Builder(this).setItems(arrayOf("Auto", "Portrait", "Landscape")) { _, i ->
            prefs.edit().putInt("orient", i).apply(); applyOrientation(); refreshSettings()
        }.showT()
    }
    switchRow(list, "Keep screen on", null, "keep_awake", true) { applyKeepAwake() }
    header(list, "Screenshots")
    switchRow(list, "Preview after screenshot", "A small thumbnail for 3 seconds", "shot_preview", true) { }
}

internal fun MainActivity.pageButtons(list: LinearLayout) {
    header(list, "Bubble row")
    switchRow(list, "Show bubble row", null, "bubble_show", true) { updateChrome() }
    sliderRow(list, "Bubble opacity", "bubble_alpha", 30, 100, 100) { updateChrome() }
    sliderRow(list, "Bubble height", "bubble_h", 60, 150, 100) { updateChrome(); refreshBubbles() }
    header(list, "Floating buttons")
    switchRow(list, "Show floating buttons", "Drag them anywhere. Lock them so they stay put", "float_show", true) { refreshFloats() }
    sliderRow(list, "All floating buttons: size", "float_scale", 60, 160, 100) { refreshFloats() }
    sliderRow(list, "All floating buttons: opacity", "float_alpha", 0, 100, 100) { refreshFloats() }
    actionRow(list, "Manage buttons", "${floats.items.size} button(s). Long-press a button on screen to edit it") { floatManager() }
}

internal fun MainActivity.pageGestures(list: LinearLayout) {
    header(list, "Gestures")
    switchRow(list, "Gestures", "Off by default. Edge, address bar, bottom bar and multi-finger swipes", "gestures_on", false) {
        applyGestureExclusion(); ensureDrawerReachable()
    }
    val gh = hostOf(current?.url.orEmpty())
    actionRow(
        list, "On this site",
        if (gh.isEmpty()) "Open a website first"
        else "$gh: gestures are ${if (gestureOffHosts().contains(gh)) "OFF (tap to turn on)" else "on (tap to turn off)"}"
    ) { toggleSiteGestures() }
    header(list, "Gesture area")
    groupFor(list).addView(GestureAreaView(this), LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    actionRow(list, "Edge swipe width", "${prefs.getInt("gest_edge_dp", 20)} dp") { chooseEdgeWidth() }
    rowBase(list, "Split height", "${prefs.getInt("gest_split", 50)}%. Drag the dashed line in the preview", null, null)
    var lastGroup = ""
    GestureMap.GESTURES.forEach { g ->
        val grp = when {
            g.key.startsWith("edge_") -> "Edge swipes"
            g.key.startsWith("bar_") -> "Address bar"
            g.key.startsWith("dock_") -> "Bottom bar"
            g.key.startsWith("two_") -> "Two fingers"
            else -> "Three fingers"
        }
        if (grp != lastGroup) {
            header(list, grp); lastGroup = grp
            val kind = when (grp) { "Address bar" -> "bar"; "Bottom bar" -> "dock"; "Two fingers" -> "two"; "Three fingers" -> "three"; else -> "" }
            if (kind.isNotEmpty()) groupFor(list).addView(GestureGroupView(this, kind), LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        actionRow(list, g.label, GestureMap.label(gestureAction(g.key))) { chooseGestureAction(g) }
    }
    header(list, "Reset")
    actionRow(list, "Reset gestures", "Back to the starting mapping") { resetGestures() }
}

internal fun MainActivity.pageBackup(list: LinearLayout) {
    header(list, "Bookmarks")
    actionRow(list, "Export to file", "Saves a .json backup anywhere you choose") {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "gz-bookmarks.json")
        }
        startActivityForResult(i, REQ_EXPORT)
    }
    actionRow(list, "Import from file", "Replaces your current bookmarks") {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, REQ_IMPORT)
    }
}

internal fun MainActivity.pageStorage(list: LinearLayout) {
    header(list, "Downloads")
    actionRow(list, "Downloads", "${downloads.items.size} file(s) in the list") { showDownloads() }
    header(list, "Clear data")
    actionRow(list, "Clear cache", "Keeps logins and game data") {
        clearCache()
    }
    val ch = hostOf(current?.url.orEmpty())
    val cp = current?.profile.orEmpty()
    actionRow(
        list, "Clear this site's data",
        if (ch.isEmpty()) "Open a website first" else if (cp.isEmpty()) ch else "$ch (${profiles.nameOf(cp)})"
    ) {
        if (ch.isEmpty()) toast("Open a website first") else confirm("Clear all data for $ch? You will be logged out of it.") {
            clearSite(ch, cp)
        }
    }
    actionRow(list, "Clear everything", "Cookies, logins, cache and site data for all sites and profiles") {
        confirm("Clear ALL browsing data? You will be logged out everywhere.") {
            clearAll()
        }
    }
}

internal fun MainActivity.confirm(msg: String, label: String = "Clear", yes: () -> Unit) {
    AlertDialog.Builder(this).setMessage(msg)
        .setPositiveButton(label) { _, _ -> yes() }
        .setNegativeButton("Cancel", null).showT()
}

internal fun MainActivity.clearCache() {
    (current ?: tabs.firstOrNull())?.web?.clearCache(true)
    toast("Cleared")
}

internal fun MainActivity.clearSite(host: String, profile: String = "") {
    val cm = cookiesOf(profile) ?: run { toast("Profile not available"); return }
    var h = host
    while (h.contains('.')) {
        val raw = cm.getCookie("https://$h")
        if (raw != null) {
            raw.split(";").map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }.forEach { n ->
                cm.setCookie("https://$h", "$n=; Max-Age=0; Path=/")
                cm.setCookie("https://$h", "$n=; Max-Age=0; Domain=$h; Path=/")
                cm.setCookie("https://$h", "$n=; Max-Age=0; Domain=.$h; Path=/")
            }
        }
        h = h.substringAfter('.')
    }
    cm.flush()
    (if (profile.isEmpty()) WebStorage.getInstance() else webProfile(profile)?.webStorage)?.deleteOrigin("https://$host")
    toast("Cleared")
}

internal fun MainActivity.clearAll() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    profiles.items.forEach { p ->
        try {
            cookiesOf(p.id)?.let { it.removeAllCookies(null); it.flush() }
            webProfile(p.id)?.webStorage?.deleteAllData()
        } catch (e: Throwable) { }
    }
    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
    tabs.forEach { it.web.clearCache(true) }
    toast("Cleared")
}

internal fun MainActivity.chooseHome() {
    val names = arrayOf("Home screen", "Last visited page", "Custom URL…", "Game (Granblue Fantasy)")
    AlertDialog.Builder(this).setTitle("Home page")
        .setSingleChoiceItems(names, prefs.getInt("home_mode", 0)) { dlg, which ->
            dlg.dismiss()
            if (which == 2) {
                val et = EditText(this).apply {
                    hint = "https://…"; setSingleLine(); setText(prefs.getString("home_url", ""))
                }
                AlertDialog.Builder(this).setTitle("Custom home page").setView(et)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ ->
                        var u = et.text.toString().trim()
                        if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
                        prefs.edit().putInt("home_mode", if (u.isEmpty()) 0 else 2).putString("home_url", u).apply()
                        refreshSettings()
                    }.showT()
            } else {
                prefs.edit().putInt("home_mode", which).apply(); refreshSettings()
            }
        }.showT()
}

internal fun MainActivity.widthDialog() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), 0) }
    val seek = SeekBar(this).apply { max = 70; progress = prefs.getInt("width_pct", 100) - 30 }
    val label = TextView(this).apply { setTextColor(Color.WHITE); text = "Width: ${seek.progress + 30}%" }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { label.text = "Width: ${p + 30}%" }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    })
    val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
    listOf("Left", "Center", "Right").forEachIndexed { i, n ->
        group.addView(RadioButton(this).apply { id = 100 + i; text = n; setTextColor(Color.WHITE) })
    }
    group.check(100 + prefs.getInt("align", 1))
    box.addView(label); box.addView(seek); box.addView(group)
    AlertDialog.Builder(this).setTitle("Page width").setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Apply & reload") { _, _ ->
            prefs.edit().putInt("width_pct", seek.progress + 30)
                .putInt("align", group.checkedRadioButtonId - 100).apply()
            applyWidth(); current?.web?.reload(); refreshSettings()
        }.showT()
}

internal fun MainActivity.importBookmarks(s: String) {
    val list = store.parse(s)
    if (list == null) toast("That isn't an exported bookmarks file")
    else {
        store.items.clear(); store.items.addAll(list); store.save(); refreshBubbles()
        toast("Imported ${list.size} bookmarks")
    }
}
