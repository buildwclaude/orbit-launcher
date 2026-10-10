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

    /**
     * Saves and updates [settings] right away. Waiting for Android's "preference
     * changed" callback left switches stuck: in release builds the callback
     * stopped arriving, so nothing on screen ever saw the new value.
     */
    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _settings.value = read()
    }

    /**
     * The icon style is written to disk before anything reacts to it. Redrawing
     * every icon is the heaviest thing Orbit does; with apply(), anything going
     * wrong during the redraw lost the new choice and the old one came back.
     */
    fun setIconStyle(style: String) {
        sp.edit().putString(ICON_STYLE, style).commit()
        _settings.value = read()
    }

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
