package app.orbit.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.orbit.launcher.ui.Launcher
import app.orbit.launcher.ui.OrbitTheme
import kotlinx.coroutines.flow.MutableSharedFlow

class HomeActivity : ComponentActivity() {
    /** Home pressed while already on the home screen: close the apps screen, go to page 1. */
    private val homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var blurRadius = 0

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
                    onDoubleTap = ::lockScreen,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
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

    /** Double-tap on empty home space. The first time, it shows where to turn it on. */
    private fun lockScreen() {
        if (!orbit.prefs.settings.value.doubleTapLock || LockService.lock()) return
        Toast.makeText(this, "To lock with a double-tap, turn on Orbit double-tap to lock", Toast.LENGTH_LONG).show()
        LockService.openSettings(this)
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
}
