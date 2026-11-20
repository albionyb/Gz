package com.gzbrowser

/** Custom Granblue-style bookmark icons (vector drawables ic_bm_*). */
object BmIcons {
    /** key -> (display name, drawable). Order = order shown in the picker. */
    val all: List<Triple<String, String, Int>> = listOf(
        Triple("raid", "Raid", R.drawable.ic_bm_raid),
        Triple("trial", "Trial Battle", R.drawable.ic_bm_trial),
        Triple("pending", "Pending", R.drawable.ic_bm_pending),
        Triple("home", "Home", R.drawable.ic_bm_home),
        Triple("inventory", "Inventory", R.drawable.ic_bm_inventory),
        Triple("supply", "Supply", R.drawable.ic_bm_supply),
        Triple("shop", "Shop", R.drawable.ic_bm_shop),
        Triple("gacha", "Gacha", R.drawable.ic_bm_gacha),
        Triple("quest", "Quest", R.drawable.ic_bm_quest),
        Triple("sandbox", "Arcarum Sandbox", R.drawable.ic_bm_sandbox),
        Triple("solomon", "Evoking Solomonis", R.drawable.ic_bm_solomon),
        Triple("crate", "Crate", R.drawable.ic_bm_crate),
        Triple("stash", "Stash", R.drawable.ic_bm_stash),
    )

    val screenshot: Int = R.drawable.ic_bm_shot

    /** Icons for tool bubbles (not shown in the picker). */
    private val toolIcons: Map<String, Int> = mapOf(
        "t_notes" to R.drawable.ic_m_notes, "t_drops" to R.drawable.ic_m_drop, "t_spark" to R.drawable.ic_m_spark,
        "t_reload" to R.drawable.ic_nav_reload, "t_screenshot" to R.drawable.ic_bm_shot, "t_fullscreen" to R.drawable.ic_m_full,
        "t_find" to R.drawable.ic_m_find, "t_desktop" to R.drawable.ic_m_desktop, "t_menu" to R.drawable.ic_nav_menu,
        "t_history" to R.drawable.ic_m_history, "t_downloads" to R.drawable.ic_m_download, "t_settings" to R.drawable.ic_m_settings
    )

    fun toolKey(tool: String): String = if (toolIcons.containsKey("t_$tool")) "t_$tool" else ""

    fun res(key: String): Int? = all.firstOrNull { it.first == key }?.third ?: toolIcons[key]
    fun name(key: String): String = all.firstOrNull { it.first == key }?.second ?: ""

    fun short(key: String): String = when (key) {
        "sandbox" -> "Sandbox"
        "solomon" -> "Solomon"
        "trial" -> "Trial"
        else -> name(key)
    }
}
