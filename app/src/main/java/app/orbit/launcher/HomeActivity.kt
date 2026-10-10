package app.orbit.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.orbit.launcher.data.widgetSpan
import app.orbit.launcher.ui.Launcher
import app.orbit.launcher.ui.OrbitTheme
import kotlinx.coroutines.flow.MutableSharedFlow

class HomeActivity : ComponentActivity() {
    /** Home pressed while already on the home screen: close the apps screen, go to page 1. */
    private val homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var blurRadius = 0
    /** Home pages to scroll to, e.g. where a new widget landed. */
    private val showPage = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** A widget being added: waiting for Android's "allow" dialog or the widget's own setup screen. */
    private class PendingWidget(val id: Int, val info: AppWidgetProviderInfo, val page: Int)
    private var pending: PendingWidget? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pending ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configureOrPlace(p) else cancelWidget()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // White status/navigation bar icons over the wallpaper.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            OrbitTheme {
                Launcher(
                    homePressed = homePressed,
                    onBlur = ::setBlur,
                    onExpandNotifications = ::expandNotifications,
                    onAddWidget = ::addWidget,
                    showPage = showPage,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { orbit.widgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { orbit.widgetHost.stopListening() }
    }

    private fun addWidget(info: AppWidgetProviderInfo, page: Int) {
        val host = orbit.widgetHost
        val id = host.allocateAppWidgetId()
        val p = PendingWidget(id, info, page)
        pending = p
        if (orbit.widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrPlace(p)
            return
        }
        // Android asks once: "Allow Orbit to create widgets and access their data?"
        val ask = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        runCatching { bindWidget.launch(ask) }.onFailure { cancelWidget() }
    }

    /** Some widgets have a setup screen first (pick a contact, a city...). */
    private fun configureOrPlace(p: PendingWidget) {
        val optional = p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (p.info.configure == null || optional) {
            placeWidget(p)
            return
        }
        runCatching {
            orbit.widgetHost.startAppWidgetConfigureActivityForResult(this, p.id, 0, REQUEST_CONFIGURE, null)
        }.onFailure { placeWidget(p) }
    }

    // The widget setup screen only reports back through onActivityResult.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val p = pending ?: return
        if (resultCode == Activity.RESULT_OK) placeWidget(p) else cancelWidget()
    }

    private fun placeWidget(p: PendingWidget) {
        pending = null
        val s = orbit.prefs.settings.value
        val (w, h) = widgetSpan(p.info, resources.displayMetrics.density, s.homeCols, s.homeRows)
        val page = orbit.layout.addWidget(p.id, w, h, p.page, s.homeCols, s.homeRows)
        showPage.tryEmit(page)
    }

    private fun cancelWidget() {
        pending?.let { orbit.widgetHost.deleteAppWidgetId(it.id) }
        pending = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) homePressed.tryEmit(Unit)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orbit.icons.onConfigurationChanged()
    }

    /** Blurs the wallpaper behind the window (Android 12+, when the phone allows it). */
    private fun setBlur(radius: Int) {
        if (radius == blurRadius) return
        val wm = getSystemService(WindowManager::class.java)
        if (!wm.isCrossWindowBlurEnabled && radius > 0) return
        blurRadius = radius
        val attrs = window.attributes
        attrs.blurBehindRadius = radius
        attrs.flags = if (radius > 0) {
            attrs.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
        window.attributes = attrs
    }

    // StatusBarManager.expandNotificationsPanel isn't public API, but it's what
    // every third-party launcher uses for "swipe down for notifications".
    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        runCatching {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }

    companion object {
        private const val REQUEST_CONFIGURE = 41
    }
}
