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

class Tab(val web: WebView) {
    var id: Long = System.nanoTime()   // stable id: names the saved thumbnail file
    var pending: String? = null        // restored tab whose page is loaded the first time it is shown
    var url = ""
    var title = ""
    var isHome = false
    var desktop = false
    var profile = ""                   // "" = Main; otherwise the id of the profile this tab lives in
    var group = ""                     // id of the TabGroup this tab sits in; "" = not grouped
    var thumb: Bitmap? = null
    var thumbTime = 0L                 // uptime of the last live (PixelCopy) preview, so a worse one doesn't replace it right away
    var favicon: Bitmap? = null
    val canGoBack: Boolean get() = web.canGoBack()
}

object ProfileColors {
    val names = BrowserProfileStore.NAMES
    val colors = BrowserProfileStore.PALETTE
    val mainNames = arrayOf("Grey") + BrowserProfileStore.NAMES
    val mainColors = intArrayOf(0xFF607D8B.toInt()) + BrowserProfileStore.PALETTE
}

/** State of one full-page capture (scroll + draw + stitch). */
class FullShot(
    val web: WebView, val vw: Int, val vh: Int, val total: Int, val startY: Int,
    val out: Bitmap, val frame: Bitmap
) {
    val canvas = Canvas(out)
    val frameCanvas = Canvas(frame)
    var y = 0
    var filled = 0
    var fixedHidden = false
    var barsShown = false
    var target = 0
}

class MainActivity : Activity() {

    companion object {
        const val HOME = "https://game.granbluefantasy.jp/"
        const val HOME_PAGE = "gbf:home"   // marker for the built-in home screen
        const val MAX_SHOT_H = 15000
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        const val REQ_FILE = 43
        // Theme colors. Filled from Theme presets / user accent by applyTheme().
        var C_BG = 0xFF0B1626.toInt()
        var C_SURFACE = 0xFF12263F.toInt()
        var C_DOCK = 0xFF0F2036.toInt()
        var C_BORDER = 0xFF1F4468.toInt()
        var C_ACCENT = 0xFF3FB7E8.toInt()
        var C_ICE = 0xFFCFEEFF.toInt()
        var C_MUTED = 0xFF7FA6C2.toInt()
        var C_TEXT = 0xFFE6F6FF.toInt()
        var C_TILE = 0xFF0F2A45.toInt()
        var C_EDGE = 0xFF8FD3F0.toInt()
        var C_DIV = 0xFF1A3652.toInt()
        var C_TILE2 = 0xFF1B3A5C.toInt()
        val COLOR_NAMES = arrayOf("Cyan", "Red", "Green", "Orange", "Purple", "Pink", "Gray")
        val COLORS = intArrayOf(
            0xFF00B8E6.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFF757575.toInt()
        )
    }

    lateinit var prefs: SharedPreferences
    lateinit var store: BookmarkStore
    var settingsGroup: LinearLayout? = null
    private lateinit var history: HistoryStore
    private lateinit var mobileUa: String
    private lateinit var desktopUa: String
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    lateinit var strip: View
    private lateinit var bubbleScroll: HorizontalScrollView
    private lateinit var bubbleRow: LinearLayout
    private lateinit var bubbleHandle: FrameLayout
    private lateinit var bubbleArrow: TextView
    private lateinit var bottomBar: LinearLayout
    lateinit var fsButton: TextView
    var edgeDrawer: View? = null
    var floatReveal = false
    private lateinit var tabCount: TextView
    lateinit var shortcuts: ShortcutStore
    lateinit var floats: FloatStore
    lateinit var notes: NoteStore
    lateinit var drops: DropStore
    lateinit var floatLayer: FrameLayout
    fun floatLayerReady() = ::floatLayer.isInitialized
    lateinit var panelLayer: FrameLayout
    val panelRelayout = LinkedHashMap<String, () -> Unit>()
    val openPanels = LinkedHashMap<String, PanelHandle>()
    val floatViews = HashMap<Long, View>()
    private lateinit var homeView: ScrollView
    private lateinit var homeBox: LinearLayout
    private lateinit var loadBar: View
    private lateinit var shield: TextView
    private var capturing = false
    lateinit var downloads: DownloadStore
    private var dlListener: (() -> Unit)? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val sessionTask = Runnable { saveSession() }

    val tabs = mutableListOf<Tab>()
    var current: Tab? = null
    val handler = Handler(Looper.getMainLooper())
    var barShown = true
    private var lastBack = 0L
    var settingsDialog: Dialog? = null
    var settingsPage = ""
    lateinit var profiles: BrowserProfileStore
    lateinit var tabGroups: TabGroupStore
    private var gestActive = false
    private var lastFind = ""
    private var findTab: Tab? = null
    private val hideBarTask = Runnable { hideBar() }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun toastUi(s: String) = runOnUiThread { toast(s) }
    private fun copyText(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", s))
        toast("Copied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gbf", MODE_PRIVATE)
        store = BookmarkStore(prefs)
        history = HistoryStore(prefs)
        shortcuts = ShortcutStore(prefs)
        floats = FloatStore(prefs)
        notes = NoteStore(prefs)
        drops = DropStore(prefs)
        downloads = DownloadStore(prefs)
        profiles = BrowserProfileStore(prefs)
        tabGroups = TabGroupStore(prefs)
        applyTheme()

        wipePendingProfiles()
        initWeb()
        buildUi()
        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyGestureExclusion() }
        applyOrientation()
        applyKeepAwake()
        refreshBubbles()
        cleanupFloats()
        refreshFloats()
        if (!restoreSession()) openTab(startUrl())
        applySystemBars()
        updateChrome()
        scheduleHide()
    }

    override fun onPause() {
        if (dragView != null) endBubbleDrag(false)
        captureThumb()
        saveSession(withThumbs = true)
        flushCookies()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    // ---------- WebView (Chromium) ----------

    private fun initWeb() {
        val def = WebSettings.getDefaultUserAgent(this)
        // The stock WebView UA contains "; wv" and "Version/4.0". Google and some other login pages
        // refuse embedded browsers, so present the same UA as Chrome for Android.
        mobileUa = def.replace("; wv", "").replace(" Version/4.0", "")
        val chrome = Regex("Chrome/([0-9.]+)").find(def)?.groupValues?.get(1) ?: "120.0.0.0"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        WebView.setWebContentsDebuggingEnabled(false)
        Thread { AdBlock.load(applicationContext) }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWeb(web: WebView, profile: String) {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true   // popups reach onCreateWindow, which blocks ad ones
        s.setSupportMultipleWindows(true)                // window.open / target=_blank are loaded in the same tab
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.useWideViewPort = true
        s.displayZoomControls = false
        web.setBackgroundColor(C_BG)
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES   // password managers
        cookiesOf(profile)?.apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        applyWebPrefs(web)
    }

    /** Text mode and pinch zoom. Applied live, no restart needed. */
    private fun applyWebPrefs(web: WebView) {
        val zoom = prefs.getBoolean("zoom", true)
        web.settings.setSupportZoom(zoom)
        web.settings.builtInZoomControls = zoom
        web.settings.displayZoomControls = false
        web.settings.blockNetworkImage = prefs.getBoolean("text_mode", false)
    }

    fun applyWebPrefsAll() { tabs.forEach { applyWebPrefs(it.web) } }

    private var lastBlockToast = 0L
    private fun toastBlocked(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockToast < 2000) return
        lastBlockToast = now
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    fun hostOf(url: String): String = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }

    private fun desktopHosts(): Set<String> = prefs.getStringSet("desktop_hosts", emptySet()) ?: emptySet()

    private fun noFilterHosts(): Set<String> = prefs.getStringSet("nofilter_hosts", emptySet()) ?: emptySet()

    /** Never filters anything on the game's own sites (no blocking, hiding or popup rules there). */
    private fun blockingOn(host: String): Boolean =
        prefs.getBoolean("block_on", true) && !noFilterHosts().contains(host) && !AdBlock.isAllowed(host)

    private fun setSiteMode(tab: Tab, desktop: Boolean) {
        tab.desktop = desktop
        val s = tab.web.settings
        s.userAgentString = if (desktop) desktopUa else mobileUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = desktop
    }

    /** Returns true if the tab's UA mode had to change (caller should reload). */
    private fun applySiteMode(tab: Tab, url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        val want = desktopHosts().contains(host)
        if (want == tab.desktop) return false
        setSiteMode(tab, want)
        return true
    }

    // Home modes: 0 = home screen, 1 = last visited page, 2 = custom URL, 3 = the game
    private fun homeUrl(): String = when (prefs.getInt("home_mode", 0)) {
        2 -> (prefs.getString("home_url", "") ?: "").ifBlank { HOME_PAGE }
        3 -> HOME
        else -> HOME_PAGE
    }

    private fun startUrl(): String =
        if (prefs.getInt("home_mode", 0) == 1) (prefs.getString("last_url", HOME_PAGE) ?: HOME_PAGE) else homeUrl()

    fun visibleUrl(): String? =
        current?.takeIf { !it.isHome && it.url.startsWith("http") }?.url

    private fun openTab(url: String, show: Boolean = true, lazy: Boolean = false, profile: String = ""): Tab {
        val isHomeTab = url == HOME_PAGE
        var prof = profile
        val web: WebView = newWebView(prof) ?: run {
            toast("Profile \"${profiles.nameOf(prof)}\" isn't available, opened in ${profiles.mainName}")
            prof = ""
            newWebView("")!!
        }
        configureWeb(web, prof)
        val tab = Tab(web)
        tab.profile = prof
        tab.isHome = isHomeTab
        tab.url = if (isHomeTab) "" else url
        setSiteMode(tab, !isHomeTab && desktopHosts().contains(hostOf(url)))
        attachClients(tab)
        tabs.add(tab)
        if (!isHomeTab) { if (lazy) tab.pending = url else web.loadUrl(url) }
        if (show) switchTo(tab)
        updateTabCount()
        scheduleSession()
        return tab
    }

    private fun attachClients(tab: Tab) {
        val web = tab.web
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == null) return
                tab.url = url
                tab.favicon = null
                if (applySiteMode(tab, url)) { view?.post { view.reload() }; return }
                history.add(url)
                if (tab === current) {
                    if (!tab.isHome && !urlBar.hasFocus()) urlBar.setText(url)
                    if (url.startsWith("http")) prefs.edit().putString("last_url", url).apply()
                    updateLoadProgress(5)
                }
            }

            // GBF is a single-page app: #mypage -> #raid never fires onPageStarted.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url == null || !url.startsWith("http")) return
                tab.url = url
                scheduleSession()
                if (tab === current) {
                    if (!tab.isHome && !urlBar.hasFocus()) urlBar.setText(url)
                    prefs.edit().putString("last_url", url).apply()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (tab === current) updateLoadProgress(100)
                if (view != null && url != null && url.startsWith("http")) {
                    val h = hostOf(url)
                    if (blockingOn(h) && !AdBlock.isAllowed(h)) view.evaluateJavascript(AdBlock.cosmeticJs(), null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                when (u.scheme) {
                    "http", "https" -> {
                        // Plain http is not allowed: upgrade page navigations (and redirects) to https.
                        if (u.scheme == "http" && request.isForMainFrame) {
                            val secure = u.buildUpon().scheme("https").build().toString()
                            view?.post { view.loadUrl(secure) }
                            return true
                        }
                        // Ad redirects: a link, script or server redirect that jumps to an ad/tracker domain.
                        val ph = hostOf(tab.url)
                        if (blockingOn(ph) && AdBlock.isBlocked(u.host, ph)) {
                            toastBlocked("Ad redirect blocked")
                            return true
                        }
                        return false
                    }
                    "about", "data", "blob", "javascript", "file" -> return false
                }
                // Only a tap can open another app, and only for a short list of safe schemes.
                if (request.hasGesture() && u.scheme in setOf("mailto", "tel", "market")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || request.isForMainFrame) return null
                val pageHost = hostOf(tab.url)
                if (!blockingOn(pageHost)) return null
                if (AdBlock.isBlocked(request.url.host, pageHost))
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                return null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab === current) updateLoadProgress(newProgress)
            }

            // Popups / target=_blank: block ones opened without a tap, and ones aimed at ad domains.
            // Allowed ones are loaded in the current tab (same behaviour as before).
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val pageHost = hostOf(tab.url)
                val filtering = blockingOn(pageHost) && !AdBlock.isAllowed(pageHost)
                if (filtering && !isUserGesture) {
                    toastBlocked("Popup blocked")
                    return false
                }
                val tmp = newWebView(tab.profile) ?: return false
                var closed = false
                fun close() { if (!closed) { closed = true; try { tmp.stopLoading(); tmp.destroy() } catch (e: Exception) { } } }
                fun handle(u: String?): Boolean {
                    if (closed || u == null || !u.startsWith("http")) return false
                    if (filtering && AdBlock.isBlocked(hostOf(u), pageHost)) toastBlocked("Popup blocked")
                    else tab.web.post { tab.web.loadUrl(u) }
                    tmp.post { close() }
                    return true
                }
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = handle(r?.url?.toString())
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) { handle(url) }
                }
                tmp.postDelayed({ close() }, 15000)
                transport.webView = tmp
                msg.sendToTarget()
                return true
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) try { tab.favicon = Bitmap.createScaledBitmap(icon, 48, 48, true) } catch (e: Throwable) { }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title ?: ""
                history.setTitle(tab.url, tab.title)
            }

            // <input type=file>: open the system file picker (uploads, avatars, screenshots...).
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                val fromParams: Intent? = try { fileChooserParams?.createIntent() } catch (e: Exception) { null }
                val intent = fromParams
                    ?: Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                try {
                    startActivityForResult(intent, REQ_FILE)
                } catch (e: Exception) {
                    fileCb?.onReceiveValue(null); fileCb = null
                    toast("No file picker available")
                }
                return true
            }
        }
        web.setOnLongClickListener { onWebLongPress(tab) }
        web.setDownloadListener { url, ua, cd, mime, _ -> handleDownload(url, ua, cd, mime, tab.url, tab.profile) }
    }

    private fun onWebLongPress(tab: Tab): Boolean {
        val hit = tab.web.hitTestResult
        val extra = hit.extra
        when (hit.type) {
            WebView.HitTestResult.IMAGE_TYPE -> showContextMenu(tab, extra, null)
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> showContextMenu(tab, null, extra)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = Handler(Looper.getMainLooper()) { m ->
                    showContextMenu(tab, extra, m.data.getString("url"))
                    true
                }
                tab.web.requestFocusNodeHref(h.obtainMessage())
            }
            else -> return false
        }
        return true
    }

    private fun switchTo(tab: Tab) {
        val old = current
        current = tab
        if (old != null && old !== tab && old.profile != tab.profile && profiles.items.isNotEmpty())
            toast("Profile: ${profiles.nameOf(tab.profile)}")
        if (old != null && old !== tab) {
            captureTabThumb(old)
            container.removeView(old.web)
            if (!prefs.getBoolean("bg_run", true)) old.web.onPause()
        }
        tab.web.onResume()
        if (!tab.isHome) ensureLoaded(tab)
        if (tab.web.parent == null) container.addView(tab.web, 0, FrameLayout.LayoutParams(-1, -1))
        applyWidth()
        loadBar.animate().cancel(); loadBar.alpha = 0f
        applyHomeState(tab)
        updateTabCount()
        scheduleSession()
    }

    private fun ensureLoaded(tab: Tab) {
        val u = tab.pending ?: return
        tab.pending = null
        tab.web.loadUrl(u)
    }

    /** Shows/hides the home overlay and syncs the address bar for [tab] (if it is the current tab). */
    private fun applyHomeState(tab: Tab) {
        if (tab !== current) return
        if (tab.isHome) {
            refreshHome()
            homeView.visibility = View.VISIBLE
            if (!urlBar.hasFocus()) urlBar.setText("")
        } else {
            homeView.visibility = View.GONE
            if (!urlBar.hasFocus()) urlBar.setText(tab.url)
        }
    }

    private fun setHome(tab: Tab, on: Boolean) { tab.isHome = on; if (!on) ensureLoaded(tab); applyHomeState(tab); scheduleSession() }

    fun goHome() {
        val u = homeUrl()
        if (u == HOME_PAGE) {
            captureThumb()
            current?.let { setHome(it, true) }
        } else load(u)
    }

    private fun updateLoadProgress(p: Int) {
        loadBar.animate().cancel()
        loadBar.pivotX = 0f
        loadBar.scaleX = p.coerceIn(0, 100) / 100f
        if (p >= 100) loadBar.animate().alpha(0f).setStartDelay(250).setDuration(400).start()
        else loadBar.alpha = 1f
    }

    private fun closeTab(t: Tab) {
        if (!tabs.contains(t)) return
        if (t === current) {
            val others = tabs.filter { it !== t }
            val next = others.getOrNull(minOf(tabs.indexOf(t), others.size - 1)) ?: openTab(homeUrl(), show = false)
            switchTo(next)
        }
        tabs.remove(t)
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        t.web.stopLoading()
        t.web.destroy()
        updateTabCount()
        scheduleSession()
    }

    private fun updateTabCount() {
        if (::tabCount.isInitialized) tabCount.text = tabs.size.toString()
        floatViews.values.forEach { v -> if (v is TextView && v.tag == "tabcount") v.text = tabs.size.toString() }
    }

    fun load(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") -> "https://" + q.removePrefix("http://")
            q.startsWith("https://") -> q
            q.contains(" ") || !q.contains(".") -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            else -> "https://$q"
        }
        val t = current ?: return
        t.pending = null
        if (t.isHome) setHome(t, false)
        t.web.loadUrl(url)
    }

    // ---------- UI ----------

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(C_BG) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))

        // Thin handle shown instead of the address bar while it is hidden.
        strip = FrameLayout(this).apply {
            val line = View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x66FFFFFF) }
            }
            (this as FrameLayout).addView(line, FrameLayout.LayoutParams(dp(60), dp(4), Gravity.CENTER))
            setOnClickListener { showBar() }
            visibility = View.GONE
        }
        root.addView(strip, LinearLayout.LayoutParams(-1, dp(18)))

        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "Search or URL"
            setTextColor(Color.WHITE)
            setHintTextColor(C_MUTED)
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER)
            }
            setPadding(dp(18), 0, dp(18), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ ->
                load(v.text.toString())
                v.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                scheduleHide()
                true
            }
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { v, f ->
                if (!f) scheduleHide() else (v as EditText).post { v.selectAll() }
            }
            setOnTouchListener { _, _ -> scheduleHide(); false }
            // Every tap selects the whole URL, so typing replaces it. Long-press still lets you pick a part.
            setOnClickListener { v -> (v as EditText).post { v.selectAll() } }
        }
        root.addView(urlBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(10), dp(4), dp(10), dp(4))
        })

        container = FrameLayout(this)
        homeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        homeView = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isClickable = true
            visibility = View.GONE
            addView(homeBox)
        }
        container.addView(homeView, FrameLayout.LayoutParams(-1, -1))
        loadBar = View(this).apply { setBackgroundColor(C_ACCENT); alpha = 0f; scaleX = 0f }
        container.addView(loadBar, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyWidth() }
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))

        bubbleScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(C_DOCK); setStroke(dp(1), C_BORDER)
            }
        }
        bubbleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bubbleScroll.addView(bubbleRow)
        // Small handle above the bubble row: tap to hide the row, tap again to bring it back.
        bubbleArrow = TextView(this).apply {
            text = "▾"; textSize = 12f; setTextColor(C_ICE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(C_DOCK); setStroke(dp(1), C_BORDER) }
        }
        bubbleHandle = FrameLayout(this).apply {
            addView(bubbleArrow, FrameLayout.LayoutParams(dp(56), dp(16), Gravity.CENTER))
            setOnClickListener { toggleBubbleRow() }
        }
        root.addView(bubbleHandle, LinearLayout.LayoutParams(-1, dp(20)))
        root.addView(bubbleScroll, LinearLayout.LayoutParams(-1, dp(80)).apply { setMargins(dp(10), 0, dp(10), dp(6)) })

        root.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C_BG) }
        fun nav(res: Int, action: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(C_ICE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { action() }
        }.also { bottomBar.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        fun btn(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }.also { bottomBar.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        nav(R.drawable.ic_nav_back) { goBack() }
        nav(R.drawable.ic_nav_fwd) { current?.let { if (!it.isHome) it.web.goForward() } }
        // Tab switcher button: bordered box with the tab count.
        val tabBtn = FrameLayout(this).apply { setOnClickListener { showTabs() } }
        tabCount = TextView(this).apply {
            text = "1"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat(); setStroke(dp(2), C_ICE); setColor(Color.TRANSPARENT)
            }
        }
        tabBtn.addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        bottomBar.addView(tabBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav(R.drawable.ic_nav_menu) { showMenu() }
        nav(R.drawable.ic_nav_reload) { current?.let { if (it.isHome) refreshHome() else it.web.reload() } }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, dp(48)))

        // Edge drawer handle (Fullscreen / Hide all bars / Menu); logic in EdgeDrawer.kt
        fsButton = TextView(this)
        frame.addView(fsButton, FrameLayout.LayoutParams(dp(22), dp(72), Gravity.TOP or Gravity.END))
        setupEdgeHandle()

        // Floating, draggable / lockable shortcut buttons. Empty areas of this layer let touches through.
        floatLayer = FrameLayout(this)
        floatLayer.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob ->
            if (r - l != orr - ol || bt - t != ob - ot) positionFloats()
        }
        frame.addView(floatLayer, FrameLayout.LayoutParams(-1, -1))

        // Notes / Drop tracker panels live here, above the floating buttons. Empty areas let touches through to the page.
        panelLayer = FrameLayout(this)
        panelLayer.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob ->
            if (r - l != orr - ol || bt - t != ob - ot) panelLayer.post { panelRelayout.values.toList().forEach { it() } }
        }
        frame.addView(panelLayer, FrameLayout.LayoutParams(-1, -1))

        shield = TextView(this).apply {
            text = "Capturing full page…\nplease don't touch the screen"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setBackgroundColor(0xB0000000.toInt())
            isClickable = true
            visibility = View.GONE
        }
        frame.addView(shield, FrameLayout.LayoutParams(-1, -1))

        setContentView(frame)
    }

    fun applyWidth() {
        val pct = prefs.getInt("width_pct", 100)
        val align = prefs.getInt("align", 1) // 0 left, 1 center, 2 right
        val web = current?.web ?: return
        val total = container.width
        if (total <= 0) return
        val w = if (pct >= 100) ViewGroup.LayoutParams.MATCH_PARENT else total * pct / 100
        val g = when (align) { 0 -> Gravity.START; 2 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        val lp = web.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != w || lp.gravity != g) {
            lp.width = w
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = g
            web.layoutParams = lp
        }
    }

    fun applyKeepAwake() {
        if (prefs.getBoolean("keep_awake", true))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun applyOrientation() {
        requestedOrientation = when (prefs.getInt("orient", 0)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    // ---------- Chrome visibility: address bar auto-hide + full screen ----------

    fun autoHide() = prefs.getBoolean("auto_hide", true)
    fun fullscreen() = prefs.getBoolean("fullscreen", false)

    fun updateChrome() {
        val fs = fullscreen()
        val hideAll = fs && prefs.getBoolean("fs_hide_all", false)
        val barVisible = if (autoHide()) barShown else true
        urlBar.visibility = if (barVisible) View.VISIBLE else View.GONE
        strip.visibility = if (!barVisible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (hideAll) View.GONE else View.VISIBLE
        bubbleScroll.visibility = if (hideAll || !prefs.getBoolean("bubble_show", true)) View.GONE else View.VISIBLE
        fsButton.visibility = when (drawerMode()) {
            "always" -> View.VISIBLE
            "hidden" -> View.GONE
            else -> if (fs) View.VISIBLE else View.GONE
        }
        positionEdgeHandle()
        if (::bubbleHandle.isInitialized) {
            bubbleHandle.visibility = if (hideAll) View.GONE else View.VISIBLE
            bubbleArrow.text = if (prefs.getBoolean("bubble_show", true)) "▾" else "▴"
        }
        bubbleScroll.alpha = prefs.getInt("bubble_alpha", 100) / 100f
        (bubbleScroll.layoutParams as? LinearLayout.LayoutParams)?.let {
            val h = dp(bubbleTile() + 36)
            if (it.height != h) { it.height = h; bubbleScroll.layoutParams = it }
        }
    }

    fun scheduleHide() {
        handler.removeCallbacks(hideBarTask)
        if (autoHide())
            handler.postDelayed(hideBarTask, prefs.getInt("hide_delay", 3) * 1000L)
    }

    private fun showBar() { barShown = true; updateChrome(); scheduleHide() }

    private fun hideBar() {
        if (urlBar.hasFocus()) { scheduleHide(); return }
        barShown = false
        updateChrome()
    }

    private fun applySystemBars() {
        val fs = fullscreen()
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            if (c != null) {
                if (fs) {
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsets.Type.systemBars())
                } else c.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (fs)
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            else 0
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (fs)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = lp
        }
    }

    fun setFullscreen(on: Boolean) {
        prefs.edit().putBoolean("fullscreen", on).apply()
        barShown = true
        applySystemBars()
        updateChrome()
        scheduleHide()
    }

    // ---------- Bookmark bubbles ----------

    /** Rounded tile (theme colors) holding one of the custom vector icons, which are recolored to the current theme and accent. */
    fun iconTile(res: Int, sizeDp: Int, folder: Boolean = false, selected: Boolean = false): ImageView =
        ImageView(this).apply {
            setImageDrawable(AdaptiveIcon.of(context, res))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(sizeDp) * 18 / 100
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(sizeDp) * 0.24f
                setColor(C_TILE)
                setStroke(if (folder || selected) dp(2) else dp(1), if (selected) Color.WHITE else C_EDGE)
            }
        }

    private fun bubbleTile() = (44 * prefs.getInt("bubble_h", 100) / 100).coerceIn(28, 90)

    private fun dockCell(tile: View, caption: String, tileDp: Int = 44): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(tile, LinearLayout.LayoutParams(dp(tileDp), dp(tileDp)))
        addView(TextView(this@MainActivity).apply {
            text = caption.ifEmpty { " " }; textSize = 11f; setTextColor(C_MUTED)
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(dp(maxOf(58, tileDp + 10)), ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = LinearLayout.LayoutParams(dp(maxOf(58, tileDp + 10)), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), 0, dp(2), 0) }
    }

    fun bubble(text: String, color: Int, folder: Boolean = false, icon: String = ""): View {
        val res = if (icon.isNotEmpty()) BmIcons.res(icon) else null
        if (res != null) return dockCell(iconTile(res, bubbleTile(), folder), text, bubbleTile())
        val tile = TextView(this).apply {
            this.text = if (folder) "${text.take(3)}\n▾" else text.take(3)
            setTextColor(Color.WHITE)
            textSize = if (folder) 12f else 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(color)
                if (folder) setStroke(dp(2), Color.WHITE)
            }
        }
        return dockCell(tile, "", bubbleTile())
    }

    fun refreshBubbles() {
        bubbleRow.removeAllViews()
        store.items.forEach { b ->
            val v = bubble(b.label, b.color, b.isFolder, b.icon)
            v.setOnClickListener { if (b.isFolder) openFolder(b) else openBm(b) }
            v.setOnLongClickListener { beginBubbleDrag(v, b); true }
            v.setOnTouchListener { _, ev -> bubbleTouch(ev) }
            bubbleRow.addView(v)
        }
        val addTile = TextView(this).apply {
            text = "+"; textSize = 22f; setTextColor(C_ACCENT); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(C_SURFACE)
                setStroke(dp(1), C_BORDER, dp(4).toFloat(), dp(3).toFloat())
            }
        }
        val add = dockCell(addTile, "Add", bubbleTile())
        add.setOnClickListener {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Bookmark this page", "Tool (notes, drops, exit...)", "New folder")) { _, w ->
                    when (w) {
                        0 -> addCurrentBookmark()
                        1 -> editBookmark(null, newTool = true)
                        else -> editBookmark(null, newFolder = true)
                    }
                }.showT()
        }
        bubbleRow.addView(add)
    }

    // ---------- Bubble row: show / hide and drag to reorder ----------

    private fun toggleBubbleRow() {
        if (capturing) return   // resizing the page in the middle of a full-page capture would ruin it
        prefs.edit().putBoolean("bubble_show", !prefs.getBoolean("bubble_show", true)).apply()
        updateChrome()
    }

    private var dragView: View? = null
    private var dragBm: Bookmark? = null
    private var dragFrom = -1
    private var dragTarget = -1
    private var dragHover = -1          // folder under the middle of the dragged bubble, -1 = none
    private var dragGrab = 0f           // where the finger holds the bubble, relative to its centre
    private var dragStartRawX = 0f
    private var dragRawX = 0f
    private var dragMoved = false
    private var dragShift = FloatArray(0)

    private val dragScrollTask = object : Runnable {
        override fun run() {
            if (dragView == null) return
            val loc = IntArray(2)
            bubbleScroll.getLocationOnScreen(loc)
            val edge = dp(56)
            val dx = when {
                dragRawX < loc[0] + edge -> -dp(10)
                dragRawX > loc[0] + bubbleScroll.width - edge -> dp(10)
                else -> 0
            }
            if (dx != 0) { bubbleScroll.scrollBy(dx, 0); updateBubbleDrag() }
            handler.postDelayed(this, 16)
        }
    }

    /** Touch stream of one bubble. Not dragging: leave it to the view (tap, long-press). Dragging: we own it. */
    private fun bubbleTouch(ev: MotionEvent): Boolean {
        dragRawX = ev.rawX
        if (dragView == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(ev.rawX - dragStartRawX) > dp(8)) dragMoved = true
                updateBubbleDrag()
            }
            MotionEvent.ACTION_UP -> endBubbleDrag(true)
            MotionEvent.ACTION_CANCEL -> endBubbleDrag(false)
        }
        return true
    }

    private fun beginBubbleDrag(v: View, b: Bookmark) {
        val from = bubbleRow.indexOfChild(v)
        if (from < 0 || bubbleRow.childCount - 1 != store.items.size) { bookmarkMenu(b); return }
        dragView = v; dragBm = b; dragFrom = from; dragTarget = from; dragHover = -1
        dragMoved = false; dragStartRawX = dragRawX
        dragShift = FloatArray(store.items.size)
        val loc = IntArray(2)
        bubbleRow.getLocationOnScreen(loc)
        dragGrab = dragRawX - (loc[0] + v.left + v.width / 2f)
        v.isPressed = false
        v.parent?.requestDisallowInterceptTouchEvent(true)   // the row must not scroll away from under the finger
        v.translationZ = dp(12).toFloat()
        v.scaleX = 1.12f; v.scaleY = 1.12f; v.alpha = 0.9f
        handler.post(dragScrollTask)
    }

    private fun updateBubbleDrag() {
        val v = dragView ?: return
        val n = store.items.size
        if (n == 0 || bubbleRow.childCount - 1 != n) return
        val loc = IntArray(2)
        bubbleRow.getLocationOnScreen(loc)
        val half = v.width / 2f
        val first = bubbleRow.getChildAt(0).let { it.left + it.width / 2f }
        val last = bubbleRow.getChildAt(n - 1).let { it.left + it.width / 2f }
        val center = (dragRawX - loc[0] - dragGrab).coerceIn(first - half, last + half)
        v.translationX = center - (v.left + half)
        var best = 0
        var bestD = Float.MAX_VALUE
        var hover = -1
        val canDrop = dragBm?.isFolder == false
        for (i in 0 until n) {
            val c = bubbleRow.getChildAt(i)
            val d = kotlin.math.abs(center - (c.left + c.width / 2f))
            if (d < bestD) { bestD = d; best = i }
            // the middle of a folder means "move into it"; its edges mean "place next to it"
            if (canDrop && i != dragFrom && store.items[i].isFolder && d <= c.width * 0.2f) hover = i
        }
        if (hover != dragHover) {
            hoverBubble(dragHover, false); hoverBubble(hover, true); dragHover = hover
        }
        val target = if (hover >= 0) dragFrom else best
        if (target != dragTarget) { dragTarget = target; shiftBubbles() }
    }

    private fun hoverBubble(i: Int, on: Boolean) {
        if (i < 0 || i >= bubbleRow.childCount) return
        val s = if (on) 1.15f else 1f
        bubbleRow.getChildAt(i).animate().scaleX(s).scaleY(s).setDuration(100).start()
    }

    /** Slides the other bubbles aside to open a gap at the drop position. */
    private fun shiftBubbles() {
        val n = store.items.size
        if (n < 2) return
        val slot = (bubbleRow.getChildAt(1).left - bubbleRow.getChildAt(0).left).toFloat()
        for (i in 0 until n) {
            if (i == dragFrom) continue
            val s = when {
                dragTarget > dragFrom && i > dragFrom && i <= dragTarget -> -slot
                dragTarget < dragFrom && i >= dragTarget && i < dragFrom -> slot
                else -> 0f
            }
            if (dragShift[i] != s) {
                dragShift[i] = s
                bubbleRow.getChildAt(i).animate().translationX(s).setDuration(120).start()
            }
        }
    }

    private fun endBubbleDrag(drop: Boolean) {
        val b = dragBm
        val from = dragFrom; val target = dragTarget; val hover = dragHover; val moved = dragMoved
        handler.removeCallbacks(dragScrollTask)
        dragView = null; dragBm = null; dragFrom = -1; dragTarget = -1; dragHover = -1
        if (b == null) return
        val items = store.items
        when {
            !drop -> refreshBubbles()
            !moved -> { refreshBubbles(); bookmarkMenu(b) }   // a plain long-press still opens the menu
            hover >= 0 && hover < items.size && hover != from && items[hover].isFolder && !b.isFolder -> {
                val folder = items[hover]
                items.removeAt(from)
                folder.children?.add(b)
                store.save(); refreshBubbles()
                toast("Moved into ${folder.label}")
            }
            target != from && target >= 0 && target < items.size -> {
                items.add(target, items.removeAt(from))
                store.save(); refreshBubbles()
            }
            else -> refreshBubbles()
        }
    }

    // ---------- Tool bubbles (a bubble whose url is "tool:<key>") ----------

    private val TOOLS = listOf(
        "notes" to "Notes", "drops" to "Drop tracker", "spark" to "Spark calculator", "exit" to "Exit app",
        "reload" to "Reload", "screenshot" to "Screenshot", "fullscreen" to "Full screen", "find" to "Find in page",
        "desktop" to "Desktop site", "new_tab" to "New tab", "close_tab" to "Close tab",
        "top" to "Scroll to top", "bottom" to "Scroll to bottom", "tabs" to "Tabs", "menu" to "Menu",
        "history" to "History", "downloads" to "Downloads", "settings" to "Settings"
    )

    private fun toolName(k: String) = TOOLS.firstOrNull { it.first == k }?.second ?: k

    private fun bmSub(b: Bookmark): String =
        if (b.url.startsWith("tool:")) "Tool: " + toolName(b.url.removePrefix("tool:")) else hostOf(b.url).ifEmpty { b.url }

    private fun runTool(k: String) {
        when (k) {
            "history" -> showHistory()
            "downloads" -> showDownloads()
            "settings" -> openSettings()
            else -> runGestureAction(k)
        }
    }

    private fun openBm(b: Bookmark) {
        if (b.url.startsWith("tool:")) runTool(b.url.removePrefix("tool:")) else openIn(b.url, b.profile)
    }

    private fun openFolder(f: Bookmark) {
        val kids = f.children ?: return
        val names = kids.map { "${it.label}   ${bmSub(it)}" }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle(f.label)
            .setItems(names) { _, i -> openBm(kids[i]) }
            .setNeutralButton("+ This page") { _, _ -> addCurrentBookmark(f) }
            .setNegativeButton("Close", null)
            .create()
        dlg.show(); styleDlg(dlg)
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            dlg.dismiss(); bookmarkMenu(kids[i]); true
        }
    }

    private fun addCurrentBookmark(folder: Bookmark? = null) {
        val u = visibleUrl()
        if (u == null) { toast("Open a page first"); return }
        editBookmark(null, defaultFolder = folder, fixedUrl = u)
    }

    private fun bookmarkMenu(b: Bookmark) {
        val list = store.parentList(b) ?: return
        val i = list.indexOfFirst { it === b }
        val top = list === store.items
        val labels = mutableListOf<String>()
        if (!b.isFolder && !b.url.startsWith("tool:")) labels += "Replace link with current page"
        labels += "Edit"
        if (top) { labels += "Move left"; labels += "Move right" }
        labels += "Delete"
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "Replace link with current page" -> {
                        val u = visibleUrl()
                        if (u == null) toast("Open a page first") else { b.url = u; toast("Link updated") }
                    }
                    "Edit" -> editBookmark(b)
                    "Move left" -> if (i > 0) list.add(i - 1, list.removeAt(i))
                    "Move right" -> if (i < list.size - 1) list.add(i + 1, list.removeAt(i))
                    "Delete" -> list.removeAt(i)
                }
                store.save(); refreshBubbles()
            }.showT()
    }

    private fun editBookmark(
        existing: Bookmark?, newFolder: Boolean = false, defaultFolder: Bookmark? = null, fixedUrl: String? = null,
        newTool: Boolean = false
    ) {
        val isFolder = existing?.isFolder ?: newFolder
        val isTool = !isFolder && (newTool || existing?.url?.startsWith("tool:") == true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val label = EditText(this).apply { hint = "Label (optional with an icon)"; setText(existing?.label ?: ""); setSingleLine() }
        val url = EditText(this).apply {
            hint = "URL"; setText(existing?.url ?: current?.url.orEmpty()); setSingleLine()
        }
        val toolSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TOOLS.map { it.second })
            val ti = TOOLS.indexOfFirst { "tool:" + it.first == existing?.url }
            setSelection(if (ti >= 0) ti else 0)
        }
        val colorSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, COLOR_NAMES)
            val ci = COLORS.indexOf(existing?.color ?: -1)
            setSelection(if (ci >= 0) ci else 0)
        }
        val folders = store.folders()
        val placeSpin = Spinner(this).apply {
            val names = listOf("Place: bubble row") + folders.map { "Place: folder ${it.label}" }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            val parent = existing?.let { store.parentList(it) }
            val idx = when {
                existing == null && defaultFolder != null -> folders.indexOfFirst { it === defaultFolder }
                parent != null && parent !== store.items -> folders.indexOfFirst { it.children === parent }
                else -> -1
            }
            setSelection(idx + 1)
        }
        val profSpin = if (!isFolder && !isTool && profiles.items.isNotEmpty()) profileSpinner(existing?.profile.orEmpty()) else null
        var pickedIcon = existing?.icon.orEmpty()
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(4)) }
        val iconScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(iconRow) }
        lateinit var rebuildIcons: () -> Unit
        rebuildIcons = {
            iconRow.removeAllViews()
            val none = TextView(this).apply {
                text = "Aa"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(C_SURFACE)
                    setStroke(if (pickedIcon.isEmpty()) dp(2) else dp(1), if (pickedIcon.isEmpty()) Color.WHITE else C_BORDER)
                }
                setOnClickListener { pickedIcon = ""; rebuildIcons() }
            }
            iconRow.addView(none, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(6), 0) })
            BmIcons.all.forEach { (key, _, res) ->
                val t = iconTile(res, 44, selected = key == pickedIcon)
                t.setOnClickListener { pickedIcon = key; rebuildIcons() }
                iconRow.addView(t, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(6), 0) })
            }
        }
        rebuildIcons()
        box.addView(label)
        box.addView(iconScroll)
        if (fixedUrl != null) box.addView(TextView(this).apply {
            text = "Link: " + hostOf(fixedUrl).ifEmpty { fixedUrl }
            setTextColor(0xFF9E9E9E.toInt()); textSize = 13f; setPadding(0, dp(6), 0, dp(6))
        })
        if (isTool) box.addView(toolSpin) else if (!isFolder && fixedUrl == null) box.addView(url)
        box.addView(colorSpin)
        if (!isFolder) { box.addView(placeSpin); profSpin?.let { box.addView(it) } }
        AlertDialog.Builder(this)
            .setTitle(
                when {
                    existing == null && isFolder -> "Add folder"
                    existing == null && isTool -> "Add tool"
                    existing == null -> "Add bookmark"
                    isFolder -> "Edit folder"
                    isTool -> "Edit tool"
                    else -> "Edit bookmark"
                }
            )
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val toolKey = TOOLS[toolSpin.selectedItemPosition].first
                if (isTool) pickedIcon = pickedIcon.ifEmpty { BmIcons.toolKey(toolKey) }
                val l = label.text.toString().trim().take(10).ifEmpty {
                    if (isTool) toolName(toolKey).take(10) else BmIcons.short(pickedIcon).ifEmpty { "?" }
                }
                val u = if (isTool) "tool:$toolKey" else fixedUrl ?: url.text.toString().trim()
                val c = COLORS[colorSpin.selectedItemPosition]
                val target: MutableList<Bookmark> =
                    if (isFolder || placeSpin.selectedItemPosition == 0) store.items
                    else folders[placeSpin.selectedItemPosition - 1].children ?: store.items
                if (existing == null) {
                    target.add(Bookmark(l, u, c, if (isFolder) mutableListOf() else null, pickedIcon, profSpin?.let { profileIdAt(it.selectedItemPosition) } ?: ""))
                } else {
                    existing.label = l; existing.color = c; existing.icon = pickedIcon
                    if (!isFolder) {
                        existing.url = u
                        profSpin?.let { existing.profile = profileIdAt(it.selectedItemPosition) }
                        val old = store.parentList(existing)
                        if (old != null && old !== target) { old.removeAll { it === existing }; target.add(existing) }
                    }
                }
                store.save(); refreshBubbles()
            }.showT()
    }

    // ---------- Menu ----------

    fun showMenu() {
        val host = hostOf(current?.url.orEmpty())
        val desk = host.isNotEmpty() && desktopHosts().contains(host)
        val blk = host.isNotEmpty() && blockingOn(host)
        val dlg = Dialog(this, R.style.GbfSheet)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18))
            background = GradientDrawable().apply {
                val r = dp(24).toFloat()
                setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
                setColor(C_BG); setStroke(dp(1), C_BORDER)
            }
        }
        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(C_BORDER) }
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(12)) })

        class MenuItem(val icon: Int, val title: String, val sub: String?, val act: () -> Unit)
        val items = listOf(
            MenuItem(R.drawable.ic_m_link, "Go to URL", null) {
                showBar()
                urlBar.requestFocus(); urlBar.selectAll()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(urlBar, 0)
            },
            MenuItem(R.drawable.ic_m_home, "Home", null) { goHome() },
            MenuItem(R.drawable.ic_m_history, "History", null) { showHistory() },
            MenuItem(R.drawable.ic_m_desktop, "Desktop site", if (desk) "On" else "Off") { toggleDesktop(host, desk) },
            MenuItem(R.drawable.ic_m_full, "Full screen", if (fullscreen()) "On" else "Off") { setFullscreen(!fullscreen()) },
            MenuItem(R.drawable.ic_bm_width, "Page width", "${prefs.getInt("width_pct", 100)}%") { widthDialog() },
            MenuItem(R.drawable.ic_m_profile, "Profile", profiles.nameOf(current?.profile.orEmpty())) { profileMenu() },
            MenuItem(R.drawable.ic_m_find, "Find in page", null) { showFind() },
            MenuItem(R.drawable.ic_m_settings, "Settings", null) { openSettings() },
            MenuItem(R.drawable.ic_m_shield, "Ad blocking", if (blk) "On" else "Off") { toggleBlock(host, blk) },
            MenuItem(R.drawable.ic_m_notes, "Notes", null) { showNotes() },
            MenuItem(R.drawable.ic_m_spark, "Spark", null) { showSpark() },
            MenuItem(R.drawable.ic_m_drop, "Drop tracker", null) { showDrops() }
        )
        items.chunked(3).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { item ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(14), dp(4), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER)
                    }
                    setOnClickListener { dlg.dismiss(); item.act() }
                }
                cell.addView(ImageView(this).apply { setImageDrawable(AdaptiveIcon.of(context, item.icon)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
                cell.addView(TextView(this).apply {
                    text = item.title; textSize = 12f; setTextColor(C_TEXT); gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                if (item.sub != null) cell.addView(TextView(this).apply {
                    text = item.sub; textSize = 11f; setTextColor(C_ACCENT); gravity = Gravity.CENTER
                })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            repeat(3 - rowItems.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            sheet.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        }
        dlg.setContentView(sheet)
        dlg.show()
        dlg.window?.setGravity(Gravity.BOTTOM)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun toggleDesktop(host: String, currentlyOn: Boolean) {
        if (host.isEmpty()) { toast("Open a website first"); return }
        val set = HashSet(desktopHosts())
        if (currentlyOn) set.remove(host) else set.add(host)
        prefs.edit().putStringSet("desktop_hosts", set).apply()
        val t = current ?: return
        setSiteMode(t, !currentlyOn)
        t.web.reload()
        toast("Desktop site ${if (!currentlyOn) "ON" else "OFF"} for $host")
    }

    private fun toggleBlock(host: String, currentlyOn: Boolean) {
        if (host.isEmpty()) { toast("Open a website first"); return }
        if (AdBlock.isAllowed(host)) { toast("Ad blocking is never active on the game's own sites"); return }
        if (!prefs.getBoolean("block_on", true)) { toast("Ad blocking is off in Settings"); return }
        val set = HashSet(noFilterHosts())
        if (currentlyOn) set.add(host) else set.remove(host)
        prefs.edit().putStringSet("nofilter_hosts", set).apply()
        current?.web?.reload()
        toast("Ad blocking ${if (!currentlyOn) "ON" else "OFF"} for $host")
    }

    // ---------- Tabs ----------

    /** Where a dragged tab or group would land. kind 1 = next to a tab, 2 = in / next to a group, 3 = out of all groups. */
    private class DropTarget(val kind: Int, val tab: Tab?, val gid: String, val after: Boolean)

    private fun setThumb(t: Tab, bmp: Bitmap, live: Boolean) {
        val w = 360
        val h = (bmp.height * w / bmp.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        t.thumb = if (h > 420) Bitmap.createBitmap(small, 0, 0, w, 420) else small
        if (live) t.thumbTime = SystemClock.uptimeMillis()
    }

    /** True when most of the picture is near-black: what WebView.draw gives for GPU-drawn game content. */
    private fun mostlyBlack(b: Bitmap): Boolean {
        val n = 16
        val s = Bitmap.createScaledBitmap(b, n, n, true)
        var dark = 0
        for (x in 0 until n) for (y in 0 until n) {
            val p = s.getPixel(x, y)
            if (Color.red(p) < 14 && Color.green(p) < 14 && Color.blue(p) < 14) dark++
        }
        if (s !== b) s.recycle()
        return dark > n * n * 0.6
    }

    /** Quick preview of a tab (WebView.draw). It can come out black, so it never replaces a good preview with a black one. */
    private fun captureTabThumb(t: Tab?) {
        if (t == null) return
        if (t.isHome) { t.thumb = null; return }
        if (SystemClock.uptimeMillis() - t.thumbTime < 5000) return   // a live preview was just taken
        try {
            val bmp = captureWeb(t.web) ?: return
            if (t.thumb != null && mostlyBlack(bmp)) return
            setThumb(t, bmp, false)
        } catch (e: Throwable) { }
    }

    /**
     * Preview of what is really on screen, copied from the window with PixelCopy (like the full-page screenshot).
     * WebView.draw shows black for scrolled or GPU-drawn game pages. Floating buttons are hidden for a moment.
     */
    private fun captureLiveThumb(t: Tab?, done: () -> Unit) {
        val web = t?.web
        if (t == null || t.isHome || web == null || web.parent == null || web.width <= 0 || web.height <= 0 || capturing) {
            captureTabThumb(t); done(); return
        }
        val floatVis = floatLayer.visibility
        val fsVis = fsButton.visibility
        floatLayer.visibility = View.INVISIBLE
        fsButton.visibility = View.INVISIBLE
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            floatLayer.visibility = floatVis
            fsButton.visibility = fsVis
            done()
        }
        handler.postDelayed({
            try {
                val frame = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
                val loc = IntArray(2)
                web.getLocationInWindow(loc)
                val src = Rect(loc[0], loc[1], loc[0] + web.width, loc[1] + web.height)
                PixelCopy.request(window, src, frame, PixelCopy.OnPixelCopyFinishedListener { res ->
                    try {
                        if (res == PixelCopy.SUCCESS && !(t.thumb != null && mostlyBlack(frame))) setThumb(t, frame, true)
                        else captureTabThumb(t)
                    } catch (e: Throwable) { }
                    finish()
                }, handler)
            } catch (e: Throwable) {
                captureTabThumb(t)
                finish()
            }
        }, 90)
        handler.postDelayed({ finish() }, 1500)   // never leave the buttons hidden if the copy never answers
    }

    private var tabsBusy = false

    fun showTabs() {
        if (tabsBusy) return
        tabsBusy = true
        captureLiveThumb(current) {
            tabsBusy = false
            showTabsDialog()
        }
    }

    /**
     * Firefox-style tab grid with your own tab groups.
     * Long-press a tab or a group header and drag to move it (onto a tab = next to it, onto a group header = into
     * that group, onto "Ungrouped" = out of its group). Long-press and release without moving opens its menu.
     */
    private fun showTabsDialog() {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val cBg = C_BG
        val cCard = C_SURFACE
        val cAccent = C_ACCENT
        val rootView = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // top row: tab count (selected), history, downloads, new group
        val countBox = TextView(this).apply {
            textSize = 13f; setTextColor(C_TEXT); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(2), C_ICE) }
        }
        val tabsCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(countBox, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(cAccent) }
            }, LinearLayout.LayoutParams(dp(40), dp(3)).apply { topMargin = dp(10) })
        }
        fun topIcon(txt: String, onTap: () -> Unit) = TextView(this).apply {
            text = txt; textSize = 20f; setTextColor(C_TEXT); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(13)); setOnClickListener { onTap() }
        }
        var onNewGroup: () -> Unit = { }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(dp(8), dp(30), dp(8), 0)
            setBackgroundColor(C_DOCK)
            addView(tabsCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(topIcon("🕘") { d.dismiss(); showHistory() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(topIcon("⬇") { d.dismiss(); showDownloads() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(topIcon("📁") { onNewGroup() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(top, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(96)) }
        val scroll = ScrollView(this).apply { addView(content); clipToPadding = false }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(col, FrameLayout.LayoutParams(-1, -1))

        val cw = (resources.displayMetrics.widthPixels - dp(6) * 2 - dp(6) * 4) / 2

        // ---- state shared by the drag code ----
        var doRefresh: () -> Unit = { }
        val cardViews = ArrayList<Pair<View, Tab>>()
        val headerViews = ArrayList<Pair<View, String>>()   // "" = the Ungrouped header
        val rootLoc = IntArray(2)
        var dragSrc: View? = null
        var dragTab: Tab? = null
        var dragGroup: TabGroup? = null
        var ghost: ImageView? = null
        var grabX = 0f
        var grabY = 0f
        var lastX = 0f
        var lastY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        var drop: DropTarget? = null
        val barBg = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(cAccent) }
        val boxBg = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(3), cAccent) }
        val indicator = View(this).apply { visibility = View.GONE; elevation = dp(6).toFloat() }

        fun rectOf(v: View): Rect {
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            return Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
        }

        fun showIndicator(dt: DropTarget?) {
            var r: Rect? = null
            var bar = false
            if (dt != null) {
                if (dt.kind == 1) {
                    val p = cardViews.firstOrNull { it.second === dt.tab }
                    if (p != null) { r = rectOf(p.first); bar = true }
                } else {
                    val p = headerViews.firstOrNull { it.second == dt.gid }
                    if (p != null) r = rectOf(p.first)
                }
            }
            val rc = r
            if (rc == null) { indicator.visibility = View.GONE; return }
            val after = dt?.after == true
            rootView.getLocationOnScreen(rootLoc)
            val w = if (bar) dp(4) else rc.width()
            val x = if (bar) (if (after) rc.right + dp(3) else rc.left - dp(7)) else rc.left
            indicator.background = if (bar) barBg else boxBg
            indicator.layoutParams = FrameLayout.LayoutParams(w, rc.height())
            indicator.translationX = (x - rootLoc[0]).toFloat()
            indicator.translationY = (rc.top - rootLoc[1]).toFloat()
            indicator.visibility = View.VISIBLE
        }

        fun findTarget(rx: Float, ry: Float): DropTarget? {
            val x = rx.toInt()
            val y = ry.toInt()
            if (!rectOf(scroll).contains(x, y)) return null
            val pad = dp(6)
            val dt = dragTab
            val dg = dragGroup
            for ((v, t2) in cardViews) {
                if (t2 === dt) continue
                val r = rectOf(v)
                r.inset(-pad, -pad)
                if (!r.contains(x, y)) continue
                if (dt != null) return DropTarget(1, t2, t2.group, x > r.centerX())
                if (dg != null && t2.group.isNotEmpty() && t2.group != dg.id) return DropTarget(2, null, t2.group, false)
            }
            for ((v, gid) in headerViews) {
                if (dg != null && (gid.isEmpty() || gid == dg.id)) continue
                if (rectOf(v).contains(x, y)) return if (gid.isEmpty()) DropTarget(3, null, "", false) else DropTarget(2, null, gid, false)
            }
            // blank space: the section whose header is the closest one above the finger
            var best: Pair<View, String>? = null
            var bestTop = Int.MIN_VALUE
            for (p in headerViews) {
                val tp = rectOf(p.first).top
                if (tp <= y && tp > bestTop) { best = p; bestTop = tp }
            }
            val gid = best?.second ?: return null
            if (dg != null && (gid.isEmpty() || gid == dg.id)) return null
            return if (gid.isEmpty()) DropTarget(3, null, "", false) else DropTarget(2, null, gid, false)
        }

        fun applyDrop(t: Tab?, g: TabGroup?, dt: DropTarget?) {
            if (dt == null) return
            if (t != null) {
                when (dt.kind) {
                    1 -> {
                        val t2 = dt.tab ?: return
                        tabs.remove(t)
                        var i = tabs.indexOf(t2)
                        if (i < 0) i = tabs.size else if (dt.after) i++
                        tabs.add(i, t)
                        t.group = t2.group
                    }
                    2 -> {
                        tabs.remove(t)
                        t.group = dt.gid
                        val last = tabs.indexOfLast { it.group == dt.gid }
                        tabs.add(if (last >= 0) last + 1 else tabs.size, t)
                    }
                    3 -> {
                        tabs.remove(t)
                        t.group = ""
                        val last = tabs.indexOfLast { it.group.isEmpty() }
                        tabs.add(if (last >= 0) last + 1 else tabs.size, t)
                    }
                }
            } else if (g != null && dt.kind == 2) {
                val target = tabGroups.get(dt.gid) ?: return
                tabGroups.move(g, tabGroups.items.indexOf(target))
            }
            tabGroups.save()
            scheduleSession()
        }

        fun changed() { tabGroups.save(); scheduleSession(); doRefresh() }

        // ---- group dialogs and menus ----
        fun nameDialog(title: String, initial: String, onName: (String) -> Unit) {
            val input = EditText(this).apply {
                setSingleLine(); hint = "Group name"; setText(initial); setSelection(initial.length)
                setPadding(dp(24), dp(14), dp(24), dp(10))
            }
            AlertDialog.Builder(this).setTitle(title).setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val n = input.text.toString().trim().take(20)
                    if (n.isNotEmpty()) onName(n)
                }.showT()
        }

        fun newGroup(onCreated: (TabGroup) -> Unit) {
            nameDialog("New group", "") { n ->
                if (tabGroups.nameTaken(n)) toast("That name is taken")
                else { val g = tabGroups.add(n); onCreated(g) }
            }
        }
        onNewGroup = { newGroup { _ -> changed() } }

        fun groupMenu(g: TabGroup) {
            val n = tabs.count { it.group == g.id }
            AlertDialog.Builder(this).setTitle(g.name)
                .setItems(arrayOf("Rename", "Change color", "Close all tabs in group", "Delete group (keep tabs)")) { _, i ->
                    when (i) {
                        0 -> nameDialog("Rename group", g.name) { nn ->
                            if (tabGroups.nameTaken(nn, g)) toast("That name is taken") else { g.name = nn; changed() }
                        }
                        1 -> AlertDialog.Builder(this).setTitle("Color").setItems(TabGroupStore.NAMES) { _, c ->
                            g.color = TabGroupStore.PALETTE[c]; changed()
                        }.showT()
                        2 -> if (n == 0) toast("No tabs in this group")
                            else confirm("Close $n tab(s) in \"${g.name}\"?", "Close") {
                                tabs.filter { it.group == g.id }.forEach { closeTab(it) }
                                changed()
                            }
                        else -> {
                            tabs.filter { it.group == g.id }.forEach { it.group = "" }
                            tabGroups.remove(g)
                            changed()
                        }
                    }
                }.showT()
        }

        fun pickGroup(t: Tab) {
            val gs = tabGroups.items.filter { it.id != t.group }
            if (gs.isEmpty()) return
            AlertDialog.Builder(this).setTitle("Move to group")
                .setItems(gs.map { it.name }.toTypedArray()) { _, i ->
                    applyDrop(t, null, DropTarget(2, null, gs[i].id, false))
                    doRefresh()
                }.showT()
        }

        fun tabMenu(t: Tab) {
            val labels = ArrayList<String>()
            val acts = ArrayList<() -> Unit>()
            if (tabGroups.items.any { it.id != t.group }) { labels += "Move to group…"; acts += { pickGroup(t) } }
            labels += "New group with this tab…"
            acts += { newGroup { g -> applyDrop(t, null, DropTarget(2, null, g.id, false)); doRefresh() } }
            if (t.group.isNotEmpty()) { labels += "Remove from group"; acts += { applyDrop(t, null, DropTarget(3, null, "", false)); doRefresh() } }
            labels += "Close tab"
            acts += { closeTab(t); doRefresh() }
            val title = t.title.ifEmpty { if (t.isHome) "Home" else hostOf(t.url).ifEmpty { "New tab" } }
            AlertDialog.Builder(this).setTitle(title.take(40))
                .setItems(labels.toTypedArray()) { _, i -> acts[i]() }.showT()
        }

        // ---- drag and drop ----
        fun placeGhost() {
            val g = ghost ?: return
            rootView.getLocationOnScreen(rootLoc)
            g.translationX = lastX - grabX - rootLoc[0]
            g.translationY = lastY - grabY - rootLoc[1]
        }

        fun updateTarget() {
            if (!moved) { drop = null; showIndicator(null); return }
            drop = findTarget(lastX, lastY)
            showIndicator(drop)
        }

        val autoScroll = object : Runnable {
            override fun run() {
                if (dragSrc == null) return
                val sr = rectOf(scroll)
                val edge = dp(90)
                val step = dp(14)
                val dy = when {
                    lastY < sr.top + edge -> -step
                    lastY > sr.bottom - edge -> step
                    else -> 0
                }
                if (dy != 0) { scroll.scrollBy(0, dy); updateTarget() }
                handler.postDelayed(this, 16)
            }
        }

        fun startDrag(v: View, t: Tab?, g: TabGroup?) {
            if (dragSrc != null) return
            dragSrc = v; dragTab = t; dragGroup = g
            moved = false; drop = null
            startX = lastX; startY = lastY
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            grabX = lastX - loc[0]
            grabY = lastY - loc[1]
            try {
                val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.alpha = 0.88f
                iv.elevation = dp(10).toFloat()
                rootView.addView(iv, FrameLayout.LayoutParams(v.width, v.height))
                ghost = iv
            } catch (e: Throwable) { }
            v.alpha = 0.3f
            v.parent?.requestDisallowInterceptTouchEvent(true)
            placeGhost()
            handler.post(autoScroll)
        }

        fun moveDrag() {
            if (!moved && Math.hypot((lastX - startX).toDouble(), (lastY - startY).toDouble()) > dp(10)) moved = true
            placeGhost()
            updateTarget()
        }

        fun endDrag(commit: Boolean) {
            val src = dragSrc ?: return
            val t = dragTab
            val g = dragGroup
            val dt = drop
            val wasMoved = moved
            dragSrc = null; dragTab = null; dragGroup = null; drop = null; moved = false
            handler.removeCallbacks(autoScroll)
            ghost?.let { rootView.removeView(it) }
            ghost = null
            indicator.visibility = View.GONE
            src.alpha = 1f
            src.isPressed = false
            if (!commit) return
            if (!wasMoved) {
                if (t != null) tabMenu(t) else if (g != null) groupMenu(g)
                return
            }
            applyDrop(t, g, dt)
            doRefresh()
        }

        fun attachDrag(v: View, t: Tab?, g: TabGroup?) {
            v.setOnLongClickListener { startDrag(v, t, g); true }
            v.setOnTouchListener { vv, e ->
                lastX = e.rawX
                lastY = e.rawY
                if (dragSrc !== vv) false
                else {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_MOVE -> moveDrag()
                        MotionEvent.ACTION_UP -> endDrag(true)
                        MotionEvent.ACTION_CANCEL -> endDrag(false)
                    }
                    true
                }
            }
        }

        // ---- building the views ----
        fun makeCard(t: Tab): View {
            val sel = t === current
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat(); setColor(cCard)
                    setStroke(if (sel) dp(3) else dp(1), if (sel) cAccent else C_BORDER)
                }
                setOnClickListener { switchTo(t); d.dismiss() }
            }
            val title = t.title.ifEmpty { if (t.isHome) "Home" else hostOf(t.url).ifEmpty { "New tab" } }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val fav: View = if (t.favicon != null) ImageView(this).apply {
                setImageBitmap(t.favicon); scaleType = ImageView.ScaleType.FIT_CENTER
            } else TextView(this).apply {
                text = (hostOf(t.url).ifEmpty { title }).take(1).uppercase()
                textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(C_BORDER) }
            }
            head.addView(fav, LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(dp(6), 0, dp(6), 0) })
            head.addView(TextView(this).apply {
                text = title; setTextColor(C_TEXT); textSize = 13f
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // profile badge, Main included once there is more than one profile
            if (profiles.items.isNotEmpty()) head.addView(TextView(this).apply {
                text = profiles.nameOf(t.profile).take(8); textSize = 10f; setTextColor(Color.WHITE)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(profiles.colorOf(t.profile)) }
            })
            head.addView(TextView(this).apply {
                text = "✕"; textSize = 15f; setTextColor(C_TEXT)
                setPadding(dp(10), dp(8), dp(8), dp(8))
                setOnClickListener { closeTab(t); doRefresh() }
            })
            card.addView(head)
            val pic = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(C_BG)
                if (t.thumb != null) setImageBitmap(t.thumb)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
                }
                clipToOutline = true
            }
            card.addView(pic, LinearLayout.LayoutParams(-1, (cw * 1.1f).toInt()).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            attachDrag(card, t, null)
            cardViews.add(card to t)
            return card
        }

        fun makeHeader(g: TabGroup?): View {
            val color = g?.color ?: C_BORDER
            val n = if (g == null) tabs.count { it.group.isEmpty() } else tabs.count { it.group == g.id }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(4), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(cCard); setStroke(dp(1), color) }
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(color) }
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
            row.addView(TextView(this).apply {
                text = g?.name ?: "Ungrouped"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (g == null) C_MUTED else C_TEXT)
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = n.toString(); textSize = 12f; setTextColor(C_MUTED); setPadding(dp(8), 0, dp(8), 0)
            })
            if (g != null) {
                row.addView(TextView(this).apply {
                    text = if (g.collapsed) "▸" else "▾"; textSize = 16f; setTextColor(C_TEXT); setPadding(dp(4), 0, dp(4), 0)
                })
                row.addView(TextView(this).apply {
                    text = "⋮"; textSize = 20f; setTextColor(C_TEXT); setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener { groupMenu(g) }
                })
                row.setOnClickListener { g.collapsed = !g.collapsed; tabGroups.save(); doRefresh() }
                attachDrag(row, null, g)
            }
            headerViews.add(row to (g?.id ?: ""))
            return row
        }

        fun headerLp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(10), dp(6), dp(4)) }

        fun addSection(list: List<Tab>, emptyHint: String?) {
            if (list.isEmpty()) {
                if (emptyHint != null) content.addView(TextView(this).apply {
                    text = emptyHint; textSize = 12f; setTextColor(C_MUTED); setPadding(dp(16), dp(8), dp(16), dp(8))
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
            val grid = GridLayout(this).apply { columnCount = 2 }
            list.forEach { t ->
                grid.addView(makeCard(t), GridLayout.LayoutParams().apply {
                    width = cw; height = ViewGroup.LayoutParams.WRAP_CONTENT; setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            content.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun refresh() {
            tabs.forEach { if (it.group.isNotEmpty() && tabGroups.get(it.group) == null) it.group = "" }
            countBox.text = tabs.size.toString()
            content.removeAllViews(); cardViews.clear(); headerViews.clear()
            val hasGroups = tabGroups.items.isNotEmpty()
            if (hasGroups) content.addView(makeHeader(null), headerLp())
            addSection(tabs.filter { it.group.isEmpty() }, if (hasGroups) "No ungrouped tabs" else null)
            tabGroups.items.toList().forEach { g ->
                content.addView(makeHeader(g), headerLp())
                if (!g.collapsed) addSection(tabs.filter { it.group == g.id }, "Drag tabs here")
            }
        }
        doRefresh = { refresh() }
        refresh()

        val fab = TextView(this).apply {
            text = "+"; textSize = 30f; setTextColor(onColor(cAccent)); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(cAccent) }
            elevation = dp(6).toFloat()
            setOnClickListener { captureThumb(); d.dismiss(); openTab(homeUrl()) }
            setOnLongClickListener { newTabInProfile { d.dismiss() }; true }
        }
        rootView.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(28)) })
        rootView.addView(indicator, FrameLayout.LayoutParams(1, 1))

        // long-press the count: close all tabs
        tabsCell.setOnLongClickListener {
            AlertDialog.Builder(this).setItems(arrayOf("Close all tabs")) { _, _ ->
                tabs.toList().forEach { closeTab(it) }
                doRefresh()
            }.showT()
            true
        }
        d.setOnDismissListener { endDrag(false) }
        d.setContentView(rootView)
        d.show()
    }

    // ---------- History ----------

    private fun showHistory() {
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val shown = mutableListOf<HistoryItem>()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        val search = EditText(this).apply { hint = "Search history"; setSingleLine() }
        val list = ListView(this)
        var dlg: AlertDialog? = null
        val adapter = object : ArrayAdapter<HistoryItem>(this, 0, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val it = getItem(position)!!
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                col.addView(TextView(context).apply {
                    text = it.title.ifEmpty { hostOf(it.url).ifEmpty { it.url } }
                    setTextColor(Color.WHITE); textSize = 15f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                })
                col.addView(TextView(context).apply {
                    text = "${dateFmt.format(Date(it.time))} · ${it.url}"
                    setTextColor(0xFF888888.toInt()); textSize = 11f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                })
                return col
            }
        }
        fun refresh() {
            val q = search.text.toString().trim().lowercase()
            shown.clear()
            shown.addAll(history.items.filter { q.isEmpty() || it.url.lowercase().contains(q) || it.title.lowercase().contains(q) })
            adapter.notifyDataSetChanged()
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ -> load(shown[i].url); dlg?.dismiss() }
        list.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this).setItems(arrayOf("Delete entry", "Copy link", "Open in new tab")) { _, w ->
                val item = shown[i]
                when (w) {
                    0 -> { history.remove(item); refresh() }
                    1 -> copyText(item.url)
                    2 -> { openTab(item.url, profile = current?.profile.orEmpty()); dlg?.dismiss() }
                }
            }.showT()
            true
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6f).toInt()))
        refresh()
        dlg = AlertDialog.Builder(this)
            .setTitle("History")
            .setView(box)
            .setNeutralButton("Clear all") { _, _ ->
                AlertDialog.Builder(this).setMessage("Clear all history?")
                    .setPositiveButton("Clear") { _, _ -> history.clear(); toast("History cleared") }
                    .setNegativeButton("Cancel", null).showT()
            }
            .setNegativeButton("Close", null)
            .create()
        dlg?.let { it.show(); styleDlg(it) }
    }

    // ---------- Long-press menu, downloads, saving ----------

    private fun showContextMenu(tab: Tab, src: String?, link: String?) {
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        if (!src.isNullOrEmpty()) {
            labels += "Save image"; acts += { saveImage(src, tab.url) }
            labels += "Open image in new tab"; acts += { openTab(src, profile = tab.profile) }
            labels += "Copy image link"; acts += { copyText(src) }
        }
        if (!link.isNullOrEmpty()) {
            labels += "Open link in new tab"; acts += { openTab(link, profile = tab.profile) }
            labels += "Open link here"; acts += { tab.web.loadUrl(link) }
            labels += "Copy link"; acts += { copyText(link) }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.showT()
    }

    private fun cleanName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100).ifEmpty { "file" }

    /** Writes into Pictures/Gz (images) or Download/Gz (other files). Returns where it went. */
    private fun saveToStore(name: String, mime: String, image: Boolean, writer: (OutputStream) -> Unit): Saved {
        val where = if (image) "Pictures/Gz" else "Download/Gz"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, where)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(coll, values) ?: throw IOException("Could not create file")
            try {
                val out = contentResolver.openOutputStream(uri) ?: throw IOException("Could not open file")
                out.use { writer(it) }
            } catch (e: Throwable) {
                try { contentResolver.delete(uri, null, null) } catch (e2: Exception) { }   // no half-written files
                throw e
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return Saved(where, uri.toString())
        }
        val dir = File(
            getExternalFilesDir(if (image) android.os.Environment.DIRECTORY_PICTURES else android.os.Environment.DIRECTORY_DOWNLOADS),
            "Gz"
        )
        dir.mkdirs()
        val f = File(dir, name)
        try {
            FileOutputStream(f).use { writer(it) }
        } catch (e: Throwable) { f.delete(); throw e }
        return Saved(dir.path, Uri.fromFile(f).toString())
    }

    /** Adds an already finished save (image / screenshot) to the download list. */
    private fun logSaved(name: String, mime: String, sv: Saved, url: String = "", referer: String = "") {
        val item = DlItem(System.nanoTime(), name, url, referer, mime, -1, System.currentTimeMillis(), DlItem.DONE, sv.uri, sv.where, "")
        try {
            if (sv.uri.startsWith("content:")) contentResolver.query(Uri.parse(sv.uri), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) item.size = it.getLong(0)
            } else File(Uri.parse(sv.uri).path ?: "").takeIf { it.exists() }?.let { item.size = it.length() }
        } catch (e: Exception) { }
        runOnUiThread { downloads.add(item); dlChanged() }
    }

    private fun saveImage(url: String, referer: String) {
        if (!url.startsWith("http")) { toast("Can't save this image type"); return }
        toast("Saving…")
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Mobile")
                if (referer.startsWith("http")) c.setRequestProperty("Referer", referer)
                c.connectTimeout = 15000; c.readTimeout = 30000
                val mime = (c.contentType ?: "image/jpeg").substringBefore(';').trim().ifEmpty { "image/jpeg" }
                var name = cleanName(URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8"))
                if (!name.contains('.')) name += "." + (mime.substringAfter('/', "jpg").replace("jpeg", "jpg"))
                val imgMime = if (mime.startsWith("image/")) mime else "image/jpeg"
                val sv = saveToStore(name, imgMime, true) { out ->
                    c.inputStream.use { it.copyTo(out) }
                }
                logSaved(name, imgMime, sv, url, referer)
                toastUi("Saved to ${sv.where}")
            } catch (e: Exception) {
                toastUi("Save failed: ${e.message}")
            }
        }.start()
    }

    private fun handleDownload(url: String, ua: String?, cd: String?, mime: String?, referer: String, profile: String) =
        startDownload(url, ua, cd, mime, referer, null, profile)

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?, referer: String, retry: DlItem?, profile: String = "") {
        if (!url.startsWith("http")) { toast("Can't download this kind of link"); return }
        val item = retry ?: DlItem(
            System.nanoTime(), cleanName(URLUtil.guessFileName(url, cd, mime)), url, referer,
            mime ?: "", -1, System.currentTimeMillis(), DlItem.RUNNING, "", "", ""
        ).also { it.profile = profile }
        if (retry != null) {
            item.state = DlItem.RUNNING; item.done = 0; item.size = -1; item.note = ""; item.cancel = false
            item.time = System.currentTimeMillis(); item.uri = ""
            downloads.items.remove(item); downloads.items.add(0, item)
        } else downloads.add(item)
        downloads.save(); dlChanged()
        toast("Downloading ${item.name}…")
        val cookie = cookiesOf(item.profile)?.getCookie(item.url)   // read here: profile lookups belong on the UI thread
        Thread {
            var lastUi = 0L
            try {
                val c = URL(item.url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", ua ?: mobileUa)
                cookie?.let { c.setRequestProperty("Cookie", it) }
                if (item.referer.startsWith("http")) c.setRequestProperty("Referer", item.referer)
                c.connectTimeout = 15000; c.readTimeout = 60000
                val code = c.responseCode
                if (code >= 400) throw IOException("HTTP $code")
                val type = (c.contentType ?: mime ?: "application/octet-stream")
                    .substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                val name = if (retry != null) item.name else cleanName(URLUtil.guessFileName(item.url, cd, type))
                item.name = name; item.mime = type; item.size = c.contentLengthLong
                dlChanged()
                val sv = saveToStore(name, type, type.startsWith("image/")) { out ->
                    c.inputStream.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (item.cancel) throw IOException("Canceled")
                            out.write(buf, 0, n)
                            item.done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi > 400) { lastUi = now; dlChanged() }
                        }
                    }
                }
                item.uri = sv.uri; item.where = sv.where; item.state = DlItem.DONE
                if (item.size < 0) item.size = item.done
                toastUi("Saved $name to ${sv.where}")
            } catch (e: Throwable) {
                if (item.cancel) { item.state = DlItem.CANCELED; item.note = "Canceled" }
                else {
                    item.state = DlItem.FAILED; item.note = e.message ?: "Error"
                    toastUi("Download failed: ${item.note}")
                }
            }
            runOnUiThread { downloads.save(); dlChanged() }
        }.start()
    }

    private fun dlChanged() { handler.post { dlListener?.invoke() } }

    private fun fmtSize(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    }

    private fun openDl(dl: DlItem) {
        if (!dl.uri.startsWith("content:")) { toast("Saved in ${dl.where}"); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(dl.uri), dl.mime.ifEmpty { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            toast("No app can open this file")
        } catch (e: Exception) {
            toast("File not found (deleted?)")
        }
    }

    private fun shareDl(dl: DlItem) {
        if (!dl.uri.startsWith("content:")) { toast("Sharing needs Android 10 or newer"); return }
        try {
            val send = Intent(Intent.ACTION_SEND).setType(dl.mime.ifEmpty { "*/*" })
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(dl.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) { toast("Can't share: ${e.message}") }
    }

    private fun deleteDlFile(dl: DlItem) {
        try {
            if (dl.uri.startsWith("content:")) contentResolver.delete(Uri.parse(dl.uri), null, null)
            else File(Uri.parse(dl.uri).path ?: "").delete()
        } catch (e: Exception) { toast("Couldn't delete: ${e.message}") }
        downloads.remove(dl); dlChanged()
    }

    private fun dlMenu(dl: DlItem) {
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        if (dl.state == DlItem.DONE) {
            labels += "Open"; acts += { openDl(dl) }
            labels += "Share"; acts += { shareDl(dl) }
        }
        if (dl.state == DlItem.FAILED || dl.state == DlItem.CANCELED) {
            labels += "Retry"; acts += { startDownload(dl.url, null, null, dl.mime.ifEmpty { null }, dl.referer, dl) }
        }
        if (dl.url.startsWith("http")) { labels += "Copy link"; acts += { copyText(dl.url) } }
        if (dl.state != DlItem.RUNNING) { labels += "Remove from list"; acts += { downloads.remove(dl); dlChanged() } }
        if (dl.state == DlItem.DONE) {
            labels += "Delete file"
            acts += {
                AlertDialog.Builder(this).setMessage("Delete ${dl.name} from your phone?")
                    .setPositiveButton("Delete") { _, _ -> deleteDlFile(dl) }
                    .setNegativeButton("Cancel", null).showT()
            }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this).setTitle(dl.name).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.showT()
    }

    fun showDownloads() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(8), dp(4))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_back); setColorFilter(C_ICE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(TextView(this).apply {
            text = "Downloads"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C_TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "Clear"; textSize = 14f; setTextColor(C_ACCENT)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Remove finished downloads from this list? The files stay on your phone.")
                    .setPositiveButton("Clear") { _, _ -> downloads.clearFinished(); dlChanged() }
                    .setNegativeButton("Cancel", null).showT()
            }
        })
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(C_BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(24)) }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        fun rebuild() {
            val y = scroll.scrollY
            list.removeAllViews()
            if (downloads.items.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No downloads yet.\nFiles you download or images you save appear here."
                    textSize = 14f; setTextColor(C_MUTED); gravity = Gravity.CENTER
                    setPadding(dp(20), dp(60), dp(20), 0)
                })
                return
            }
            downloads.items.toList().forEach { dl ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(8), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER)
                    }
                    setOnClickListener { if (dl.state == DlItem.DONE) openDl(dl) else dlMenu(dl) }
                    setOnLongClickListener { dlMenu(dl); true }
                }
                row.addView(TextView(this).apply {
                    text = dl.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }
                    textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C_ICE); gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat(); setColor(C_TILE); setStroke(dp(1), C_EDGE)
                    }
                }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(0, 0, dp(12), 0) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = dl.name; textSize = 15f; setTextColor(C_TEXT)
                    setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                })
                val date = fmt.format(Date(dl.time))
                val sub = when (dl.state) {
                    DlItem.RUNNING ->
                        if (dl.size > 0) "${fmtSize(dl.done)} / ${fmtSize(dl.size)} · ${(dl.done * 100 / dl.size).coerceIn(0, 100)}%"
                        else fmtSize(dl.done)
                    DlItem.DONE -> "${fmtSize(dl.size)} · $date · ${dl.where}"
                    DlItem.CANCELED -> "Canceled · $date"
                    else -> "Failed: ${dl.note} · $date"
                }
                col.addView(TextView(this).apply {
                    text = sub; textSize = 12f; setPadding(0, dp(2), 0, 0)
                    setTextColor(if (dl.state == DlItem.FAILED) 0xFFF0707A.toInt() else C_MUTED)
                    setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                })
                if (dl.state == DlItem.RUNNING) {
                    col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        isIndeterminate = dl.size <= 0
                        progress = if (dl.size > 0) (dl.done * 100 / dl.size).toInt().coerceIn(0, 100) else 0
                        progressTintList = android.content.res.ColorStateList.valueOf(C_ACCENT)
                        indeterminateTintList = android.content.res.ColorStateList.valueOf(C_ACCENT)
                    }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
                }
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = if (dl.state == DlItem.RUNNING) "✕" else "⋮"
                    textSize = 18f; setTextColor(C_MUTED); gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener { _ -> if (dl.state == DlItem.RUNNING) dl.cancel = true else dlMenu(dl) }
                })
                list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
            }
            scroll.post { scroll.scrollTo(0, y) }
        }
        rebuild()
        dlListener = { rebuild() }
        d.setOnDismissListener { dlListener = null }
        d.setContentView(root)
        d.show()
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // ---------- Screenshots ----------

    fun showScreenshotMenu() {
        val t = current
        if (t == null || t.isHome) { toast("Open a page first"); return }
        AlertDialog.Builder(this)
            .setItems(arrayOf("Screenshot", "Full page", "Partial (crop)")) { _, w ->
                if (w == 1) { fullPageScreenshot(); return@setItems }
                handler.postDelayed({
                    val bmp = captureWeb(t.web)
                    if (bmp == null) toast("Screenshot failed")
                    else if (w == 0) saveBitmap(bmp) else showCrop(bmp)
                }, 150)
            }.showT()
    }

    private fun saveBitmap(bmp: Bitmap) {
        val name = "Gz_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        Thread {
            try {
                val sv = saveToStore(name, "image/png", true) { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                logSaved(name, "image/png", sv)
                toastUi("Saved to ${sv.where}")
                showShotPreview(shotThumb(bmp))
            } catch (e: Exception) {
                toastUi("Save failed: ${e.message}")
            }
        }.start()
    }

    private fun showCrop(bmp: Bitmap) {
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        col.addView(TextView(this).apply {
            text = "Drag corners/edges to resize, inside to move"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        val cv = CropView(this, bmp)
        col.addView(cv, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(label: String, a: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { a() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) }
        b("Cancel") { dlg.dismiss() }
        b("Save crop") { saveBitmap(cv.cropped()); dlg.dismiss() }
        col.addView(row)
        dlg.setContentView(col)
        dlg?.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // ---------- Profiles ----------

    fun profilesSupported(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    } catch (e: Throwable) { false }

    /** The WebView profile behind one of our profile ids (null for Main, unknown ids, or an old WebView). */
    fun webProfile(id: String): androidx.webkit.Profile? = try {
        if (id.isEmpty() || !profilesSupported() || profiles.get(id) == null) null
        else androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(id)
    } catch (e: Throwable) { null }

    /** Cookie jar of a profile ("" = Main). Null when that profile is unavailable, so nothing ever leaks across profiles. */
    fun cookiesOf(id: String): CookieManager? =
        if (id.isEmpty()) CookieManager.getInstance() else try { webProfile(id)?.cookieManager } catch (e: Throwable) { null }

    private fun flushCookies() {
        CookieManager.getInstance().flush()
        profiles.items.forEach { try { cookiesOf(it.id)?.flush() } catch (e: Throwable) { } }
    }

    /** A new WebView living in [profile] ("" = Main). Null when the profile can't be applied. */
    private fun newWebView(profile: String): WebView? {
        val web = WebView(this)
        if (profile.isNotEmpty()) {
            try {
                if (!profilesSupported()) throw IllegalStateException("profiles not supported")
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profile)
                WebViewCompat.setProfile(web, profile)   // must happen before anything else touches the WebView
            } catch (e: Throwable) {
                try { web.destroy() } catch (x: Throwable) { }
                return null
            }
        }
        return web
    }

    /** Deleting a profile fails while a WebView still uses it, so the wipe is retried at the next start. */
    private fun tryWipe(id: String): Boolean = try {
        if (profilesSupported()) androidx.webkit.ProfileStore.getInstance().deleteProfile(id)
        true
    } catch (e: Throwable) { false }

    private fun wipePendingProfiles() {
        val left = HashSet(prefs.getStringSet("pending_wipe", emptySet()) ?: emptySet<String>())
        if (left.isEmpty()) return
        left.removeAll(left.filter { tryWipe(it) }.toSet())
        prefs.edit().putStringSet("pending_wipe", left).apply()
    }

    /** Opens [url] in the profile a bookmark / shortcut is bound to ("" = whatever profile the current tab is in). */
    private fun openIn(url: String, bound: String) {
        val want: String? = when {
            bound.isEmpty() -> null
            bound == "main" -> ""
            profiles.get(bound) != null -> bound
            else -> null   // that profile was deleted
        }
        if (want == null || want == (current?.profile ?: "")) load(url)
        else { captureThumb(); openTab(url, profile = want) }
    }

    private fun profileSpinner(selected: String): Spinner {
        val ids = listOf("", "main") + profiles.items.map { it.id }
        val names = listOf("Profile: current tab", "Profile: ${profiles.mainName}") + profiles.items.map { "Profile: ${it.name}" }
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(ids.indexOf(selected).coerceAtLeast(0))
        }
    }

    private fun profileIdAt(pos: Int): String = (listOf("", "main") + profiles.items.map { it.id }).getOrElse(pos) { "" }

    /** Long-press on + in the tab list. */
    private fun newTabInProfile(afterPick: () -> Unit) {
        val ids = listOf("") + profiles.items.map { it.id }
        val names = listOf(profiles.mainName) + profiles.items.map { it.name } + "+ New profile…"
        AlertDialog.Builder(this).setTitle("New tab in…")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == names.size - 1) { addProfile(); return@setItems }
                captureThumb(); afterPick(); openTab(homeUrl(), profile = ids[i])
            }.showT()
    }

    private fun profileMenu() {
        if (!profilesSupported()) {
            toast("Profiles need a newer Android System WebView. Update it in the Play Store")
            return
        }
        val cur = current?.profile.orEmpty()
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        if (cur.isNotEmpty()) { ids += ""; names += "Reopen this page in ${profiles.mainName}" }
        profiles.items.filter { it.id != cur }.forEach { ids += it.id; names += "Reopen this page in ${it.name}" }
        names += "Manage profiles…"
        AlertDialog.Builder(this).setTitle("Profile: ${profiles.nameOf(cur)}")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == names.size - 1) openSettings("profiles") else reopenIn(ids[i])
            }.showT()
    }

    /** A tab keeps its profile for life, so "move" = open the same page in the other profile and close this tab. */
    private fun reopenIn(profile: String) {
        val t = current ?: return
        val url = if (t.isHome) homeUrl() else t.url.ifEmpty { homeUrl() }
        captureThumb()
        openTab(url, profile = profile)
        closeTab(t)
    }

    fun addProfile() {
        if (!profilesSupported()) {
            toast("Profiles need a newer Android System WebView. Update it in the Play Store")
            return
        }
        val input = EditText(this).apply {
            setSingleLine(); hint = "Name, e.g. Alt"
            setPadding(dp(24), dp(14), dp(24), dp(10))
        }
        AlertDialog.Builder(this).setTitle("New profile").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim().take(16)
                if (n.isEmpty()) return@setPositiveButton
                if (profiles.nameTaken(n)) { toast("That name is taken"); return@setPositiveButton }
                profiles.add(n)
                toast("Profile \"$n\" created. Long-press + in the tab list to open a tab in it")
                refreshSettings()
            }.showT()
    }

    fun profileOptions(p: BrowserProfile) {
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(arrayOf("Rename", "Change color", "Delete and wipe its logins")) { _, w ->
                if (w == 1) pickColor(ProfileColors.names, ProfileColors.colors) { p.color = it; profiles.save(); refreshSettings() }
                else if (w == 0) {
                    val input = EditText(this).apply {
                        setSingleLine(); setText(p.name)
                        setPadding(dp(24), dp(14), dp(24), dp(10))
                    }
                    AlertDialog.Builder(this).setTitle("Rename profile").setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save") { _, _ ->
                            val n = input.text.toString().trim().take(16)
                            if (n.isEmpty()) return@setPositiveButton
                            if (profiles.nameTaken(n, p.id)) { toast("That name is taken"); return@setPositiveButton }
                            p.name = n; profiles.save(); refreshSettings()
                        }.showT()
                } else confirm("Delete profile \"${p.name}\"? Its tabs are closed and every login in it is wiped.", "Delete") {
                    tabs.filter { it.profile == p.id }.forEach { closeTab(it) }
                    profiles.remove(p)
                    if (tryWipe(p.id)) toast("Profile deleted")
                    else {
                        val left = HashSet(prefs.getStringSet("pending_wipe", emptySet()) ?: emptySet<String>())
                        left.add(p.id)
                        prefs.edit().putStringSet("pending_wipe", left).apply()
                        toast("Profile deleted. Its logins are wiped the next time the app starts")
                    }
                    refreshSettings()
                }
            }.showT()
    }

    /** Main is renamable and recolorable like any other profile. */
    fun mainOptions() {
        AlertDialog.Builder(this).setTitle(profiles.mainName)
            .setItems(arrayOf("Rename", "Change color")) { _, w ->
                if (w == 1) pickColor(ProfileColors.mainNames, ProfileColors.mainColors) { profiles.recolorMain(it); refreshSettings() }
                else {
                    val input = EditText(this).apply {
                        setSingleLine(); setText(profiles.mainName)
                        setPadding(dp(24), dp(14), dp(24), dp(10))
                    }
                    AlertDialog.Builder(this).setTitle("Rename Main").setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save") { _, _ ->
                            val n = input.text.toString().trim().take(16)
                            if (n.isEmpty()) return@setPositiveButton
                            if (profiles.nameTaken(n, "")) { toast("That name is taken"); return@setPositiveButton }
                            profiles.renameMain(n); refreshSettings()
                        }.showT()
                }
            }.showT()
    }

    private fun pickColor(names: Array<String>, colors: IntArray, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle("Color")
            .setItems(names) { _, i -> onPick(colors[i]) }.showT()
    }

    // ---------- Gestures ----------

    val gestures by lazy {
        GestureEngine(
            density = resources.displayMetrics.density,
            widthPx = { window.decorView.width },
            heightPx = { window.decorView.height },
            splitFrac = { prefs.getInt("gest_split", 50).coerceIn(25, 75) / 100f },
            edgePx = { dp(prefs.getInt("gest_edge_dp", 20)) },
            barRect = { rectOf(if (::urlBar.isInitialized) urlBar else null) },
            dockRect = { rectOf(if (::bottomBar.isInitialized) bottomBar else null) },
            mapped = { gestureAction(it) != "none" },
            fire = { key ->
                window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                runGestureAction(gestureAction(key))
            }
        )
    }

    private fun rectOf(v: View?): Rect? {
        if (v == null || !v.isShown || v.width == 0) return null
        val l = IntArray(2)
        v.getLocationInWindow(l)
        return Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
    }

    fun gesturesOn() = ::prefs.isInitialized && prefs.getBoolean("gestures_on", false)   // off until you switch it on

    fun gestureAction(key: String): String =
        prefs.getString("gest_$key", null)
            ?: when {   // the old single left / right edge swipes carry over to both halves
                key.startsWith("edge_left_") -> prefs.getString("gest_edge_left", null)
                key.startsWith("edge_right_") -> prefs.getString("gest_edge_right", null)
                else -> null
            }
            ?: GestureMap.GESTURES.firstOrNull { it.key == key }?.def ?: "none"

    fun gestureOffHosts(): Set<String> = prefs.getStringSet("gest_off_hosts", emptySet()) ?: emptySet()

    private fun gesturesEnabledNow(): Boolean {
        if (!gesturesOn()) return false
        val h = hostOf(current?.url.orEmpty())
        return h.isEmpty() || !gestureOffHosts().contains(h)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val onBubbles = ::bubbleScroll.isInitialized && rectOf(bubbleScroll)?.contains(ev.x.toInt(), ev.y.toInt()) == true
            gestActive = gesturesEnabledNow() && !onBubbles
        }
        if (gestActive) {
            when (gestures.onTouch(ev)) {
                GestureEngine.CLAIM -> {
                    // a gesture was recognised: the page gets a cancel for what it already saw
                    val c = MotionEvent.obtain(ev)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c)
                    c.recycle()
                    return true
                }
                GestureEngine.EAT -> return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Edge swipes would be taken by Android's own back gesture: reserve a strip on both edges, mid-screen. */
    fun applyGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29 || !::prefs.isInitialized) return
        val d = window.decorView
        val w = d.width; val h = d.height
        if (w == 0 || h == 0) return
        val rects = ArrayList<Rect>()
        if (gesturesOn()) {
            val e = maxOf(dp(prefs.getInt("gest_edge_dp", 20)), dp(32))
            val tall = dp(200)
            val split = (h * prefs.getInt("gest_split", 50).coerceIn(25, 75) / 100f).toInt()
            val top = (split - tall / 2).coerceIn(0, (h - tall).coerceAtLeast(0))
            rects.add(Rect(0, top, e, top + tall))
            rects.add(Rect(w - e, top, w, top + tall))
        }
        d.systemGestureExclusionRects = rects
    }

    private fun runGestureAction(action: String) {
        if (action.startsWith(GestureMap.BM)) {
            val u = GestureMap.bmUrl(action)
            if (u.startsWith("tool:")) runTool(u.removePrefix("tool:")) else if (u.isNotBlank()) load(u)
            return
        }
        val t = current
        when (action) {
            "back" -> if (t != null && ((t.isHome && t.url.isNotEmpty()) || (!t.isHome && t.canGoBack))) goBack() else toast("Nothing to go back to")
            "forward" -> if (t != null && !t.isHome) t.web.goForward()
            "reload" -> if (t != null) { if (t.isHome) refreshHome() else t.web.reload() }
            "home" -> goHome()
            "next_tab", "prev_tab" -> {
                val cur = t ?: return
                val i = tabs.indexOf(cur)
                if (tabs.size > 1 && i >= 0) {
                    val step = if (action == "next_tab") 1 else tabs.size - 1
                    switchTo(tabs[(i + step) % tabs.size])
                } else toast("Only one tab open")
            }
            "new_tab" -> { captureThumb(); openTab(homeUrl()) }
            "close_tab" -> if (t != null) closeTab(t)
            "tabs" -> showTabs()
            "menu" -> showMenu()
            "drawer" -> showEdgeDrawer()
            "top", "bottom" -> scrollPage(action == "top")
            "fullscreen" -> setFullscreen(!fullscreen())
            "desktop" -> {
                val h = hostOf(t?.url.orEmpty())
                toggleDesktop(h, h.isNotEmpty() && desktopHosts().contains(h))
            }
            "screenshot" -> showScreenshotMenu()
            "find" -> showFind()
            "notes" -> togglePanel("notes") { showNotes() }
            "drops" -> togglePanel("drops") { showDrops() }
            "spark" -> showSpark()
            "exit" -> if (prefs.getBoolean("back_guard", true)) confirmExit() else finishAndRemoveTask()
        }
    }

    fun confirmExit() {
        AlertDialog.Builder(this).setMessage("Exit Gz Browser?")
            .setPositiveButton("Exit") { _, _ -> finishAndRemoveTask() }
            .setNegativeButton("Cancel", null).showT()
    }

    private fun scrollPage(toTop: Boolean) {
        val t = current ?: return
        if (t.isHome) { homeView.fullScroll(if (toTop) View.FOCUS_UP else View.FOCUS_DOWN); return }
        if (toTop) t.web.pageUp(true) else t.web.pageDown(true)
    }

    fun showFind() {
        val t = current ?: return
        if (t.isHome) { toast("Open a page first"); return }
        val input = EditText(this).apply {
            setSingleLine(); hint = "Find in page"; setText(lastFind)
            setPadding(dp(24), dp(14), dp(24), dp(10))
        }
        fun go(forward: Boolean) {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            if (q != lastFind || findTab !== t) { lastFind = q; findTab = t; t.web.findAllAsync(q) }
            else t.web.findNext(forward)
        }
        AlertDialog.Builder(this).setTitle("Find in page").setView(input)
            .setPositiveButton("Find / next") { _, _ -> go(true) }
            .setNeutralButton("Previous") { _, _ -> go(false) }
            .setNegativeButton("Close") { _, _ -> t.web.clearMatches(); findTab = null }
            .showT()
    }

    fun chooseGestureAction(g: GestureMap.G) {
        val curKey = gestureAction(g.key)
        val names = GestureMap.ACTIONS.map { it.second } + "Open a bookmark / link…"
        val cur = if (curKey.startsWith(GestureMap.BM)) GestureMap.ACTIONS.size
                  else GestureMap.ACTIONS.indexOfFirst { it.first == curKey }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(g.label)
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, i ->
                d.dismiss()
                if (i < GestureMap.ACTIONS.size) {
                    prefs.edit().putString("gest_${g.key}", GestureMap.ACTIONS[i].first).apply()
                    ensureDrawerReachable()
                    refreshSettings()
                    return@setSingleChoiceItems
                }
                val cands = linkCandidates()
                if (cands.isEmpty()) { toast("Add a bookmark bubble first (or open a page)"); return@setSingleChoiceItems }
                AlertDialog.Builder(this).setTitle("Open which link?")
                    .setItems(cands.map { it.label }.toTypedArray()) { _, j ->
                        val c = cands[j]
                        prefs.edit().putString("gest_${g.key}", GestureMap.bmAction(c.label, c.url)).apply()
                        ensureDrawerReachable()
                        refreshSettings()
                    }.showT()
            }.showT()
    }

    fun chooseEdgeWidth() {
        val opts = intArrayOf(12, 16, 20, 28, 36, 48)
        val cur = opts.indexOf(prefs.getInt("gest_edge_dp", 20)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Edge swipe width")
            .setSingleChoiceItems(opts.map { "$it dp" }.toTypedArray(), cur) { d, i ->
                prefs.edit().putInt("gest_edge_dp", opts[i]).apply()
                applyGestureExclusion(); d.dismiss(); refreshSettings()
            }.showT()
    }

    fun toggleSiteGestures() {
        val h = hostOf(current?.url.orEmpty())
        if (h.isEmpty()) { toast("Open a website first"); return }
        val set = HashSet(gestureOffHosts())
        if (!set.remove(h)) set.add(h)
        prefs.edit().putStringSet("gest_off_hosts", set).apply()
        toast("Gestures ${if (set.contains(h)) "OFF" else "ON"} for $h")
        refreshSettings()
    }

    fun resetGestures() {
        val e = prefs.edit()
        GestureMap.GESTURES.forEach { e.remove("gest_${it.key}") }
        e.remove("gest_edge_left"); e.remove("gest_edge_right")
        e.apply()
        ensureDrawerReachable()
        refreshSettings()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCb
            fileCb = null
            val picked = if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            cb?.onReceiveValue(picked)   // null = cancelled; must always be answered or the page's picker stays stuck
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == REQ_EXPORT) {
                contentResolver.openOutputStream(uri)?.use { it.write(store.toJson().toByteArray()) }
                toast("Bookmarks exported")
            } else if (requestCode == REQ_IMPORT) {
                val s = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }.orEmpty()
                importBookmarks(s)
            }
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    fun goBack() {
        val t = current ?: return
        if (t.isHome && t.url.isNotEmpty()) setHome(t, false)
        else if (!t.isHome && t.canGoBack) t.web.goBack()
    }

    // ---------- Theme ----------

    fun presetIndex() = prefs.getInt("theme_preset", 0).coerceIn(0, Theme.presets.size - 1)

    /** Loads the chosen preset (+ optional custom accent) into the C_* colors. */
    private fun applyTheme() {
        val p = Theme.presets[presetIndex()]
        C_BG = p.bg; C_SURFACE = p.surface; C_DOCK = p.dock; C_BORDER = p.border
        C_ICE = p.ice; C_MUTED = p.muted; C_TEXT = p.text
        C_TILE = p.tile; C_EDGE = p.edge; C_DIV = p.div; C_TILE2 = p.tile2
        val custom = prefs.getInt("theme_accent", 0)
        C_ACCENT = if (custom == 0) p.accent else custom
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C_BG))
    }

    /** Readable text/icon color on top of [c]. */
    private fun onColor(c: Int): Int = if (Color.luminance(c) > 0.55f) 0xFF101820.toInt() else Color.WHITE

    /** Rebuilds the whole UI with the new colors. Pages (WebViews) stay loaded, nothing reloads. */
    private fun reTheme() {
        applyTheme()
        val cur = current
        if (cur != null) (cur.web.parent as? ViewGroup)?.removeView(cur.web)
        tabs.forEach { it.web.setBackgroundColor(C_BG) }
        buildUi()
        if (cur != null) {
            container.addView(cur.web, 0, FrameLayout.LayoutParams(-1, -1))
            applyWidth()
            applyHomeState(cur)
        }
        refreshBubbles(); refreshFloats(); updateTabCount()
        applySystemBars(); updateChrome(); scheduleHide()
        refreshSettings()
    }

    private fun circle(color: Int, sizeDp: Int, ring: Int = C_ICE, ringDp: Int = 2): View = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(ringDp), ring) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun accentDot(color: Int): View = circle(color, 26)

    fun themeDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        var dlg: AlertDialog? = null
        Theme.presets.forEachIndexed { i, p ->
            val sel = i == presetIndex()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(p.bg)
                    setStroke(if (sel) dp(3) else dp(1), if (sel) C_ACCENT else p.border)
                }
                setOnClickListener { prefs.edit().putInt("theme_preset", i).apply(); dlg?.dismiss(); reTheme() }
            }
            row.addView(TextView(this).apply { text = p.name; textSize = 16f; setTextColor(p.text) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            for (c in intArrayOf(p.surface, p.border, p.accent)) {
                row.addView(circle(c, 20, p.ice, 1).also { (it.layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), 0, 0, 0) })
            }
            box.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        dlg = AlertDialog.Builder(this).setTitle("Color theme")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null).create()
        dlg.show(); styleDlg(dlg)
    }

    fun accentDialog() {
        val cur = prefs.getInt("theme_accent", 0)
        var picked = cur
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val hex = EditText(this).apply {
            hint = "#RRGGBB"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            if (cur != 0) setText(String.format("#%06X", cur and 0xFFFFFF))
        }
        val dots = mutableListOf<Pair<View, Int>>()
        fun mark() = dots.forEach { (v, c) ->
            (v.background as GradientDrawable).setStroke(if (c == picked) dp(3) else dp(1), if (c == picked) Color.WHITE else C_BORDER)
        }
        Theme.swatches.chunked(5).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            group.forEach { sw ->
                val dot = circle(sw.color, 42, C_BORDER, 1)
                (dot.layoutParams as LinearLayout.LayoutParams).setMargins(dp(6), dp(6), dp(6), dp(6))
                dot.setOnClickListener { picked = sw.color; hex.setText(String.format("#%06X", sw.color and 0xFFFFFF)); mark() }
                dots += dot to sw.color
                row.addView(dot)
            }
            box.addView(row)
        }
        mark()
        box.addView(hex, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        AlertDialog.Builder(this).setTitle("Accent color").setView(box)
            .setNeutralButton("Theme default") { _, _ -> prefs.edit().putInt("theme_accent", 0).apply(); reTheme() }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val t = hex.text.toString().trim().removePrefix("#")
                val v = if (t.length == 6) t.toLongOrNull(16) else null
                if (v == null) toast("Enter a color like #3FB7E8")
                else { prefs.edit().putInt("theme_accent", 0xFF000000.toInt() or v.toInt()).apply(); reTheme() }
            }.showT()
    }

    /** Dialogs built with AlertDialog get the theme colors (the XML style is fixed). */
    fun styleDlg(d: AlertDialog) {
        d.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(20).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER)
        })
        for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL))
            d.getButton(b)?.setTextColor(C_ACCENT)
    }

    fun AlertDialog.Builder.showT(): AlertDialog = create().also { it.show(); styleDlg(it) }

    // ---------- Tab restore ----------

    private fun scheduleSession() { handler.removeCallbacks(sessionTask); handler.postDelayed(sessionTask, 1500) }

    private fun saveSession(withThumbs: Boolean = false) {
        if (!::prefs.isInitialized || tabs.isEmpty()) return
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("u", t.url).put("t", t.title).put("h", t.isHome).put("p", t.profile).put("g", t.group))
        }
        val o = JSONObject().put("cur", tabs.indexOf(current).coerceAtLeast(0)).put("tabs", arr)
        prefs.edit().putString("session", o.toString()).apply()
        if (withThumbs) saveThumbs()
    }

    private fun saveThumbs() {
        val dir = File(cacheDir, "tabthumbs")
        val snap = tabs.map { it.id to it.thumb }
        Thread {
            try {
                dir.mkdirs()
                val keep = snap.map { "${it.first}.jpg" }.toSet()
                dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
                for ((id, bmp) in snap) {
                    if (bmp == null) continue
                    FileOutputStream(File(dir, "$id.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                }
            } catch (e: Throwable) { }
        }.start()
    }

    /** Reopens the tabs of the last session. Only the visible tab loads right away. */
    private fun restoreSession(): Boolean {
        if (!prefs.getBoolean("restore_tabs", true)) return false
        try {
            val o = JSONObject(prefs.getString("session", null) ?: return false)
            val arr = o.getJSONArray("tabs")
            val dir = File(cacheDir, "tabthumbs")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val u = e.optString("u")
                val pf = e.optString("p").let { if (it.isNotEmpty() && profiles.get(it) == null) "" else it }
                val t = if (u.isEmpty()) openTab(HOME_PAGE, show = false, profile = pf) else openTab(u, show = false, lazy = true, profile = pf)
                if (u.isNotEmpty() && e.optBoolean("h")) t.isHome = true   // home screen shown over a loaded page
                t.id = e.optLong("id", t.id)
                t.group = e.optString("g").let { if (it.isNotEmpty() && tabGroups.get(it) == null) "" else it }
                t.title = e.optString("t")
                val f = File(dir, "${t.id}.jpg")
                if (f.exists()) t.thumb = BitmapFactory.decodeFile(f.path)
            }
            if (tabs.isEmpty()) return false
            switchTo(tabs[o.optInt("cur").coerceIn(0, tabs.size - 1)])
            return true
        } catch (e: Exception) {
            if (tabs.isNotEmpty()) { switchTo(tabs[0]); return true }
            return false
        }
    }

    private fun restartApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName)!!
        startActivity(Intent.makeRestartActivityTask(i.component))
        Process.killProcess(Process.myPid())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (edgeDrawer != null) { dismissEdgeDrawer(); return }
        val topPanel = panelRelayout.keys.lastOrNull()
        if (topPanel != null) { openPanels[topPanel]?.dismiss(); return }
        val t = current
        if (t != null && t.isHome && t.url.isNotEmpty()) { setHome(t, false); return }
        if (t != null && !t.isHome && t.canGoBack) { t.web.goBack(); return }
        if (t != null && tabs.size > 1) { closeTab(t); return }
        if (prefs.getBoolean("back_guard", true)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBack > 2000) { lastBack = now; toast("Press back again to exit"); return }
        }
        super.onBackPressed()
    }


    // ---------- Home screen ----------

    private fun roundClip(v: View, radiusDp: Int, oval: Boolean = false) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (oval) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; setTextColor(C_MUTED); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding(0, dp(8), 0, dp(12))
    }

    fun refreshHome() {
        if (!::homeBox.isInitialized) return
        homeBox.removeAllViews()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        val logo = ImageView(this).apply {
            val bm = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_fg)
            val cw = bm.width * 2 / 3
            setImageBitmap(Bitmap.createBitmap(bm, (bm.width - cw) / 2, (bm.height - cw) / 2, cw, cw))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        roundClip(logo, 0, oval = true)
        head.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "Gz Browser"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C_TEXT)
        })
        titles.addView(TextView(this).apply { text = "Game browser"; textSize = 12f; setTextColor(C_MUTED) })
        head.addView(titles)
        homeBox.addView(head)

        // Shortcuts grid (4 per row)
        homeBox.addView(sectionTitle("Shortcuts"))
        val tiles = mutableListOf<View>()
        shortcuts.items.forEach { sc ->
            val host = hostOf(sc.url).ifEmpty { null }
            tiles += tile(sc.name, sc.name.take(1).uppercase(), host, { openIn(sc.url, sc.profile) }, { shortcutMenu(sc) })
        }
        tiles += tile("Add", "+", null, { editShortcut(null) }, null)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(4).forEach { rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(16)) }
            rowTiles.forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(4 - rowTiles.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        homeBox.addView(grid)

        // Jump back in
        val jUrl = prefs.getString("jump_url", null)
        val hItem = history.items.firstOrNull { it.url.startsWith("http") }
        val url = jUrl ?: hItem?.url
        if (url != null) {
            val title = (if (jUrl != null) prefs.getString("jump_title", "") else hItem?.title).orEmpty()
            val host = hostOf(url)
            val thumbFile = File(filesDir, "jump.png")
            homeBox.addView(sectionTitle("Jump back in").apply { setPadding(0, dp(24), 0, dp(14)) })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER) }
                setOnClickListener { load(url) }
            }
            val bm = if (jUrl != null && thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.path) else null
            if (bm != null) {
                val iv = ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }
                roundClip(iv, 12)
                card.addView(iv, LinearLayout.LayoutParams(dp(110), dp(84)))
            } else {
                val ph = FrameLayout(this).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(C_TILE2) }
                }
                val letter = TextView(this).apply {
                    text = host.take(1).uppercase(); textSize = 26f; setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                }
                ph.addView(letter, FrameLayout.LayoutParams(-1, -1))
                card.addView(ph, LinearLayout.LayoutParams(dp(110), dp(84)))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
            col.addView(TextView(this).apply {
                text = title.ifEmpty { host }; textSize = 16f; setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = host; textSize = 13f; setTextColor(C_MUTED); setSingleLine()
                ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
            })
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            homeBox.addView(card)
        }
    }

    private fun tile(label: String, letter: String, host: String?, onClick: () -> Unit, onLong: (() -> Unit)?): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(C_SURFACE); setStroke(dp(1), C_BORDER) }
        }
        val letterTv = TextView(this).apply {
            text = letter; textSize = 24f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        circle.addView(letterTv, FrameLayout.LayoutParams(-1, -1))
        if (host != null) {
            val img = ImageView(this).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_CENTER }
            roundClip(img, 8)
            circle.addView(img, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            loadFavicon(host, img, letterTv)
        }
        col.addView(circle, LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(6), dp(2), 0)
        }, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
        return col
    }

    /** Site icon via Google's favicon service, cached on disk. Falls back to the letter tile. */
    private fun loadFavicon(host: String, img: ImageView, letter: TextView) {
        val dir = File(filesDir, "favicons")
        dir.mkdirs()
        val f = File(dir, host.replace(Regex("[^A-Za-z0-9.-]"), "_") + ".png")
        fun show(bm: Bitmap) { img.setImageBitmap(bm); img.visibility = View.VISIBLE; letter.visibility = View.GONE }
        if (f.exists()) {
            val cached = BitmapFactory.decodeFile(f.path)
            if (cached != null) { show(cached); return }
        }
        Thread {
            try {
                val c = URL("https://www.google.com/s2/favicons?domain=$host&sz=128").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val bytes = c.inputStream.use { it.readBytes() }
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bm != null) { f.writeBytes(bytes); runOnUiThread { show(bm) } }
            } catch (e: Exception) { }
        }.start()
    }

    private fun shortcutMenu(sc: Shortcut) {
        AlertDialog.Builder(this).setItems(arrayOf("Edit", "Delete")) { _, w ->
            if (w == 0) editShortcut(sc)
            else { shortcuts.items.remove(sc); shortcuts.save(); refreshHome() }
        }.showT()
    }

    private fun editShortcut(existing: Shortcut?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val cur = visibleUrl()
        val name = EditText(this).apply {
            hint = "Name"; setSingleLine()
            setText(existing?.name ?: if (cur != null) hostOf(cur) else "")
        }
        val url = EditText(this).apply { hint = "URL"; setSingleLine(); setText(existing?.url ?: cur.orEmpty()) }
        box.addView(name); box.addView(url)
        val profSpin = if (profiles.items.isNotEmpty()) profileSpinner(existing?.profile.orEmpty()) else null
        profSpin?.let { box.addView(it) }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add shortcut" else "Edit shortcut")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                var u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http")) u = "https://$u"
                val n = name.text.toString().trim().ifEmpty { hostOf(u).ifEmpty { u } }
                val pid = profSpin?.let { profileIdAt(it.selectedItemPosition) } ?: existing?.profile.orEmpty()
                if (existing == null) shortcuts.items.add(Shortcut(n, u, pid))
                else { existing.name = n; existing.url = u; existing.profile = pid }
                shortcuts.save(); refreshHome()
            }.showT()
    }

    // ---------- "Jump back in" preview ----------

    private fun captureThumb() {
        val t = current ?: return
        if (t.isHome || !t.url.startsWith("http")) return
        val url = t.url
        val title = t.title
        try {
            captureWeb(t.web)?.let { saveThumb(it, url, title) }
        } catch (e: Exception) { }
    }

    private fun saveThumb(bmp: Bitmap, url: String, title: String) {
        Thread {
            try {
                if (bmp.width <= 0 || bmp.height <= 0) return@Thread
                val w = 360
                val h = (bmp.height * w / bmp.width.toFloat()).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                FileOutputStream(File(filesDir, "jump.png")).use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
                prefs.edit().putString("jump_url", url).putString("jump_title", title).apply()
            } catch (e: Exception) { }
        }.start()
    }


    // ---------- Screenshots of the WebView ----------

    /** Draws the WebView's current viewport into a bitmap (page only, no bars or floating buttons). */
    private fun captureWeb(web: WebView): Bitmap? = try {
        if (web.width <= 0 || web.height <= 0) null
        else Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888).also { web.draw(Canvas(it)) }
    } catch (e: Throwable) { null }

    /**
     * Full page: scrolls the page one screen at a time, copies what is really on screen with PixelCopy
     * (WebView.draw returns blank frames for content that was just scrolled in) and stitches the frames.
     * Floating buttons are hidden while it runs.
     */
    @Suppress("DEPRECATION")
    private fun fullPageScreenshot() {
        val t = current
        if (t == null || t.isHome) { toast("Open a page first"); return }
        if (capturing) return
        val web = t.web
        val vw = web.width
        val vh = web.height
        if (vw <= 0 || vh <= 0) return
        val total = minOf((web.contentHeight * web.scale).toInt(), MAX_SHOT_H).coerceAtLeast(vh)
        val s = try {
            FullShot(
                web, vw, vh, total, web.scrollY,
                Bitmap.createBitmap(vw, total, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            )
        } catch (e: Throwable) { toast("Not enough memory for this page"); return }
        capturing = true
        floatLayer.visibility = View.INVISIBLE
        fsButton.visibility = View.INVISIBLE
        toast("Capturing full page… please don't touch the screen")
        shotStep(s)
    }

    private fun shotStep(s: FullShot) {
        // After the first screen, hide position:fixed elements (chat bubble, floating menus) so they are
        // not stitched in on every screen. Full-width bars pinned to the bottom (the game footer) are
        // brought back for the last screen only, so they appear once at the end of the page.
        val last = s.y + s.vh >= s.total - 2
        if (s.y > 0 && !s.fixedHidden) {
            s.fixedHidden = true
            s.web.evaluateJavascript(
                "(function(){var a=[],W=innerWidth,H=innerHeight;document.querySelectorAll('body *').forEach(function(e){" +
                "if(getComputedStyle(e).position==='fixed'){var r=e.getBoundingClientRect();" +
                "var bar=r.width>=W*0.6&&r.bottom>=H-8&&r.top>H*0.5;" +
                "a.push([e,e.style.visibility,bar]);e.style.visibility='hidden';}});window.__gbfFixed=a;})()", null
            )
        }
        if (last && s.fixedHidden && !s.barsShown) {
            s.barsShown = true
            s.web.evaluateJavascript(
                "(function(){(window.__gbfFixed||[]).forEach(function(p){if(p[2])p[0].style.visibility=p[1];});})()", null
            )
        }
        // never ask for more scroll than the page has: the WebView would report the requested value
        // even though it is clamped, which stitched the same strip twice
        s.target = s.y.coerceAtMost((s.total - s.vh).coerceAtLeast(0))
        s.web.scrollTo(0, s.target)
        handler.postDelayed({ shotCapture(s) }, 700)   // let the new frame render
    }

    private fun shotCapture(s: FullShot) {
        // read the real scroll position from the page (physical pixels)
        s.web.evaluateJavascript("Math.round(window.pageYOffset*window.devicePixelRatio)") { r ->
            val js = r?.trim('"')?.toDoubleOrNull()?.toInt()
            val actual = if (js == null || js < 0 || (js == 0 && s.target > 0)) s.target else js
            shotCaptureAt(s, actual)
        }
    }

    private fun shotCaptureAt(s: FullShot, actual: Int) {
        try {
            val off = (s.y - actual).coerceAtLeast(0)   // > 0 once the page cannot scroll any further
            val rows = minOf(s.vh - off, s.total - s.y)
            if (rows <= 0) { shotFinish(s, null); return }
            val loc = IntArray(2)
            s.web.getLocationInWindow(loc)
            val src = Rect(loc[0], loc[1], loc[0] + s.vw, loc[1] + s.vh)
            PixelCopy.request(window, src, s.frame, PixelCopy.OnPixelCopyFinishedListener { res ->
                shotCopied(s, res, actual, off, rows)
            }, handler)
        } catch (e: Throwable) {
            shotFinish(s, e.message ?: "capture failed")
        }
    }

    private fun shotCopied(s: FullShot, result: Int, actual: Int, off: Int, rows: Int) {
        try {
            if (result != PixelCopy.SUCCESS) { shotFinish(s, "screen copy failed ($result)"); return }
            s.canvas.drawBitmap(s.frame, Rect(0, off, s.vw, off + rows), Rect(0, s.y, s.vw, s.y + rows), null)
            s.filled = s.y + rows
            if (s.filled >= s.total || actual < s.y) shotFinish(s, null)
            else { s.y += s.vh; shotStep(s) }
        } catch (e: Throwable) {
            shotFinish(s, e.message ?: "out of memory")
        }
    }

    private fun shotFinish(s: FullShot, err: String?) {
        if (s.fixedHidden) s.web.evaluateJavascript(
            "(function(){(window.__gbfFixed||[]).forEach(function(p){p[0].style.visibility=p[1];});window.__gbfFixed=null;})()", null
        )
        s.web.scrollTo(0, s.startY)
        floatLayer.visibility = View.VISIBLE
        updateChrome()
        capturing = false
        s.frame.recycle()
        if (err != null || s.filled <= 0) {
            s.out.recycle()
            toast("Full page failed: ${err ?: "nothing captured"}")
            return
        }
        val bmp = if (s.filled < s.total) Bitmap.createBitmap(s.out, 0, 0, s.vw, s.filled) else s.out
        val name = "Gz_full_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        Thread {
            try {
                val sv = saveToStore(name, "image/png", true) { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
                logSaved(name, "image/png", sv)
                toastUi("Saved full page to ${sv.where}")
                showShotPreview(shotThumb(bmp))
            } catch (e: Throwable) {
                toastUi("Full page failed: ${e.message ?: "out of memory"}")
            }
        }.start()
    }
}
