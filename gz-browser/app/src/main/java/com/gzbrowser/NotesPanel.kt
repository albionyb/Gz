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
import com.gzbrowser.MainActivity.Companion.C_BORDER
import com.gzbrowser.MainActivity.Companion.C_MUTED
import com.gzbrowser.MainActivity.Companion.C_TEXT
import com.gzbrowser.MainActivity.Companion.C_DIV
import com.gzbrowser.MainActivity.Companion.C_TILE2

// ----- Notes -----

internal fun MainActivity.showNotes() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(14)) }
    box.addView(TextView(this).apply {
        text = "Tap the box to mark a task done. A battle counter counts down to zero."
        textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, 0)
    })

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(list); minimumHeight = dp(100) }
    box.addView(scroll,
        if (panelStay("notes")) LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) }
        else LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.36f).toInt()).apply { topMargin = dp(6) })

    val count = TextView(this).apply { textSize = 13f; setTextColor(C_MUTED) }

    fun rebuild() {
        list.removeAllViews()
        if (notes.items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No notes yet. Add one below."; textSize = 14f; setTextColor(C_MUTED); setPadding(0, dp(18), 0, 0)
            })
        }
        for (n in notes.items.toList()) {
            val counting = n.toggle && n.total > 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            if (n.toggle) {
                val c = if (n.done) C_GREEN else C_RED
                row.addView(TextView(this).apply {
                    text = when { n.done -> "✓"; counting -> "${n.left}"; else -> "✗" }
                    textSize = if (n.done || !counting) 18f else when { n.left >= 1000 -> 11f; n.left >= 100 -> 14f; else -> 17f }
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; setTextColor(c)
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(tint(c)); setStroke(dp(1), c) }
                    setOnClickListener {
                        when {
                            counting && !n.done -> { n.left = n.left - 1; if (n.left <= 0) { n.left = 0; n.done = true } }
                            counting && n.done -> { n.left = n.total; n.done = false }
                            else -> n.done = !n.done
                        }
                        notes.save(); rebuild()
                    }
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
            } else {
                row.addView(TextView(this).apply {
                    text = "•"; textSize = 20f; setTextColor(C_MUTED); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
            }
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
            textCol.addView(TextView(this).apply {
                text = n.text; textSize = 15f
                setTextColor(if (n.toggle && n.done) C_MUTED else C_TEXT)
                if (n.toggle && n.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            if (counting && !n.done) textCol.addView(TextView(this).apply {
                text = "${n.left} of ${n.total} battles left"; textSize = 11f; setTextColor(C_MUTED)
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "⋮"; textSize = 20f; setTextColor(C_MUTED); gravity = Gravity.CENTER
                setOnClickListener {
                    AlertDialog.Builder(this@showNotes).setItems(
                        arrayOf("Edit text", if (n.toggle) "Turn toggle off (plain note)" else "Turn toggle on",
                            if (n.total > 0) "Battle counter: ${n.total} (change)" else "Add battle counter", "Delete")
                    ) { _, w ->
                        when (w) {
                            0 -> editNote(n) { rebuild() }
                            1 -> { n.toggle = !n.toggle; n.done = false; n.left = n.total; notes.save(); rebuild() }
                            2 -> editCounter(n) { rebuild() }
                            3 -> { notes.items.remove(n); notes.save(); rebuild() }
                        }
                    }.showT()
                }
            }, LinearLayout.LayoutParams(dp(34), dp(38)))
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(C_DIV) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        val toggles = notes.items.filter { it.toggle }
        count.text = if (toggles.isEmpty()) "${notes.items.size} notes" else "${toggles.count { it.done }} of ${toggles.size} done"
    }

    // Add row: note text, optional battle count, add button
    val input = uiEdit("Add a note")
    val countIn = uiEdit("Count", true)
    fun addNow() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val c = countIn.text.toString().trim().toIntOrNull()?.coerceIn(0, 9999) ?: 0
        notes.add(t, c); input.setText(""); countIn.setText(""); rebuild()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.setOnEditorActionListener { _, _, _ -> addNow(); true }
    countIn.imeOptions = EditorInfo.IME_ACTION_DONE
    countIn.setOnEditorActionListener { _, _, _ -> addNow(); true }
    val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addRow.addView(countIn, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
    addRow.addView(uiBtn("+", C_TILE2, C_TEXT, C_BORDER) { addNow() },
        LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
    box.addView(addRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    // Footer: count on the left; Clear is the bottom-right corner
    val clear = uiBtn("Clear", tint(C_RED), C_RED, C_RED) {
        if (notes.items.isEmpty()) toast("Nothing to clear")
        else confirm("Clear all notes?") { notes.clear(); rebuild() }
    }
    val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    foot.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    foot.addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
    box.addView(foot, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    rebuild()
    uiPanel("notes", "Notes", box) { showNotes() }
}

internal fun MainActivity.editCounter(n: Note, done: () -> Unit) {
    val et = uiEdit("Battles (0 = no counter)", true).apply { if (n.total > 0) { setText("${n.total}"); setSelection(text.length) } }
    val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(16), dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    AlertDialog.Builder(this).setTitle("Battle counter").setView(wrap)
        .setPositiveButton("Save") { _, _ ->
            val v = et.text.toString().trim().toIntOrNull()?.coerceIn(0, 9999) ?: 0
            n.total = v; n.left = v; n.done = false
            if (v > 0) n.toggle = true
            notes.save(); done()
        }
        .setNegativeButton("Cancel", null).showT()
}

internal fun MainActivity.editNote(n: Note, done: () -> Unit) {
    val et = uiEdit("Note").apply { setText(n.text); setSelection(n.text.length) }
    val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(16), dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    AlertDialog.Builder(this).setView(wrap)
        .setPositiveButton("Save") { _, _ ->
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) { n.text = t; notes.save(); done() }
        }
        .setNegativeButton("Cancel", null).showT()
}
