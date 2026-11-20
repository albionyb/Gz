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
import com.gzbrowser.MainActivity.Companion.C_TILE
import com.gzbrowser.MainActivity.Companion.C_EDGE
import com.gzbrowser.MainActivity.Companion.COLORS

internal fun MainActivity.floatsOn() = prefs.getBoolean("float_show", true)

internal fun MainActivity.floatActionName(a: String) = when (a) {
    "back" -> "Back"
    "forward" -> "Forward"
    "reload" -> "Reload"
    "home" -> "Home"
    "tabs" -> "Tabs"
    "menu" -> "Menu"
    "shot" -> "Screenshot"
    "full" -> "Full screen on/off"
    "bubbles" -> "Show/hide bubble row"
    "bookmark" -> "Bookmark / link"
    "downloads" -> "Downloads"
    "notes" -> "Notes"
    "drops" -> "Drop tracker"
    "spark" -> "Spark calculator"
    "find" -> "Find in page"
    "exit" -> "Exit app"
    else -> a
}

internal val floatActionKeys = arrayOf("back", "forward", "reload", "home", "tabs", "menu", "shot", "full", "bubbles", "downloads", "notes", "drops", "spark", "find", "exit", "bookmark")

internal fun MainActivity.runFloat(b: FloatBtn) {
    when (b.action) {
        "back" -> goBack()
        "forward" -> current?.let { if (!it.isHome) it.web.goForward() }
        "reload" -> current?.let { if (it.isHome) refreshHome() else it.web.reload() }
        "home" -> goHome()
        "tabs" -> showTabs()
        "menu" -> showMenu()
        "shot" -> showScreenshotMenu()
        "full" -> setFullscreen(!fullscreen())
        "bubbles" -> {
            prefs.edit().putBoolean("bubble_show", !prefs.getBoolean("bubble_show", true)).apply()
            updateChrome()
        }
        "downloads" -> showDownloads()
        "notes" -> togglePanel("notes") { showNotes() }
        "drops" -> togglePanel("drops") { showDrops() }
        "spark" -> showSpark()
        "find" -> showFind()
        "exit" -> if (prefs.getBoolean("back_guard", true)) confirmExit() else finishAndRemoveTask()
        "bookmark" -> if (b.url.isNotBlank()) load(b.url)
    }
}

internal fun MainActivity.refreshFloats() {
    if (!floatLayerReady()) return
    floatLayer.removeAllViews()
    floatViews.clear()
    if (!floatsOn()) return
    floats.items.forEach { b ->
        val v = buildFloatView(b)
        floatViews[b.id] = v
        floatLayer.addView(v)
    }
    positionFloats()
}

internal fun MainActivity.positionFloats() {
    val w = floatLayer.width
    val h = floatLayer.height
    if (w <= 0 || h <= 0) return
    floats.items.forEach { b ->
        val v = floatViews[b.id] ?: return@forEach
        val sz = dp(floatSz(b))
        v.x = b.fx.coerceIn(0f, 1f) * (w - sz).coerceAtLeast(0)
        v.y = b.fy.coerceIn(0f, 1f) * (h - sz).coerceAtLeast(0)
    }
}

internal fun MainActivity.floatSz(b: FloatBtn) = (b.size * prefs.getInt("float_scale", 100) / 100).coerceIn(20, 160)

internal fun MainActivity.buildFloatView(b: FloatBtn): View {
    val s = floatSz(b)
    val edge = if (b.locked) C_EDGE else Color.WHITE   // white outline = unlocked / movable
    fun textTile(txt: String, fill: Int, tag: String? = null) = TextView(this).apply {
        text = txt
        this.tag = tag
        setTextColor(Color.WHITE)
        textSize = s * 0.3f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(s) * 0.24f
            setColor(fill)
            setStroke(if (b.locked) dp(1) else dp(2), edge)
        }
    }
    val v: View = when (b.action) {
        "tabs" -> textTile(tabs.size.toString(), C_TILE, "tabcount")
        "notes" -> textTile("✎", C_TILE)
        "exit" -> textTile("✕", C_TILE)
        "bookmark" -> {
            val res = if (b.icon.isNotEmpty()) BmIcons.res(b.icon) else null
            if (res != null) iconTile(res, s, selected = !b.locked) else textTile(b.label.take(3), b.color)
        }
        else -> iconTile(
            when (b.action) {
                "back" -> R.drawable.ic_nav_back
                "forward" -> R.drawable.ic_nav_fwd
                "reload" -> R.drawable.ic_nav_reload
                "home" -> R.drawable.ic_bm_home
                "menu" -> R.drawable.ic_nav_menu
                "shot" -> BmIcons.screenshot
                "full" -> R.drawable.ic_m_full
                "downloads" -> R.drawable.ic_m_download
                "drops" -> R.drawable.ic_m_drop
                "spark" -> R.drawable.ic_m_spark
                "find" -> R.drawable.ic_m_find
                else -> R.drawable.ic_bm_stash
            }, s, selected = !b.locked
        )
    }
    v.alpha = if (floatReveal) 1f else (b.alpha.coerceIn(0, 100) / 100f) * (prefs.getInt("float_alpha", 100) / 100f)
    v.layoutParams = FrameLayout.LayoutParams(dp(s), dp(s))
    attachFloatTouch(v, b)
    return v
}

internal fun MainActivity.attachFloatTouch(v: View, b: FloatBtn) {
    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
    var dragging = false; var moved = false; var longDone = false
    val longPress = Runnable {
        longDone = true
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        floatEditMenu(b)
    }
    v.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = view.x; startY = view.y
                dragging = false; moved = false; longDone = false
                handler.postDelayed(longPress, 650)
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    moved = true
                    handler.removeCallbacks(longPress)
                    if (!b.locked) dragging = true
                }
                if (dragging) {
                    val maxX = (floatLayer.width - view.width).coerceAtLeast(0).toFloat()
                    val maxY = (floatLayer.height - view.height).coerceAtLeast(0).toFloat()
                    view.x = (startX + dx).coerceIn(0f, maxX)
                    view.y = (startY + dy).coerceIn(0f, maxY)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    val maxX = (floatLayer.width - view.width).coerceAtLeast(1).toFloat()
                    val maxY = (floatLayer.height - view.height).coerceAtLeast(1).toFloat()
                    b.fx = (view.x / maxX).coerceIn(0f, 1f)
                    b.fy = (view.y / maxY).coerceIn(0f, 1f)
                    floats.save()
                } else if (!moved && !longDone) {
                    runFloat(b)
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        true
    }
}

internal fun MainActivity.floatManager() {
    val labels = mutableListOf("+ Add button", "Lock all", "Unlock all")
    floats.items.forEach {
        val name = if (it.action == "bookmark") it.label.ifBlank { "Link" } else floatActionName(it.action)
        labels += (if (it.locked) "🔒 " else "↔ ") + name
    }
    AlertDialog.Builder(this).setTitle("Floating buttons")
        .setItems(labels.toTypedArray()) { _, i ->
            when (i) {
                0 -> addFloat()
                1 -> { floats.items.forEach { it.locked = true }; floats.save(); refreshFloats(); toast("All locked") }
                2 -> { floats.items.forEach { it.locked = false }; floats.save(); refreshFloats(); toast("All unlocked: drag to move") }
                else -> floatEditMenu(floats.items[i - 3])
            }
        }.showT()
}

/** Current page plus every bookmark bubble (folder children included), for "open which link" pickers. */
internal fun MainActivity.linkCandidates(): List<Bookmark> {
    val cands = mutableListOf<Bookmark>()
    visibleUrl()?.let { cands += Bookmark("Current page", it, COLORS[0]) }
    for (bm in store.items) {
        if (bm.isFolder) bm.children?.forEach { c -> if (!c.isFolder) cands.add(c) }
        else cands.add(bm)
    }
    return cands
}

/** Picks an action; for "bookmark" also picks which link. */
internal fun MainActivity.pickFloatAction(done: (action: String, label: String, url: String, color: Int, icon: String) -> Unit) {
    AlertDialog.Builder(this).setTitle("Button action")
        .setItems(floatActionKeys.map { floatActionName(it) }.toTypedArray()) { _, i ->
            val a = floatActionKeys[i]
            if (a != "bookmark") { done(a, "", "", COLORS[0], ""); return@setItems }
            val cands = linkCandidates()
            if (cands.isEmpty()) { toast("Add a bookmark bubble first (or open a page)"); return@setItems }
            AlertDialog.Builder(this).setTitle("Open which link?")
                .setItems(cands.map { it.label }.toTypedArray()) { _, j ->
                    val c = cands[j]
                    done("bookmark", c.label, c.url, c.color, c.icon)
                }.showT()
        }.showT()
}

internal fun MainActivity.addFloat() {
    pickFloatAction { a, label, url, color, icon ->
        if ((a == "notes" || a == "drops") && floats.items.any { it.action == a }) {
            toast("That button already exists. Long-press it to edit or delete"); return@pickFloatAction
        }
        val n = floats.items.size
        floats.items += FloatBtn(
            System.currentTimeMillis(), a, label, url, color, icon,
            0.93f, (0.25f + 0.1f * (n % 6)).coerceAtMost(0.9f), 48, 90, false
        )
        floats.save(); refreshFloats()
        toast("Drag it into place, then long-press it and choose Lock")
    }
}

internal fun MainActivity.floatEditMenu(b: FloatBtn) {
    val labels = arrayOf(
        if (b.locked) "Unlock (allow moving)" else "Lock in place",
        "Size: ${b.size}", "Opacity: ${b.alpha}%", "Change action", "Delete"
    )
    AlertDialog.Builder(this).setItems(labels) { _, i ->
        when (i) {
            0 -> { b.locked = !b.locked; floats.save(); refreshFloats(); toast(if (b.locked) "Locked" else "Unlocked: drag to move") }
            1 -> seekDialog("Button size", 32, 90, b.size) { b.size = it; floats.save(); refreshFloats() }
            2 -> seekDialog("Opacity", 0, 100, b.alpha) { b.alpha = it; floats.save(); refreshFloats() }
            3 -> pickFloatAction { a, label, url, color, icon ->
                if ((a == "notes" || a == "drops") && floats.items.any { it.action == a && it !== b }) {
                    toast("That button already exists"); return@pickFloatAction
                }
                b.action = a; b.label = label; b.url = url; b.color = color; b.icon = icon
                floats.save(); refreshFloats()
            }
            4 -> { floats.items.remove(b); floats.save(); refreshFloats() }
        }
    }.showT()
}

internal fun MainActivity.seekDialog(title: String, min: Int, max: Int, cur: Int, onSet: (Int) -> Unit) {
    val label = TextView(this).apply { textSize = 16f; setPadding(dp(20), dp(16), dp(20), 0) }
    val seek = SeekBar(this).apply {
        this.max = max - min; progress = cur - min
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    label.text = "$title: $cur"
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { label.text = "$title: ${p + min}" }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    })
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(seek) }
    AlertDialog.Builder(this).setView(box)
        .setPositiveButton("OK") { _, _ -> onSet(seek.progress + min) }
        .setNegativeButton("Cancel", null).showT()
}
