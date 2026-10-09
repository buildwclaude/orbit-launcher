package app.orbit.launcher.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders app icons in the chosen style:
 * - the app's own icon,
 * - Google themed icons (the app's monochrome layer in Material You colours),
 * - Nothing style (monochrome layer in black/white, everything else greyscale),
 * - or an installed icon pack.
 */
class IconProvider(
    private val context: Context,
    prefs: Prefs,
    private val apps: AppRepository,
    private val scope: CoroutineScope,
) {
    private val sizePx = (64 * context.resources.displayMetrics.density).toInt()
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    private val _version = MutableStateFlow(0)

    /** Bumped whenever every icon must be redrawn. */
    val version: StateFlow<Int> = _version

    @Volatile private var style = prefs.settings.value.iconStyle
    @Volatile private var pack: IconPack? = null
    private var warmJob: Job? = null

    init {
        scope.launch {
            prefs.settings.map { it.iconStyle }.distinctUntilChanged().collect { s ->
                pack = if (s.startsWith(IconStyle.PACK_PREFIX)) {
                    withContext(Dispatchers.IO) { IconPack.load(context, s.removePrefix(IconStyle.PACK_PREFIX)) }
                } else {
                    null
                }
                style = s
                invalidate()
            }
        }
        scope.launch { apps.apps.filterNotNull().collect { warm(it) } }
    }

    private fun invalidate() {
        cache.clear()
        _version.value++
        apps.apps.value?.let { warm(it) }
    }

    /** Themed colours follow light/dark mode. */
    fun onConfigurationChanged() {
        if (style == IconStyle.THEMED_GOOGLE || style == IconStyle.THEMED_NOTHING) invalidate()
    }

    fun dropPackage(pkg: String) {
        cache.keys.removeAll { it.substringAfter('|').startsWith("$pkg/") }
    }

    fun cached(app: AppInfo): ImageBitmap? = cache["${_version.value}|${app.key}"]

    /** Blocking; call off the main thread. */
    fun icon(app: AppInfo): ImageBitmap {
        val key = "${_version.value}|${app.key}"
        cache[key]?.let { return it }
        return render(app).also { cache[key] = it }
    }

    // Render everything up front so the apps screen never shows empty icons.
    private fun warm(list: List<AppInfo>) {
        warmJob?.cancel()
        warmJob = scope.launch(Dispatchers.Default) {
            for (app in list) {
                ensureActive()
                runCatching { icon(app) }
            }
        }
    }

    private fun render(app: AppInfo): ImageBitmap {
        val base: Drawable = runCatching { app.info.getIcon(0) }.getOrNull()
            ?: context.packageManager.defaultActivityIcon
        val s = style
        val styled: Drawable = when {
            s.startsWith(IconStyle.PACK_PREFIX) -> pack?.iconFor(app.component) ?: base
            s == IconStyle.THEMED_GOOGLE -> themed(base, googleColors()) ?: base
            s == IconStyle.THEMED_NOTHING -> themed(base, nothingColors()) ?: greyscale(base)
            else -> base
        }
        val badged = if (app.isMainUser) styled else context.packageManager.getUserBadgedIcon(styled, app.user)
        val bitmap = badged.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // Hardware bitmaps live on the GPU: no uploads while the pages scroll.
        return (bitmap.copy(Bitmap.Config.HARDWARE, false) ?: bitmap).asImageBitmap()
    }

    /** The icon's monochrome layer on a coloured background, like Pixel themed icons. */
    private fun themed(base: Drawable, colors: Pair<Int, Int>): Drawable? {
        if (Build.VERSION.SDK_INT < 33) return null
        val mono = (base as? AdaptiveIconDrawable)?.monochrome ?: return null
        mono.mutate().setTint(colors.second)
        return AdaptiveIconDrawable(ColorDrawable(colors.first), mono)
    }

    private fun greyscale(base: Drawable): Drawable = base.mutate().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private fun isDark() =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** background to glyph. */
    private fun googleColors(): Pair<Int, Int> = if (isDark()) {
        context.getColor(android.R.color.system_accent1_800) to context.getColor(android.R.color.system_accent1_100)
    } else {
        context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_accent1_700)
    }

    private fun nothingColors(): Pair<Int, Int> = if (isDark()) {
        0xFF1B1B1B.toInt() to 0xFFFFFFFF.toInt()
    } else {
        0xFFF4F4F4.toInt() to 0xFF111111.toInt()
    }
}
