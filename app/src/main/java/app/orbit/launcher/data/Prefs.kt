package app.orbit.launcher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object IconStyle {
    const val SYSTEM = "system"
    const val THEMED_GOOGLE = "themed_google"
    const val THEMED_NOTHING = "themed_nothing"
    const val PACK_PREFIX = "pack:"
}

data class Settings(
    val iconStyle: String,
    /** Black over the wallpaper when the apps screen is open, 0..0.8. */
    val drawerDim: Float,
    val drawerBlur: Boolean,
    val homeCols: Int,
    val homeRows: Int,
    val drawerCols: Int,
    val drawerRows: Int,
    val showLabels: Boolean,
    val swipeDownNotifications: Boolean,
)

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings

    // Held in a field: SharedPreferences only keeps a weak reference.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != LayoutStore.KEY) _settings.value = read()
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun read() = Settings(
        iconStyle = sp.getString(ICON_STYLE, null) ?: IconStyle.SYSTEM,
        drawerDim = sp.getFloat(DRAWER_DIM, 0.2f),
        drawerBlur = sp.getBoolean(DRAWER_BLUR, false),
        homeCols = sp.getInt(HOME_COLS, 4),
        homeRows = sp.getInt(HOME_ROWS, 5),
        drawerCols = sp.getInt(DRAWER_COLS, 5),
        drawerRows = sp.getInt(DRAWER_ROWS, 6),
        showLabels = sp.getBoolean(SHOW_LABELS, true),
        swipeDownNotifications = sp.getBoolean(SWIPE_DOWN, true),
    )

    fun edit(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    companion object {
        const val ICON_STYLE = "icon_style"
        const val DRAWER_DIM = "drawer_dim"
        const val DRAWER_BLUR = "drawer_blur"
        const val HOME_COLS = "home_cols"
        const val HOME_ROWS = "home_rows"
        const val DRAWER_COLS = "drawer_cols"
        const val DRAWER_ROWS = "drawer_rows"
        const val SHOW_LABELS = "show_labels"
        const val SWIPE_DOWN = "swipe_down_notifications"
    }
}
