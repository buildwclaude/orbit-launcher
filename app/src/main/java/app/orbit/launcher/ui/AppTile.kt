package app.orbit.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.launcher.data.AppInfo
import app.orbit.launcher.orbit
import kotlinx.coroutines.withContext

data class MenuAction(val label: String, val run: () -> Unit)

/** The icon being dragged, in root (window) coordinates. */
@Stable
class DragState {
    var app by mutableStateOf<AppInfo?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
    var active by mutableStateOf(false)
    var fromDrawer by mutableStateOf(false)
        private set

    fun start(app: AppInfo, at: Offset, fromDrawer: Boolean) {
        this.app = app
        this.position = at
        this.fromDrawer = fromDrawer
        this.active = false
    }

    fun reset() {
        app = null
        active = false
        fromDrawer = false
    }
}

@Composable
fun rememberAppIcon(app: AppInfo): ImageBitmap? {
    val icons = LocalContext.current.orbit.icons
    val version by icons.version.collectAsState()
    return produceState(icons.cached(app), app.key, version) {
        value = withContext(icons.dispatcher) { runCatching { icons.icon(app) }.getOrNull() }
    }.value
}

@Composable
fun IconImage(icon: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (icon == null) Spacer(modifier.size(size)) else Image(icon, contentDescription = null, modifier = modifier.size(size))
}

/**
 * An app icon with its label. Tap opens it; long-press shows the menu, and
 * moving after the long-press picks the icon up to drag it (like One UI).
 */
@Composable
fun AppTile(
    app: AppInfo,
    iconSize: Dp,
    showLabel: Boolean,
    drag: DragState,
    fromDrawer: Boolean,
    menu: () -> List<MenuAction>,
    onOpen: (AppInfo, android.graphics.Rect?) -> Unit,
    onDragOut: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
    /** False for Private space apps: menu only, they can't be placed on home. */
    canDrag: Boolean = true,
) {
    val icon = rememberAppIcon(app)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var menuOpen by remember { mutableStateOf(false) }
    val hidden = drag.active && drag.app?.key == app.key

    // The gesture coroutines outlive recompositions; always call the latest lambdas.
    val currentOpen by rememberUpdatedState(onOpen)
    val currentDragOut by rememberUpdatedState(onDragOut)
    val currentDrop by rememberUpdatedState(onDrop)

    Column(
        modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerInput(app.key) {
                detectTapGestures(
                    onTap = { currentOpen(app, bounds.toAndroidRect()) },
                    // Handled by the drag detector; set so a long press never also counts as a tap.
                    onLongPress = {},
                )
            }
            .pointerInput(app.key) {
                var moved = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        moved = Offset.Zero
                        menuOpen = true
                        if (canDrag) drag.start(app, bounds.topLeft + offset, fromDrawer)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (!canDrag) return@detectDragGesturesAfterLongPress
                        moved += amount
                        drag.position += amount
                        if (!drag.active && moved.getDistance() > viewConfiguration.touchSlop * 2) {
                            drag.active = true
                            menuOpen = false
                            currentDragOut()
                        }
                    },
                    onDragEnd = { if (canDrag && drag.active) currentDrop() },
                    onDragCancel = { if (canDrag && drag.active) drag.reset() },
                )
            }
            .graphicsLayer { alpha = if (hidden) 0f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            IconImage(icon, iconSize)
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = {
                    menuOpen = false
                    if (!drag.active) drag.reset()
                },
            ) {
                menu().forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            menuOpen = false
                            drag.reset()
                            action.run()
                        },
                    )
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                style = LabelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) {
        Spacer(modifier.height(6.dp))
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 1f else 0.4f)),
            )
        }
    }
}
