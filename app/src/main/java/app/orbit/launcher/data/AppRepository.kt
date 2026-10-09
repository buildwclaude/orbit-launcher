package app.orbit.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

private const val TAG = "Orbit"

@Immutable
data class AppInfo(
    /** Stable id: "package/class#userSerial". */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val info: LauncherActivityInfo,
) {
    val packageName: String get() = component.packageName
    val isMainUser: Boolean get() = user == Process.myUserHandle()
}

class AppRepository(private val context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppInfo>?>(null)

    /** Every app with an icon, sorted by name. Null until the first load. */
    val apps: StateFlow<List<AppInfo>?> = _apps

    /** Called on the main thread when a package is installed, updated or removed. */
    var onPackageChanged: ((pkg: String, removed: Boolean) -> Unit)? = null

    private var loadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName, true)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName, false)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName, false)
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) =
            pkgs.forEach { changed(it, false) }
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) =
            refresh()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    private fun changed(pkg: String, removed: Boolean) {
        onPackageChanged?.invoke(pkg, removed)
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _apps.value = withContext(Dispatchers.Default) { load() }
        }
    }

    private fun load(): List<AppInfo> {
        val collator = Collator.getInstance()
        val result = ArrayList<AppInfo>()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val list = try {
                launcherApps.getActivityList(null, user)
            } catch (e: Exception) {
                Log.w(TAG, "getActivityList failed for $user", e)
                emptyList()
            }
            for (info in list) {
                val cn = info.componentName
                result += AppInfo(
                    key = "${cn.packageName}/${cn.className}#$serial",
                    label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName },
                    component = cn,
                    user = user,
                    info = info,
                )
            }
        }
        result.sortWith { a, b -> collator.compare(a.label, b.label) }
        return result
    }

    fun launch(app: AppInfo, sourceBounds: Rect?) {
        try {
            launcherApps.startMainActivity(app.component, app.user, sourceBounds, null)
        } catch (e: Exception) {
            Log.w(TAG, "launch failed", e)
            Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(app: AppInfo) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "app info failed", e)
        }
    }

    fun uninstall(app: AppInfo) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                    .putExtra(Intent.EXTRA_USER, app.user)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "uninstall failed", e)
        }
    }
}
