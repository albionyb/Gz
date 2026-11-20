package com.gzbrowser

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gzbrowser.MainActivity.Companion.C_ICE
import com.gzbrowser.MainActivity.Companion.C_SURFACE
import com.gzbrowser.MainActivity.Companion.C_TEXT

/** Small thumbnail of a screenshot that was just saved. Call from any thread; costs little memory. */
internal fun MainActivity.shotThumb(bmp: Bitmap): Bitmap? = try {
    val sc = minOf(dp(110).toFloat() / bmp.width, dp(150).toFloat() / bmp.height, 1f)
    Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
} catch (e: Throwable) { null }

/** Slides a small "Saved" card with the thumbnail in at the bottom-left; it goes away after 3 seconds (tap to close). */
internal fun MainActivity.showShotPreview(thumb: Bitmap?) {
    if (thumb == null || !prefs.getBoolean("shot_preview", true)) return
    runOnUiThread {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
        content.findViewWithTag<View>("shot_preview")?.let { content.removeView(it) }
        val card = LinearLayout(this).apply {
            tag = "shot_preview"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(C_SURFACE); setStroke(dp(2), C_ICE) }
            elevation = dp(8).toFloat()
        }
        card.addView(ImageView(this).apply { setImageBitmap(thumb) }, LinearLayout.LayoutParams(thumb.width, thumb.height))
        card.addView(TextView(this).apply { text = "Saved"; textSize = 11f; setTextColor(C_TEXT); gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(10), 0, 0, dp(110)) }
        content.addView(card, lp)
        val off = -dp(150).toFloat()
        card.translationX = off
        card.animate().translationX(0f).setDuration(180).start()
        val out = Runnable { card.animate().translationX(off).setDuration(180).withEndAction { content.removeView(card) }.start() }
        card.setOnClickListener { card.removeCallbacks(out); out.run() }
        card.postDelayed(out, 3000)
    }
}
