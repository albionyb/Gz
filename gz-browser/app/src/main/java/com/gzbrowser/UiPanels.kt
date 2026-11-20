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
import com.gzbrowser.MainActivity.Companion.C_BG
import com.gzbrowser.MainActivity.Companion.C_SURFACE
import com.gzbrowser.MainActivity.Companion.C_BORDER
import com.gzbrowser.MainActivity.Companion.C_ACCENT
import com.gzbrowser.MainActivity.Companion.C_MUTED
import com.gzbrowser.MainActivity.Companion.C_TEXT

internal val C_GREEN = 0xFF3FD08A.toInt()
internal val C_RED = 0xFFF0505F.toInt()
internal val C_GOLD = 0xFFF5C038.toInt()

internal fun MainActivity.tint(c: Int): Int = (c and 0x00FFFFFF) or 0x33000000

internal fun MainActivity.panelStay(key: String) = prefs.getBoolean("panel_${key}_stay", true)

internal fun MainActivity.togglePanel(key: String, open: () -> Unit) {
    val h = openPanels[key]
    if (h != null) h.dismiss() else open()
}

/**
 * Notes and Drop tracker are one floating button each, added from "+ Add button".
 * Older versions only hid the Notes button instead of deleting it and allowed duplicates: clean that up.
 */
internal fun MainActivity.cleanupFloats() {
    prefs.edit().remove("notes_float").apply()
    val seen = HashSet<String>()
    floats.items.removeAll { (it.action == "notes" || it.action == "drops") && !seen.add(it.action) }
    floats.save()
}

/**
 * Shows [content] as a panel. Default: an overlay above the page that stays open when you tap outside
 * (drag by its title bar, gear = size / opacity / lock). If "stay open" is off it is a normal popup dialog.
 * [content] is a vertical layout whose scrolling part takes the free height when it is in the overlay.
 */
internal fun MainActivity.uiPanel(key: String, title: String, content: View, reopen: () -> Unit): PanelHandle {
    openPanels[key]?.dismiss()

    if (!panelStay(key)) {
        var d: AlertDialog? = null
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(uiTitle(title) { d?.dismiss() },
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(14), dp(16), 0) })
        wrap.addView(content, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(TextView(this).apply {
            text = "Keep this open when tapping outside"; textSize = 13f; setTextColor(C_ACCENT); gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setOnClickListener {
                prefs.edit().putBoolean("panel_${key}_stay", true).apply()
                d?.dismiss(); reopen()
            }
        })
        val dd = uiDialog(wrap)
        d = dd
        val h = PanelHandle { dd.dismiss() }
        openPanels[key] = h
        dd.setOnDismissListener { if (openPanels[key] === h) openPanels.remove(key) }
        return h
    }

    val pw = CapLinear(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true   // the panel itself must not let touches through to the page
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER) }
        alpha = prefs.getInt("panel_${key}_alpha", 100).coerceIn(30, 100) / 100f
    }

    fun place() {
        val cw = panelLayer.width; val ch = panelLayer.height
        if (cw <= 0 || ch <= 0) return
        val scale = prefs.getInt("panel_${key}_scale", 100)
        val w = (minOf(dp(360), cw - dp(12)) * scale / 100).coerceIn(minOf(dp(220), cw), cw)
        val h = (maxOf((ch * 0.6f).toInt(), minOf(dp(280), ch)) * scale / 100).coerceIn(minOf(dp(180), ch), ch)
        // The panel is only as tall as its content; h is the most it may grow to.
        pw.maxH = h
        val lp = pw.layoutParams
        if (lp.width != w || lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = w; lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; pw.layoutParams = lp
        } else pw.requestLayout()
        val ph = if (pw.height > 0) pw.height else h
        pw.x = prefs.getFloat("panel_${key}_x", 0.5f).coerceIn(0f, 1f) * (cw - w).coerceAtLeast(0)
        pw.y = prefs.getFloat("panel_${key}_y", 0.25f).coerceIn(0f, 1f) * (ch - ph).coerceAtLeast(0)
    }

    val handle = PanelHandle {
        uiHideKeyboard(pw)
        panelLayer.removeView(pw)
        panelRelayout.remove(key)
        openPanels.remove(key)
    }

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(4), dp(4), dp(4))
    }
    header.addView(TextView(this).apply {
        text = "≡  $title"; textSize = 17f; setTextColor(C_TEXT); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(8))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val gear = TextView(this).apply {
        text = "⚙"; textSize = 19f; setTextColor(C_MUTED); gravity = Gravity.CENTER
        setOnClickListener {
            val lock = prefs.getBoolean("panel_${key}_lock", false)
            val scale = prefs.getInt("panel_${key}_scale", 100)
            val al = prefs.getInt("panel_${key}_alpha", 100)
            AlertDialog.Builder(this@uiPanel).setTitle(title).setItems(arrayOf(
                "Stay open when tapping outside: ON",
                "Size: $scale%", "Opacity: $al%",
                if (lock) "Unlock (allow moving)" else "Lock in place",
                "Close"
            )) { _, i ->
                when (i) {
                    0 -> { prefs.edit().putBoolean("panel_${key}_stay", false).apply(); handle.dismiss(); reopen() }
                    1 -> seekDialog("Panel size %", 60, 130, scale) { prefs.edit().putInt("panel_${key}_scale", it).apply(); place() }
                    2 -> seekDialog("Opacity %", 30, 100, al) { prefs.edit().putInt("panel_${key}_alpha", it).apply(); pw.alpha = it / 100f }
                    3 -> { prefs.edit().putBoolean("panel_${key}_lock", !lock).apply(); toast(if (!lock) "Locked" else "Unlocked: drag the title to move") }
                    4 -> handle.dismiss()
                }
            }.showT()
        }
    }
    val close = TextView(this).apply {
        text = "✕"; textSize = 19f; setTextColor(C_MUTED); gravity = Gravity.CENTER
        setOnClickListener { handle.dismiss() }
    }
    header.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
    header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))

    var sx = 0f; var sy = 0f; var ox = 0f; var oy = 0f
    header.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = pw.x; oy = pw.y }
            MotionEvent.ACTION_MOVE -> if (!prefs.getBoolean("panel_${key}_lock", false)) {
                pw.x = (ox + e.rawX - sx).coerceIn(0f, (panelLayer.width - pw.width).coerceAtLeast(0).toFloat())
                pw.y = (oy + e.rawY - sy).coerceIn(0f, (panelLayer.height - pw.height).coerceAtLeast(0).toFloat())
            }
            MotionEvent.ACTION_UP -> {
                val mx = (panelLayer.width - pw.width).coerceAtLeast(1).toFloat()
                val my = (panelLayer.height - pw.height).coerceAtLeast(1).toFloat()
                prefs.edit().putFloat("panel_${key}_x", (pw.x / mx).coerceIn(0f, 1f))
                    .putFloat("panel_${key}_y", (pw.y / my).coerceIn(0f, 1f)).apply()
            }
        }
        true
    }

    // When the content grows or shrinks, keep the panel fully on screen.
    pw.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
        if (b - t != ob - ot) pw.y = pw.y.coerceAtMost((panelLayer.height - pw.height).coerceAtLeast(0).toFloat())
    }
    pw.addView(header, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    pw.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    panelLayer.addView(pw, FrameLayout.LayoutParams(dp(300), dp(300)))
    panelRelayout[key] = { place() }
    openPanels[key] = handle
    place()
    panelLayer.post { place() }
    return handle
}

internal fun MainActivity.uiEdit(hint: String, number: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setHintTextColor(C_MUTED); setTextColor(C_TEXT); textSize = 15f
    setSingleLine()
    if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
    setPadding(dp(12), dp(8), dp(12), dp(8))
    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(C_BG); setStroke(dp(1), C_BORDER) }
}

internal fun MainActivity.uiBtn(label: String, fill: Int, fg: Int, stroke: Int, onTap: () -> Unit): TextView = TextView(this).apply {
    text = label; textSize = 14f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
    setOnClickListener { onTap() }
}

internal fun MainActivity.uiTitle(text: String, onClose: () -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    addView(TextView(context).apply {
        this.text = text; textSize = 20f; setTextColor(C_TEXT); typeface = Typeface.DEFAULT_BOLD
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(TextView(context).apply {
        this.text = "✕"; textSize = 20f; setTextColor(C_MUTED); setPadding(dp(12), dp(4), dp(4), dp(4))
        setOnClickListener { onClose() }
    })
}

internal fun MainActivity.uiDialog(content: View): AlertDialog {
    val d = AlertDialog.Builder(this).setView(content).create()
    d.show(); styleDlg(d)
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    return d
}

internal fun MainActivity.uiWatch(e: EditText, f: () -> Unit) {
    e.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { f() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
}

internal fun MainActivity.uiHideKeyboard(v: View) {
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
}
