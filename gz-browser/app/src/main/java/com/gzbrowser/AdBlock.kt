package com.gzbrowser

import android.content.Context

/**
 * Tiny domain-based ad / tracker blocker used from WebViewClient.shouldInterceptRequest.
 * Loads assets/blocklist_base.txt (bundled) and assets/blocklist_extra.txt (downloaded at build time, optional).
 * Lines can be plain domains or hosts-file lines ("0.0.0.0 domain"); "#" starts a comment.
 */
object AdBlock {
    @Volatile private var domains: Set<String> = emptySet()
    private val ws = Regex("\\s+")

    // Never blocked, whatever the lists say: the game and its login/payment services.
    private val allow = arrayOf("granbluefantasy.jp", "cygames.jp", "mbga.jp", "mobage.jp")

    fun load(ctx: Context) {
        val set = HashSet<String>(131072)
        for (name in arrayOf("blocklist_base.txt", "blocklist_extra.txt")) {
            try {
                ctx.assets.open(name).bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.substringBefore('#').trim().lowercase()
                        if (line.isEmpty()) continue
                        val d = line.split(ws).last()
                        if (d.contains('.') && d != "localhost") set.add(d)
                    }
                }
            } catch (e: Exception) { /* optional file missing */ }
        }
        domains = set
    }

    private fun under(host: String, base: String) = host == base || host.endsWith(".$base")

    /** True for the game's own hosts: no cosmetic hiding and no popup blocking there. */
    fun isAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        for (a in allow) if (under(h, a)) return true
        return false
    }

    // Hides ad containers that are served from the site's own domain (domain blocking can't see those).
    private val hideSelectors = listOf(
        "ins.adsbygoogle", "[class~='adsbygoogle']",
        "[id^='google_ads']", "[id^='div-gpt-ad']", "iframe[id^='google_ads_iframe']",
        "[id^='ad-']", "[id^='ads-']", "[id^='ad_']", "[id^='ads_']",
        "[class~='ad-banner']", "[class~='ad-container']", "[class~='ad-slot']",
        "[class~='ad-wrapper']", "[class~='ad-box']", "[class~='banner-ad']",
        "[class~='advert']", "[class~='advertisement']", "[class*='adsbox']",
        "[data-ad-slot]", "[data-ad-client]", "[data-google-query-id]",
        "iframe[src*='doubleclick.net']", "iframe[src*='googlesyndication.com']",
        "[id^='taboola']", "[class~='taboola']", "[id^='outbrain']", "[class*='OUTBRAIN']"
    )

    fun cosmeticJs(): String {
        val css = org.json.JSONObject.quote(hideSelectors.joinToString(",") + "{display:none!important}")
        return "(function(){try{var css=" + css + ";" +
            "function add(){if(document.getElementById('gbf-adcss'))return;" +
            "var s=document.createElement('style');s.id='gbf-adcss';s.textContent=css;" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "add();setTimeout(add,1500);setTimeout(add,4000);}catch(e){}})();"
    }

    fun isBlocked(host: String?, pageHost: String): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        if (pageHost.isNotEmpty() && under(h, pageHost.lowercase())) return false   // first party
        for (a in allow) if (under(h, a)) return false
        val set = domains
        var d = h
        while (true) {
            if (set.contains(d)) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
            if (!d.contains('.')) return false
        }
    }
}
