package com.tapminer

import android.content.Context
import android.os.Build

/**
 * Tiny persistent store — no settings menu. One game, one high score, one best
 * sector. Also whether to render side-by-side for the glasses.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("tapminer", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    // FABLE_X3_STARTER_GUIDE gotcha #24: the X3 Pro reports Build.MODEL=ARGF20;
    // detect RayNeo hardware by manufacturer/brand/product instead.
    val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    val sbs get() = isRayNeoX3 // side-by-side for the glasses, single view elsewhere

    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (v > highScore) p.edit().putInt("hi", v).apply() }

    var bestSector: Int
        get() = p.getInt("bestSector", 1)
        set(v) { if (v > bestSector) p.edit().putInt("bestSector", v).apply() }

    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }
}
