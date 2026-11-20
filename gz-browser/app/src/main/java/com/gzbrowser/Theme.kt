package com.gzbrowser

/** Color presets for the whole app. The accent can be overridden separately (see Settings > Theme). */
object Theme {
    class Preset(
        val name: String,
        val bg: Int, val surface: Int, val dock: Int, val border: Int,
        val ice: Int, val muted: Int, val text: Int,
        val tile: Int, val edge: Int, val div: Int, val tile2: Int,
        val accent: Int
    )

    private fun h(rgb: Int) = 0xFF000000.toInt() or rgb

    val presets = listOf(
        // 0 = the original look
        Preset("Ocean", h(0x0B1626), h(0x12263F), h(0x0F2036), h(0x1F4468), h(0xCFEEFF), h(0x7FA6C2), h(0xE6F6FF),
            h(0x0F2A45), h(0x8FD3F0), h(0x1A3652), h(0x1B3A5C), h(0x3FB7E8)),
        Preset("Midnight", h(0x111216), h(0x1B1D23), h(0x16181D), h(0x30343D), h(0xE4E6EB), h(0x8D93A0), h(0xF1F2F5),
            h(0x20232B), h(0xA9B0BD), h(0x262932), h(0x2A2E38), h(0x5AA9FF)),
        Preset("AMOLED black", h(0x000000), h(0x0D0D10), h(0x060608), h(0x24262C), h(0xE6E6E6), h(0x8A8D94), h(0xF2F2F2),
            h(0x15161A), h(0xA0A4AD), h(0x1B1C20), h(0x1E2025), h(0x3FB7E8)),
        Preset("Violet", h(0x14101F), h(0x211A33), h(0x1B1529), h(0x3A2F5C), h(0xE4D9FF), h(0x9A8CBF), h(0xF0EAFF),
            h(0x2A2145), h(0xB9A5F5), h(0x2E2650), h(0x332A55), h(0x9059FF)),
        Preset("Forest", h(0x0C1A14), h(0x13281E), h(0x0F2018), h(0x1F4A36), h(0xD2F5E3), h(0x7FB59A), h(0xE6FBEF),
            h(0x0F2F22), h(0x8FE0B4), h(0x1A3A2B), h(0x1B4A35), h(0x3FD08A)),
        Preset("Crimson", h(0x1A0E10), h(0x2B1519), h(0x221215), h(0x5A2830), h(0xFFD9DD), h(0xC28A92), h(0xFFEBED),
            h(0x3A1A20), h(0xF29AA5), h(0x40202A), h(0x4A2029), h(0xF0505F))
    )

    class Swatch(val name: String, val color: Int)

    val swatches = listOf(
        Swatch("Cyan", h(0x3FB7E8)), Swatch("Sky", h(0x5AA9FF)), Swatch("Blue", h(0x3F7DE8)),
        Swatch("Indigo", h(0x6B6FE8)), Swatch("Violet", h(0x9059FF)), Swatch("Magenta", h(0xD14FD8)),
        Swatch("Pink", h(0xEC407A)), Swatch("Red", h(0xF0505F)), Swatch("Orange", h(0xFB8C00)),
        Swatch("Amber", h(0xF5C038)), Swatch("Lime", h(0x9CCC3E)), Swatch("Green", h(0x3FD08A)),
        Swatch("Teal", h(0x1FC8B4)), Swatch("Silver", h(0xB0BEC5)), Swatch("White", h(0xF2F2F2))
    )
}
