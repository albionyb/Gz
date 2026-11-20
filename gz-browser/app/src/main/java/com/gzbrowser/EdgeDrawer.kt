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
import com.gzbrowser.MainActivity.Companion.C_TEXT
import com.gzbrowser.MainActivity.Companion.C_ACCENT
import com.gzbrowser.MainActivity.Companion.C_MUTED

/** Edge drawer: a thin, always-visible handle on the screen edge (replaces the old floating ☰ button). */

internal fun MainActivity.positionEdgeHandle() {
    val parent = fsButton.parent as? FrameLayout ?: return
    val right = prefs.getBoolean("drawer_right", true)
    val lp = fsButton.layoutParams as FrameLayout.LayoutParams
    val pH = parent.height
    val frac = prefs.getFloat("drawer_y", 0.35f)
    val top = if (pH > 0) (frac * pH - lp.height / 2f).toInt().coerceIn(dp(48), (pH - dp(48) - lp.height).coerceAtLeast(dp(48))) else dp(120)
    val g = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
    if (lp.gravity != g || lp.topMargin != top) {
        lp.gravity = g; lp.topMargin = top; lp.leftMargin = 0; lp.rightMargin = 0
        fsButton.layoutParams = lp
    }
    fsButton.text = if (right) "‹" else "›"
}

@android.annotation.SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupEdgeHandle() {
    fsButton.apply {
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        alpha = 0.92f
        elevation = dp(4).toFloat()
        background = GradientDrawable().apply {
            setColor(0xDD13294B.toInt()); setStroke(dp(1), 0xAAFFFFFF.toInt()); cornerRadius = dp(10).toFloat()
        }
        contentDescription = "Edge drawer"
        visibility = View.GONE
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (android.os.Build.VERSION.SDK_INT >= 29) v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
        }
    }
    (fsButton.parent as? View)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionEdgeHandle() }
    var downX = 0f; var downY = 0f; var startTop = 0; var mode = 0
    fsButton.setOnTouchListener { v, e ->
        val parent = v.parent as? FrameLayout ?: return@setOnTouchListener false
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val right = prefs.getBoolean("drawer_right", true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startTop = lp.topMargin; mode = 0
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                val inward = if (right) -dx else dx
                if (mode == 0) {
                    if (inward > dp(16) && inward > Math.abs(dy)) { mode = 2; showEdgeDrawer() }
                    else if (Math.abs(dy) > dp(8)) mode = 1
                }
                if (mode == 1) {
                    val pH = parent.height
                    lp.topMargin = (startTop + dy).toInt().coerceIn(dp(48), (pH - dp(48) - lp.height).coerceAtLeast(dp(48)))
                    v.layoutParams = lp
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 0) showEdgeDrawer()
                if (mode == 1 && parent.height > 0) {
                    prefs.edit().putFloat("drawer_y", (lp.topMargin + lp.height / 2f) / parent.height).apply()
                }
            }
        }
        true
    }
}

internal fun MainActivity.dismissEdgeDrawer() {
    edgeDrawer?.let { (it.parent as? ViewGroup)?.removeView(it) }
    edgeDrawer = null
}

internal fun MainActivity.revealFloats() {
    floatReveal = true
    refreshFloats()
    handler.postDelayed({ floatReveal = false; refreshFloats() }, 3000)
}

internal fun MainActivity.showEdgeDrawer() {
    val parent = fsButton.parent as? FrameLayout ?: return
    dismissEdgeDrawer()
    val right = prefs.getBoolean("drawer_right", true)
    val fs = fullscreen()
    val hideAll = fs && prefs.getBoolean("fs_hide_all", false)
    val catcher = FrameLayout(this)
    catcher.setOnClickListener { dismissEdgeDrawer() }
    val card = LinearLayout(this)
    card.orientation = LinearLayout.VERTICAL
    card.background = GradientDrawable().apply {
        setColor(C_SURFACE); setStroke(dp(1), C_BORDER); cornerRadius = dp(14).toFloat()
    }
    card.setPadding(dp(6), dp(6), dp(6), dp(6))
    card.elevation = dp(10).toFloat()
    card.isClickable = true

    fun row(label: String, state: String?, action: () -> Unit) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(12), 0, dp(12), 0)
        r.setOnClickListener { dismissEdgeDrawer(); action() }
        r.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(C_TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (state != null) {
            r.addView(TextView(this).apply { text = state; textSize = 13f; setTextColor(if (state == "On") C_ACCENT else C_MUTED) })
        }
        card.addView(r, LinearLayout.LayoutParams(-1, dp(48)))
    }

    row("Fullscreen", if (fs) "On" else "Off") { setFullscreen(!fs) }
    row("Hide all bars", if (hideAll) "On" else "Off") {
        val on = !hideAll
        prefs.edit().putBoolean("fs_hide_all", on).apply()
        if (on && !fullscreen()) setFullscreen(true) else updateChrome()
    }
    row("Main menu", null) { showMenu() }
    row("Show floating buttons (3 s)", null) { revealFloats() }
    row("Move handle to other side", null) {
        prefs.edit().putBoolean("drawer_right", !right).apply()
        positionEdgeHandle()
    }

    val cardH = dp(48) * 5 + dp(12)
    val pH = parent.height
    val top = ((prefs.getFloat("drawer_y", 0.35f) * pH).toInt() - cardH / 2).coerceIn(dp(8), (pH - cardH - dp(8)).coerceAtLeast(dp(8)))
    val lp = FrameLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or (if (right) Gravity.END else Gravity.START))
    lp.topMargin = top
    if (right) lp.rightMargin = dp(28) else lp.leftMargin = dp(28)
    catcher.addView(card, lp)
    parent.addView(catcher, FrameLayout.LayoutParams(-1, -1))
    edgeDrawer = catcher
    card.alpha = 0f
    card.translationX = if (right) dp(40).toFloat() else -dp(40).toFloat()
    card.animate().alpha(1f).translationX(0f).setDuration(140).start()
}


/** auto = only in full screen, always, or hidden (open it with a gesture instead). */
internal fun MainActivity.drawerMode(): String =
    prefs.getString("drawer_mode", null) ?: if (prefs.getBoolean("drawer_always", false)) "always" else "auto"

internal fun MainActivity.hasDrawerGesture(): Boolean =
    gesturesOn() && GestureMap.GESTURES.any { gestureAction(it.key) == "drawer" }

/** If the handle is hidden but no gesture opens the drawer any more, bring the handle back. */
internal fun MainActivity.ensureDrawerReachable() {
    if (drawerMode() == "hidden" && !hasDrawerGesture()) {
        prefs.edit().putString("drawer_mode", "auto").apply()
        updateChrome()
        toast("Edge drawer handle is back: no gesture opens the drawer")
    }
}

internal fun MainActivity.drawerModeLabel(): String = when (drawerMode()) {
    "always" -> "Always show"
    "hidden" -> "Hidden. Open it with a gesture"
    else -> "Auto: only in full screen"
}

internal fun MainActivity.chooseDrawerMode() {
    val names = arrayOf("Auto (only in full screen)", "Always show", "Hidden (open with a gesture)")
    val keys = arrayOf("auto", "always", "hidden")
    AlertDialog.Builder(this).setTitle("Edge drawer handle")
        .setSingleChoiceItems(names, keys.indexOf(drawerMode()).coerceAtLeast(0)) { d, i ->
            d.dismiss()
            if (keys[i] == "hidden" && !hasDrawerGesture()) { pickDrawerGesture(); return@setSingleChoiceItems }
            prefs.edit().putString("drawer_mode", keys[i]).apply()
            updateChrome(); refreshSettings()
        }.showT()
}

private fun MainActivity.pickDrawerGesture() {
    AlertDialog.Builder(this).setTitle("Pick a gesture that opens the drawer")
        .setItems(GestureMap.GESTURES.map { it.label }.toTypedArray()) { _, i ->
            prefs.edit().putString("gest_${GestureMap.GESTURES[i].key}", "drawer")
                .putBoolean("gestures_on", true).putString("drawer_mode", "hidden").apply()
            applyGestureExclusion(); updateChrome(); refreshSettings()
        }
        .setNegativeButton("Cancel", null).showT()
}
