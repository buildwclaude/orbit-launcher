package app.orbit.launcher.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Makes a monochrome glyph for apps that don't ship a themed icon, the way
 * Nothing's launcher and newer Pixels do, so themed styles cover every app.
 *
 * Works in the adaptive-icon space: a 108dp square whose middle 72dp is visible.
 * The result is a white glyph on transparent, ready to tint.
 */
object AutoMono {
    private const val S = 216 // working size: 2px per dp of the 108dp canvas
    private const val VIS_FROM = S / 6 // visible 72dp area: 18dp..90dp
    private const val VIS_TO = S * 5 / 6

    /** Glyph size relative to the canvas, matching typical system themed icons. */
    private const val GLYPH = 0.42f

    fun make(res: Resources, base: Drawable): Drawable? = runCatching {
        val alpha = if (base is AdaptiveIconDrawable && base.foreground != null) {
            val fg = render(base.foreground)
            if (opaqueFraction(fg) < 0.6f) {
                // A logo on its own background layer: the logo's shape is the glyph.
                alphaOf(fg)
            } else {
                // The foreground fills the icon: separate the logo from the main colour.
                val full = render(base.background)
                Canvas(full).drawBitmap(fg, 0f, 0f, null)
                contrastOf(full)
            }
        } else {
            // Old-style icon: scale it into the visible area first.
            val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
            base.setBounds(VIS_FROM, VIS_FROM, VIS_TO, VIS_TO)
            base.draw(Canvas(b))
            if (opaqueFraction(b) < 0.7f) alphaOf(b) else contrastOf(b)
        }
        normalize(alpha)?.let { BitmapDrawable(res, it) }
    }.getOrNull()

    private fun render(d: Drawable?): Bitmap {
        val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        if (d != null) {
            d.setBounds(0, 0, S, S)
            d.draw(Canvas(b))
        }
        return b
    }

    private fun pixels(b: Bitmap) = IntArray(S * S).also { b.getPixels(it, 0, S, 0, 0, S, S) }

    private inline fun inVisible(x: Int, y: Int) = x in VIS_FROM until VIS_TO && y in VIS_FROM until VIS_TO

    /** Share of the visible area that is opaque. */
    private fun opaqueFraction(b: Bitmap): Float {
        val px = pixels(b)
        var opaque = 0
        for (y in VIS_FROM until VIS_TO) for (x in VIS_FROM until VIS_TO) {
            if ((px[y * S + x] ushr 24) > 128) opaque++
        }
        val side = VIS_TO - VIS_FROM
        return opaque.toFloat() / (side * side)
    }

    private fun alphaOf(b: Bitmap): IntArray {
        val px = pixels(b)
        return IntArray(S * S) { i -> if (inVisible(i % S, i / S)) px[i] ushr 24 else 0 }
    }

    /** Pixels that differ from the icon's most common colour become the glyph. */
    private fun contrastOf(b: Bitmap): IntArray {
        val px = pixels(b)
        // Most common colour, on a 5-bit-per-channel histogram.
        val hist = IntArray(32 * 32 * 32)
        for (y in VIS_FROM until VIS_TO) for (x in VIS_FROM until VIS_TO) {
            val c = px[y * S + x]
            if ((c ushr 24) > 128) hist[((c shr 19) and 31) shl 10 or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)]++
        }
        var best = 0
        for (i in hist.indices) if (hist[i] > hist[best]) best = i
        val dr = ((best shr 10) and 31) * 8 + 4
        val dg = ((best shr 5) and 31) * 8 + 4
        val db = (best and 31) * 8 + 4

        return IntArray(S * S) { i ->
            val x = i % S
            val y = i / S
            val c = px[i]
            val a = c ushr 24
            if (!inVisible(x, y) || a == 0) {
                0
            } else {
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val bl = c and 255
                val dist = sqrt(((r - dr) * (r - dr) + (g - dg) * (g - dg) + (bl - db) * (bl - db)).toFloat())
                // Soft edge between 30 and 110 colour distance.
                (((dist - 30f) / 80f).coerceIn(0f, 1f) * a).toInt()
            }
        }
    }

    /** Crop to the glyph, scale it to a standard size and centre it. Null if nothing usable. */
    private fun normalize(alpha: IntArray): Bitmap? {
        var minX = S
        var minY = S
        var maxX = -1
        var maxY = -1
        var count = 0
        for (i in alpha.indices) {
            if (alpha[i] > 24) {
                val x = i % S
                val y = i / S
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                count++
            }
        }
        if (maxX < 0 || count < S * S / 400) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        // Covers the whole visible area: we picked up a background, not a logo.
        val side = VIS_TO - VIS_FROM
        if (bw > side * 0.95f && bh > side * 0.95f && count > side * side * 0.8f) return null

        val mask = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        mask.setPixels(IntArray(S * S) { (alpha[it] shl 24) or 0xFFFFFF }, 0, S, 0, 0, S, S)

        val scale = GLYPH * S / max(bw, bh)
        val w = (bw * scale).toInt()
        val h = (bh * scale).toInt()
        val left = (S - w) / 2
        val top = (S - h) / 2
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            mask,
            Rect(minX, minY, maxX + 1, maxY + 1),
            Rect(left, top, left + w, top + h),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        mask.recycle()
        return out
    }
}
