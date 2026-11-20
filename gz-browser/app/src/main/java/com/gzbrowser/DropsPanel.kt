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
import com.gzbrowser.MainActivity.Companion.C_SURFACE
import com.gzbrowser.MainActivity.Companion.C_BORDER
import com.gzbrowser.MainActivity.Companion.C_ACCENT
import com.gzbrowser.MainActivity.Companion.C_MUTED
import com.gzbrowser.MainActivity.Companion.C_TEXT
import com.gzbrowser.MainActivity.Companion.C_TILE
import com.gzbrowser.MainActivity.Companion.C_TILE2

// ----- Drop tracker (simple) -----

internal fun MainActivity.showDrops() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(14)) }

    val input = uiEdit("Item name")
    var addOpen = false
    var page = 0
    var rebuild: () -> Unit = {}

    // Add row stays hidden until "+ Add item" is tapped (always shown while there are no items)
    val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    val toggleAdd = TextView(this).apply {
        textSize = 13f; setTextColor(C_ACCENT); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(8), dp(2), dp(8))
        setOnClickListener { addOpen = !addOpen; rebuild() }
    }
    bar.addView(toggleAdd)

    val empty = TextView(this).apply {
        text = "Add an item, then tap a chest after each fight."
        textSize = 13f; setTextColor(C_MUTED); setPadding(0, dp(10), 0, dp(4))
    }
    val pager = SnapHScroll(this)
    val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    pager.addView(strip)
    val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    val gap = dp(8)

    fun paintDots() {
        dots.removeAllViews()
        if (drops.items.size < 2) return
        for (i in drops.items.indices) dots.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == page) C_TEXT else C_BORDER) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(3), 0, dp(3), 0) })
    }
    pager.onPage = { page = it; paintDots() }

    // One card fills the whole pager width, so you only see one item at a time.
    fun sizeCards() {
        val w = pager.width
        if (w <= 0) return
        for (i in 0 until strip.childCount) {
            strip.getChildAt(i).layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i < strip.childCount - 1) rightMargin = gap }
        }
        pager.pageW = w + gap
        strip.requestLayout()
        pager.post { pager.snapTo(page, smooth = false) }
    }
    pager.addOnLayoutChangeListener { _, l, _, r, _, ol, _, oldR, _ -> if (r - l != oldR - ol) sizeCards() }

    fun chestButton(label: String, sparkly: Boolean, fill: Int, stroke: Int, onTap: (ChestView) -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
            contentDescription = label
        }
        val chest = ChestView(this, sparkly)
        col.addView(chest, LinearLayout.LayoutParams(dp(60), dp(60)))
        col.addView(TextView(this).apply { text = label; textSize = 10f; setTextColor(C_MUTED); gravity = Gravity.CENTER })
        col.setOnClickListener { onTap(chest) }
        return col
    }

    fun buildCard(d: DropItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(C_TILE); setStroke(dp(1), C_BORDER) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = d.name; textSize = 16f; setTextColor(C_TEXT); typeface = Typeface.DEFAULT_BOLD
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "⋮"; textSize = 20f; setTextColor(C_MUTED); gravity = Gravity.CENTER
            setOnClickListener {
                AlertDialog.Builder(this@showDrops).setItems(arrayOf("Rename", "Delete")) { _, w ->
                    if (w == 0) renameDrop(d) { rebuild() }
                    else AlertDialog.Builder(this@showDrops).setMessage("Delete \"${d.name}\"?")
                        .setPositiveButton("Delete") { _, _ -> drops.items.remove(d); drops.save(); rebuild() }
                        .setNegativeButton("Cancel", null).showT()
                }.showT()
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        card.addView(head)

        val big = TextView(this).apply { textSize = 44f; setTextColor(C_ACCENT); typeface = Typeface.DEFAULT_BOLD }
        val meta = TextView(this).apply { textSize = 14f; setTextColor(C_TEXT); setPadding(0, dp(6), 0, dp(10)) }
        val undo = TextView(this).apply {
            text = "Undo last"; textSize = 13f; setTextColor(C_MUTED); setPadding(0, dp(10), dp(12), dp(2))
        }
        // Refresh in place (no rebuild) so the chest animation keeps playing
        fun refresh() {
            big.text = "${d.since}"
            meta.text = if (d.drops == 0) "No drops yet · ${d.fights} fights"
            else "${d.drops} drop${if (d.drops == 1) "" else "s"} · avg ${String.format(Locale.US, "%.1f", d.average)} fights per drop"
            undo.visibility = if (d.log.isEmpty()) View.INVISIBLE else View.VISIBLE
        }
        undo.setOnClickListener { d.log = d.log.dropLast(1); drops.save(); refresh() }

        card.addView(big)
        card.addView(TextView(this).apply { text = "fights since last drop"; textSize = 12f; setTextColor(C_MUTED) })
        card.addView(meta)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(chestButton("No drop", false, C_SURFACE, C_BORDER) { it.shake(); d.log += "0"; drops.save(); refresh() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        btns.addView(chestButton("Dropped", true, C_SURFACE, 0xFF3FB7E8.toInt()) { it.open(); d.log += "1"; drops.save(); refresh() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        card.addView(btns)
        card.addView(undo)
        refresh()
        return card
    }

    rebuild = {
        strip.removeAllViews()
        val none = drops.items.isEmpty()
        page = page.coerceIn(0, (drops.items.size - 1).coerceAtLeast(0))
        for (d in drops.items.toList()) strip.addView(buildCard(d), LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
        empty.visibility = if (none) View.VISIBLE else View.GONE
        pager.visibility = if (none) View.GONE else View.VISIBLE
        bar.visibility = if (none) View.GONE else View.VISIBLE
        addRow.visibility = if (none || addOpen) View.VISIBLE else View.GONE
        toggleAdd.text = if (addOpen) "Hide" else "+ Add item"
        paintDots()
        pager.post { sizeCards() }
    }

    fun addNow() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) { toast("Enter an item name"); return }
        drops.add(t); input.setText(""); uiHideKeyboard(input)
        addOpen = false; page = drops.items.size - 1
        rebuild()
    }
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.setOnEditorActionListener { _, _, _ -> addNow(); true }
    addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addRow.addView(uiBtn("Add", C_TILE2, C_TEXT, C_BORDER) { addNow() },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })

    box.addView(bar)
    box.addView(addRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
    box.addView(empty)
    box.addView(pager, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    box.addView(dots, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    rebuild()
    val sv = ScrollView(this).apply { addView(box) }
    uiPanel("drops", "Drop tracker", sv) { showDrops() }
}

internal fun MainActivity.renameDrop(d: DropItem, done: () -> Unit) {
    val et = uiEdit("Item name").apply { setText(d.name); setSelection(d.name.length) }
    val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(16), dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    AlertDialog.Builder(this).setView(wrap)
        .setPositiveButton("Save") { _, _ ->
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) { d.name = t; drops.save(); done() }
        }
        .setNegativeButton("Cancel", null).showT()
}
