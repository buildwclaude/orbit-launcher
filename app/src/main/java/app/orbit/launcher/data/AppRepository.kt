package app.orbit.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
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
    /** Lives in Android 15's Private space. */
    val isPrivate: Boolean,
) {
    val packageName: String get() = component.packageName
    val isMainUser: Boolean get() = user == Process.myUserHandle()
}

/** Android 15 Private space, if the phone has one set up. */
@Immutable
data class PrivateSpace(val user: UserHandle, val locked: Boolean)

class AppRepository(private val context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppInfo>?>(null)

    /** Every app with an icon, sorted by name. Null until the first load. */
    val apps: StateFlow<List<AppInfo>?> = _apps

    private val _privateSpace = MutableStateFlow<PrivateSpace?>(null)
    val privateSpace: StateFlow<PrivateSpace?> = _privateSpace

    /** Called on the main thread when a package is installed, updated or removed for one user. */
    var onPackageChanged: ((pkg: String, userSerial: Long, removed: Boolean) -> Unit)? = null

    private var loadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName, user, true)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName, user, false)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName, user, false)
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) =
            pkgs.forEach { changed(it, user, false) }
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) =
            refresh()
    }

    // Private space / work profile locked or unlocked.
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
            addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            if (Build.VERSION.SDK_INT >= 35) {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            }
        }
        ContextCompat.registerReceiver(context, profileReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    private fun changed(pkg: String, user: UserHandle, removed: Boolean) {
        onPackageChanged?.invoke(pkg, userManager.getSerialNumberForUser(user), removed)
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val (list, space) = withContext(Dispatchers.Default) { load() }
            _privateSpace.value = space
            _apps.value = list
        }
    }

    private fun isPrivateProfile(user: UserHandle): Boolean =
        Build.VERSION.SDK_INT >= 35 &&
            runCatching { launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE }
                .getOrDefault(false)

    private fun load(): Pair<List<AppInfo>, PrivateSpace?> {
        val collator = Collator.getInstance()
        val result = ArrayList<AppInfo>()
        var space: PrivateSpace? = null
        for (user in launcherApps.profiles) {
            val isPrivate = isPrivateProfile(user)
            if (isPrivate) {
                val locked = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(true)
                space = PrivateSpace(user, locked)
                // Locked: its apps stay hidden, like on Pixel and Nothing.
                if (locked) continue
            }
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
                    isPrivate = isPrivate,
                )
            }
        }
        result.sortWith { a, b -> collator.compare(a.label, b.label) }
        return result to space
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

    /**
     * Lock or unlock Private space. Unlocking makes Android ask for your PIN,
     * pattern or fingerprint. Allowed because Orbit is the default home app.
     */
    fun setPrivateSpaceLocked(locked: Boolean) {
        val space = _privateSpace.value ?: return
        try {
            userManager.requestQuietModeEnabled(locked, space.user)
        } catch (e: Exception) {
            Log.w(TAG, "private space ${if (locked) "lock" else "unlock"} failed", e)
            Toast.makeText(context, "Make Orbit your home app to use Private space", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    /** Android's Private space settings screen. */
    fun privateSpaceSettings() =
        if (Build.VERSION.SDK_INT >= 35) runCatching { launcherApps.privateSpaceSettingsIntent }.getOrNull() else null
}
