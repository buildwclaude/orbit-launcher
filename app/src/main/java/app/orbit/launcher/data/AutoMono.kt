package app.orbit.launcher.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
 * The result is a white glyph on transparent, ready to tint. Each call draws the
 * icon once, works on that one pixel array, and frees its scratch bitmaps.
 */
object AutoMono {
    private const val S = 216 // working size: 2px per dp of the 108dp canvas
    private const val VIS_FROM = S / 6 // visible 72dp area: 18dp..90dp
    private const val VIS_TO = S * 5 / 6
    private const val SIDE = VIS_TO - VIS_FROM

    /** Glyph size relative to the canvas, matching typical system themed icons. */
    private const val GLYPH = 0.42f

    /** Always returns a glyph: the logo when one can be found, otherwise the app's first letter. */
    fun make(res: Resources, base: Drawable, label: String): Drawable =
        BitmapDrawable(res, runCatching { glyphFor(base) }.getOrNull() ?: letter(label))

    private fun glyphFor(base: Drawable): Bitmap? {
        val px: IntArray
        val mask: IntArray
        if (base is AdaptiveIconDrawable && base.foreground != null) {
            val fg = pixelsOf(base.foreground, 0, S)
            if (opaqueFraction(fg) < 0.6f) {
                // A logo on its own background layer: the logo's shape is the glyph.
                px = fg
                mask = alphaOf(fg)
            } else {
                // The foreground fills the icon: separate the logo from the main colour.
                px = pixelsOf(base, VIS_FROM, VIS_TO, layers = true)
                mask = contrastOf(px)
            }
        } else {
            // Old-style icon: scale it into the visible area.
            px = pixelsOf(base, VIS_FROM, VIS_TO)
            mask = if (opaqueFraction(px) < 0.7f) alphaOf(px) else contrastOf(px)
        }
        // A solid circle or square means we found a badge, not a logo:
        // look for the logo inside it instead.
        return normalize(mask) ?: normalize(contrastOf(px))
    }

    /**
     * Draws [d] into [from]..[to] of an S×S canvas and returns its pixels.
     * With [layers], draws an adaptive icon's background and foreground unmasked
     * across the whole canvas, so its layers line up with [VIS_FROM]..[VIS_TO].
     */
    private fun pixelsOf(d: Drawable, from: Int, to: Int, layers: Boolean = false): IntArray {
        val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        if (layers && d is AdaptiveIconDrawable) {
            for (layer in listOf(d.background, d.foreground)) {
                layer ?: continue
                layer.setBounds(0, 0, S, S)
                layer.draw(canvas)
            }
        } else {
            d.setBounds(from, from, to, to)
            d.draw(canvas)
        }
        val px = IntArray(S * S)
        b.getPixels(px, 0, S, 0, 0, S, S)
        b.recycle()
        return px
    }

    private fun inVisible(i: Int): Boolean {
        val x = i % S
        val y = i / S
        return x >= VIS_FROM && x < VIS_TO && y >= VIS_FROM && y < VIS_TO
    }

    /** Share of the visible area that is opaque. */
    private fun opaqueFraction(px: IntArray): Float {
        var opaque = 0
        for (y in VIS_FROM until VIS_TO) for (x in VIS_FROM until VIS_TO) {
            if ((px[y * S + x] ushr 24) > 128) opaque++
        }
        return opaque.toFloat() / (SIDE * SIDE)
    }

    private fun alphaOf(px: IntArray) = IntArray(S * S) { i -> if (inVisible(i)) px[i] ushr 24 else 0 }

    /** Pixels that differ from the icon's most common colour become the glyph. */
    private fun contrastOf(px: IntArray): IntArray {
        // Most common colour, on a 5-bit-per-channel histogram.
        val hist = IntArray(32 * 32 * 32)
        for (y in VIS_FROM until VIS_TO) for (x in VIS_FROM until VIS_TO) {
            val c = px[y * S + x]
            if ((c ushr 24) > 128) hist[(((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)]++
        }
        var best = 0
        for (i in hist.indices) if (hist[i] > hist[best]) best = i
        val dr = ((best shr 10) and 31) * 8 + 4
        val dg = ((best shr 5) and 31) * 8 + 4
        val db = (best and 31) * 8 + 4

        return IntArray(S * S) { i ->
            val c = px[i]
            val a = c ushr 24
            if (a == 0 || !inVisible(i)) {
                0
            } else {
                val r = ((c shr 16) and 255) - dr
                val g = ((c shr 8) and 255) - dg
                val b = (c and 255) - db
                val dist = sqrt((r * r + g * g + b * b).toFloat())
                // Soft edge between 30 and 110 colour distance.
                (((dist - 30f) / 80f).coerceIn(0f, 1f) * a).toInt()
            }
        }
    }

    /** Crop to the glyph, scale it to a standard size and centre it. Null if it isn't a logo. */
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
        // A big solid shape (circle, square, whole background) isn't a logo.
        if (count.toFloat() / (bw * bh) > 0.72f && max(bw, bh) > SIDE * 0.3f) return null

        for (i in alpha.indices) alpha[i] = (alpha[i] shl 24) or 0xFFFFFF
        val mask = Bitmap.createBitmap(alpha, S, S, Bitmap.Config.ARGB_8888)

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
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        mask.recycle()
        return out
    }

    /** Fallback glyph: the first letter of the app's name. */
    private fun letter(label: String): Bitmap {
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val text = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = S * 0.36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        Canvas(out).drawText(text, S / 2f, S / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return out
    }
}
