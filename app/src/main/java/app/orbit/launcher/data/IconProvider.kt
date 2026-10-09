package app.orbit.launcher.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * - Google themed icons (monochrome glyphs in Material You colours),
 * - Nothing style (monochrome layer in black/white),
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

    /**
     * All icon drawing runs here, two at a time. Unlimited parallel rendering
     * (100+ apps) starved the UI and the garbage collector when switching styles.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

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
        // A single odd icon must never take the home screen down: fall back to the plain icon.
        val bitmap = runCatching { render(app, style) }.recoverCatching { render(app, IconStyle.SYSTEM) }.getOrNull()
            ?: return placeholder()
        return bitmap.also { cache[key] = it }
    }

    // Render everything up front so the apps screen never shows empty icons.
    private fun warm(list: List<AppInfo>) {
        warmJob?.cancel()
        warmJob = scope.launch(dispatcher) {
            for (app in list) {
                ensureActive()
                runCatching { icon(app) }
            }
        }
    }

    private fun placeholder(): ImageBitmap =
        context.packageManager.defaultActivityIcon.toBitmap(sizePx, sizePx).asImageBitmap()

    private fun render(app: AppInfo, s: String): ImageBitmap {
        val base: Drawable = runCatching { app.info.getIcon(0) }.getOrNull()
            ?: context.packageManager.defaultActivityIcon
        val styled: Drawable = when {
            s.startsWith(IconStyle.PACK_PREFIX) -> pack?.iconFor(app.component) ?: base
            s == IconStyle.THEMED_GOOGLE -> themed(base, googleColors(), app.label)
            s == IconStyle.THEMED_NOTHING -> themed(base, nothingColors(), app.label)
            else -> base
        }
        val badged = if (app.isMainUser) styled else context.packageManager.getUserBadgedIcon(styled, app.user)
        val bitmap = badged.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // Hardware bitmaps live on the GPU: no uploads while the pages scroll.
        return (bitmap.copy(Bitmap.Config.HARDWARE, false) ?: bitmap).asImageBitmap()
    }

    /**
     * A monochrome glyph on a coloured background, like Pixel themed icons. Uses
     * the app's own themed icon when it has one, otherwise generates one.
     */
    private fun themed(base: Drawable, colors: Pair<Int, Int>, label: String): Drawable {
        val own = if (Build.VERSION.SDK_INT >= 33) (base as? AdaptiveIconDrawable)?.monochrome else null
        val mono = own?.mutate() ?: AutoMono.make(context.resources, base, label)
        mono.setTint(colors.second)
        return AdaptiveIconDrawable(ColorDrawable(colors.first), mono)
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
