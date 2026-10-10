@file:OptIn(ExperimentalMaterial3Api::class)

package app.orbit.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.orbit.launcher.data.IconPack
import app.orbit.launcher.data.IconStyle
import app.orbit.launcher.data.PrivateSpace
import app.orbit.launcher.data.Prefs
import app.orbit.launcher.BuildConfig
import app.orbit.launcher.orbit
import kotlin.math.roundToInt

private val GRIDS = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6)

private fun isDefaultHome(ctx: Context): Boolean =
    ctx.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo?.packageName == ctx.packageName

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.orbit.prefs
    val s by prefs.settings.collectAsState()
    var packs by remember { mutableStateOf(IconPack.installed(ctx)) }
    var isDefault by remember { mutableStateOf(isDefaultHome(ctx)) }
    val privateSpace by ctx.orbit.apps.privateSpace.collectAsState()
    var crash by remember { mutableStateOf(ctx.orbit.crashLog.read()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = isDefaultHome(ctx)
        packs = IconPack.installed(ctx)
        // Android 15 hides Private space until Orbit is the home app: look again.
        ctx.orbit.apps.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orbit settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section(if (isDefault) "Orbit is your home app" else "Make Orbit your home app") {
                    Text(
                        if (isDefault) {
                            "Press Home to see it. To go back to the Nothing launcher, pick it here."
                        } else {
                            "Choose Orbit under Default home app. You can switch back to the Nothing launcher there any time."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) }
                    }) { Text("Default home app") }
                }
            }

            item {
                Section("Icons") {
                    StyleOption("App icons", "Each app's own icon", s.iconStyle == IconStyle.SYSTEM) {
                        prefs.setIconStyle(IconStyle.SYSTEM)
                    }
                    StyleOption(
                        "Google themed icons",
                        "Pixel-style icons in your wallpaper's colours, for every app",
                        s.iconStyle == IconStyle.THEMED_GOOGLE,
                    ) { prefs.setIconStyle(IconStyle.THEMED_GOOGLE) }
                    StyleOption(
                        "Nothing style",
                        "Monochrome icons in black and white, for every app",
                        s.iconStyle == IconStyle.THEMED_NOTHING,
                    ) { prefs.setIconStyle(IconStyle.THEMED_NOTHING) }
                    packs.forEach { pack ->
                        val id = IconStyle.PACK_PREFIX + pack.packageName
                        StyleOption(pack.label, "Icon pack", s.iconStyle == id) {
                            prefs.setIconStyle(id)
                        }
                    }
                    if (packs.isEmpty()) {
                        Text(
                            "No icon packs installed. Any pack from the Play Store that works with Nova or other launchers will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Section("Private space") {
                    Text(privateSpaceText(privateSpace, isDefault), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { ctx.orbit.apps.openPrivateSpaceSettings() }) {
                        Text(if (privateSpace == null) "Set up Private space" else "Private space settings")
                    }
                }
            }

            item {
                Section("Problems") {
                    val first = crash?.lineSequence()?.firstOrNull()
                    Text(
                        if (first == null) "Orbit hasn't crashed or frozen since this version was installed." else "Orbit stopped: $first",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "If something isn't working, copy the report and paste it in the chat. It stays on your phone until you paste it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyReport(ctx, isDefault, s.iconStyle, crash) }) { Text("Copy report") }
                        if (crash != null) {
                            OutlinedButton(onClick = {
                                ctx.orbit.crashLog.clear()
                                crash = null
                            }) { Text("Clear") }
                        }
                    }
                }
            }

            item {
                Section("Apps screen") {
                    Text("Background dim: ${(s.drawerDim * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "0% is fully transparent: just your wallpaper behind the apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = s.drawerDim,
                        onValueChange = { v -> prefs.edit { putFloat(Prefs.DRAWER_DIM, v) } },
                        valueRange = 0f..0.8f,
                        steps = 15,
                    )
                    SwitchRow("Blur wallpaper", "Works if your phone allows window blur", s.drawerBlur) {
                        prefs.edit { putBoolean(Prefs.DRAWER_BLUR, it) }
                    }
                    Text("Apps screen grid", style = MaterialTheme.typography.bodyMedium)
                    GridPicker(s.drawerCols to s.drawerRows) { (c, r) ->
                        prefs.edit { putInt(Prefs.DRAWER_COLS, c); putInt(Prefs.DRAWER_ROWS, r) }
                    }
                }
            }

            item {
                Section("Home screen") {
                    Text("Home screen grid", style = MaterialTheme.typography.bodyMedium)
                    GridPicker(s.homeCols to s.homeRows) { (c, r) ->
                        prefs.edit { putInt(Prefs.HOME_COLS, c); putInt(Prefs.HOME_ROWS, r) }
                    }
                    SwitchRow("Show app names", null, s.showLabels) { prefs.edit { putBoolean(Prefs.SHOW_LABELS, it) } }
                    SwitchRow("Swipe down for notifications", null, s.swipeDownNotifications) {
                        prefs.edit { putBoolean(Prefs.SWIPE_DOWN, it) }
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                        }
                    }) { Text("Change wallpaper") }
                }
            }
        }
    }
}

private fun privateSpaceText(space: PrivateSpace?, isDefault: Boolean): String = when {
    Build.VERSION.SDK_INT < 35 -> "Private space needs Android 15."
    space != null -> "Found, ${if (space.locked) "locked" else "unlocked"}. To open it, swipe up for the apps screen, " +
        "then on the first page swipe your finger from left to right. You can also tap the lock next to the page dots."
    !isDefault -> "Make Orbit your home app first. Android only shows Private space to the home app."
    else -> "No Private space on this phone yet. Set one up, then come back here."
}

private fun copyReport(ctx: Context, isDefault: Boolean, iconStyle: String, crash: String?) {
    val report = buildString {
        appendLine("Orbit ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("default home: $isDefault, icon style: $iconStyle")
        append(ctx.orbit.apps.profileReport)
        appendLine("private space: ${ctx.orbit.apps.privateSpace.value}")
        appendLine()
        append(crash ?: "No crash recorded.")
    }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Orbit report", report))
    // Android 13+ shows its own "Copied" message.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StyleOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GridPicker(current: Pair<Int, Int>, onPick: (Pair<Int, Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GRIDS.forEach { g ->
            FilterChip(
                selected = g == current,
                onClick = { onPick(g) },
                label = { Text("${g.first}×${g.second}") },
            )
        }
    }
}
