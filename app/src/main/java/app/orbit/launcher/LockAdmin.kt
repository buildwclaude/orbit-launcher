package app.orbit.launcher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Double-tap to lock, through Android's "device admin" lock. Its only policy is
 * turning the screen off. (An accessibility service could do it too, but Play
 * Protect blocks installing sideloaded apps that have one.)
 */
class LockAdmin : DeviceAdminReceiver() {
    companion object {
        private fun component(ctx: Context) = ComponentName(ctx, LockAdmin::class.java)
        private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

        fun isEnabled(ctx: Context): Boolean = dpm(ctx).isAdminActive(component(ctx))

        /** False when it isn't turned on yet. */
        fun lock(ctx: Context): Boolean {
            if (!isEnabled(ctx)) return false
            return runCatching { dpm(ctx).lockNow() }.isSuccess
        }

        /** Android's own "Activate device admin app?" screen. */
        fun requestEnable(ctx: Context) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets you double-tap an empty spot on the Orbit home screen to lock the phone. That's all it can do.",
                )
            runCatching { ctx.startActivity(intent) }
        }

        /** Needed before Orbit can be uninstalled while it's on. */
        fun disable(ctx: Context) {
            runCatching { dpm(ctx).removeActiveAdmin(component(ctx)) }
        }
    }
}
