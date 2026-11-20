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
import com.gzbrowser.MainActivity.Companion.C_DIV

// ----- Spark calculator -----

internal fun MainActivity.showSpark() {
    var dlg: AlertDialog? = null
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
    box.addView(uiTitle("Spark calculator") { dlg?.dismiss() })

    val banner = TextView(this).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0) }
    val big = TextView(this).apply { textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C_GOLD) }
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(C_ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(C_BORDER)
    }
    val sub = TextView(this).apply { textSize = 13f; setTextColor(C_MUTED) }
    // Rainbow celebration while the spark target is reached
    var rainbow: android.animation.ValueAnimator? = null
    fun stopRainbow() {
        rainbow?.cancel(); rainbow = null
        big.setTextColor(C_GOLD)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(C_ACCENT)
    }
    fun startRainbow() {
        if (rainbow != null) return
        rainbow = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val h = it.animatedValue as Float
                banner.setTextColor(Color.HSVToColor(floatArrayOf(h, 0.75f, 1f)))
                big.setTextColor(Color.HSVToColor(floatArrayOf((h + 90f) % 360f, 0.75f, 1f)))
                bar.progressTintList = android.content.res.ColorStateList.valueOf(Color.HSVToColor(floatArrayOf((h + 180f) % 360f, 0.8f, 1f)))
            }
            start()
        }
    }
    box.addView(banner); box.addView(big)
    box.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(6) })
    box.addView(sub, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

    fun labeled(label: String, e: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(10), 0, dp(4)) })
        addView(e, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    fun saved(k: String, def: String) = prefs.getString(k, def) ?: def
    val eCry = uiEdit("0", true).apply { setText(saved("spark_cry", "0")) }
    val eOne = uiEdit("0", true).apply { setText(saved("spark_one", "0")) }
    val eTen = uiEdit("0", true).apply { setText(saved("spark_ten", "0")) }
    val eOther = uiEdit("0", true).apply { setText(saved("spark_other", "0")) }
    val eTarget = uiEdit("300", true).apply { setText(saved("spark_target", "300")) }

    box.addView(labeled("Crystals", eCry))
    val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    two.addView(labeled("Single draw tickets", eOne), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
    two.addView(labeled("10-part tickets", eTen), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
    box.addView(two)
    box.addView(labeled("Other draws (anything else saved)", eOther))
    box.addView(labeled("Spark target (draws)", eTarget))
    box.addView(TextView(this).apply {
        text = "300 crystals = 1 draw. 300 draws = 1 spark."; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(8), 0, dp(4))
    })

    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(rows)

    fun num(e: EditText): Long = e.text.toString().trim().toLongOrNull()?.coerceIn(0L, 100_000_000L) ?: 0L
    fun statRow(label: String, value: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(9)) }
        r.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(C_TEXT) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(C_TEXT); typeface = Typeface.DEFAULT_BOLD })
        rows.addView(r)
        rows.addView(View(this).apply { setBackgroundColor(C_DIV) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    fun calc() {
        val cry = num(eCry)
        val fromCry = cry / 300
        val spare = cry % 300
        val tickets = num(eOne) + num(eTen) * 10 + num(eOther)
        val target = num(eTarget).coerceAtLeast(1L)
        val total = fromCry + tickets
        val sparks = total / target
        val left = total % target
        val need = if (total >= target) 0L else target - total
        val cryNeed = if (need == 0L) 0L else (need * 300 - spare).coerceAtLeast(0L)
        val done = total >= target
        banner.text = if (done) "SPARK COMPLETE!" else "Spark in progress"
        if (done) startRainbow() else { stopRainbow(); banner.setTextColor(C_ACCENT) }
        big.text = "$total / $target draws"
        bar.progress = (minOf(total, target) * 1000L / target).toInt()
        sub.text = if (done) "$sparks spark(s) ready. Time to pick your SSR!" else "$need draws to go."
        rows.removeAllViews()
        statRow("Total draws", "$total")
        statRow("From crystals", "$fromCry (+$spare crystals spare)")
        statRow("From tickets and other", "$tickets")
        statRow("Full sparks you can afford", "$sparks")
        statRow("Draws left after sparks", "$left")
        statRow("Draws still needed", "$need")
        statRow("Crystals still needed", "$cryNeed")
        prefs.edit().putString("spark_cry", eCry.text.toString()).putString("spark_one", eOne.text.toString())
            .putString("spark_ten", eTen.text.toString()).putString("spark_other", eOther.text.toString())
            .putString("spark_target", eTarget.text.toString()).apply()
    }
    listOf(eCry, eOne, eTen, eOther, eTarget).forEach { uiWatch(it) { calc() } }

    box.addView(uiBtn("Reset spark", C_SURFACE, C_TEXT, C_BORDER) {
        confirm("Reset all spark numbers?") {
            eCry.setText("0"); eOne.setText("0"); eTen.setText("0"); eOther.setText("0"); eTarget.setText("300")
            calc()
        }
    }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    calc()
    val sv = ScrollView(this).apply { addView(box) }
    dlg = uiDialog(sv)
    dlg?.setOnDismissListener { rainbow?.cancel(); rainbow = null }
}
