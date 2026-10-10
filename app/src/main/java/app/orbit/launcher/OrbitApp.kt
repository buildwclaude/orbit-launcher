package app.orbit.launcher

import android.app.Application
import android.content.Context
import app.orbit.launcher.data.AppRepository
import app.orbit.launcher.data.CrashLog
import app.orbit.launcher.data.IconProvider
import app.orbit.launcher.data.LayoutStore
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

    override fun onCreate() {
        super.onCreate()
        crashLog = CrashLog(this).also { it.install() }
        prefs = Prefs(this)
        apps = AppRepository(this, scope)
        icons = IconProvider(this, prefs, apps, scope)
        layout = LayoutStore(prefs.sp)

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
