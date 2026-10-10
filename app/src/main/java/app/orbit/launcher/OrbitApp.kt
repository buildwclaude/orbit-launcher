package app.orbit.launcher

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import app.orbit.launcher.data.AppRepository
import app.orbit.launcher.data.CrashLog
import app.orbit.launcher.data.IconProvider
import app.orbit.launcher.data.LayoutStore
import app.orbit.launcher.data.OrbitWidgetHost
import app.orbit.launcher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OrbitApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var crashLog: CrashLog
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var icons: IconProvider
        private set
    lateinit var layout: LayoutStore
        private set
    lateinit var widgetHost: OrbitWidgetHost
        private set
    val widgetManager: AppWidgetManager by lazy { AppWidgetManager.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        crashLog = CrashLog(this).also { it.install() }
        prefs = Prefs(this)
        apps = AppRepository(this, scope)
        icons = IconProvider(this, prefs, apps, scope)
        layout = LayoutStore(prefs.sp)
        widgetHost = OrbitWidgetHost(this)
        // Widget ids Android still holds for us but that aren't on home any more (e.g. Orbit stopped mid-setup).
        runCatching {
            val onHome = layout.layout.value.widgets.map { it.id }.toSet()
            widgetHost.appWidgetIds.filter { it !in onHome }.forEach(widgetHost::deleteAppWidgetId)
        }

        apps.onPackageChanged = { pkg, userSerial, removed ->
            icons.dropPackage(pkg)
            if (removed) layout.removePackage(pkg, userSerial)
        }
        scope.launch {
            val list = apps.apps.filterNotNull().first()
            val s = prefs.settings.value
            layout.seed(this@OrbitApp, list, s.homeCols, s.homeRows)
        }
    }
}

val Context.orbit: OrbitApp get() = applicationContext as OrbitApp
