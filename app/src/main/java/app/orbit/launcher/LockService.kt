package app.orbit.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Double-tap to lock. Android only lets a launcher turn the screen off through
 * an accessibility service (device admin would also block fingerprint unlock).
 * It listens to nothing and reads nothing on screen: it only asks Android to lock.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: LockService? = null

        /** False when the service isn't turned on in Accessibility settings. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, LockService::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Orbit's own page in Accessibility settings, or the list if this phone can't open it directly. */
        fun openSettings(ctx: Context) {
            val me = ComponentName(ctx, LockService::class.java).flattenToString()
            val details = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, me)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(details) }.onFailure {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}
