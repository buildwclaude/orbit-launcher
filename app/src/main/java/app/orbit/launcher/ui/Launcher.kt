package app.orbit.launcher.ui

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.launcher.data.AppInfo
import app.orbit.launcher.data.Layout
import app.orbit.launcher.data.Settings
import app.orbit.launcher.orbit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DrawerSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@Composable
fun Launcher(
    homePressed: Flow<Unit>,
    onBlur: (Int) -> Unit,
    onExpandNotifications: () -> Unit,
    onDoubleTap: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val orbit = ctx.orbit
    val apps by orbit.apps.apps.collectAsState()
    val settings by orbit.prefs.settings.collectAsState()
    val layout by orbit.layout.layout.collectAsState()
    val privateSpace by orbit.apps.privateSpace.collectAsState()
    val appMap = remember(apps) { apps.orEmpty().associateBy { it.key } }
    // Private space apps only appear on their own page, never in the main list or on home.
    val (privateApps, mainApps) = remember(apps) { apps.orEmpty().partition { it.isPrivate } }

    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    /** 0 = home screen, 1 = apps screen fully open. */
    val drawer = remember { Animatable(0f) }
    val drag = remember { DragState() }
    var query by remember { mutableStateOf("") }
    var homeMenu by remember { mutableStateOf(false) }
    var rootWidth by remember { mutableFloatStateOf(0f) }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }

    // While dragging, an extra empty page waits on the right for a new page.
    val homePager = rememberPagerState { layout.pageCount + if (drag.active) 1 else 0 }

    fun openDrawer() {
        homeMenu = false
        scope.launch { drawer.animateTo(1f, DrawerSpring) }
    }

    fun closeDrawer() {
        focus.clearFocus()
        keyboard?.hide()
        query = ""
        scope.launch { drawer.animateTo(0f, DrawerSpring) }
    }

    fun open(app: AppInfo, bounds: android.graphics.Rect?) = orbit.apps.launch(app, bounds)

    /** Drop the dragged icon where the finger is: dock slot or home cell. */
    fun drop() {
        val app = drag.app
        val p = drag.position
        val s = settings
        if (app != null) {
            when {
                dockBounds.contains(p) -> {
                    val shown = layout.dock.count { it in appMap && it != app.key }
                    val slotW = dockBounds.width / s.homeCols
                    val start = dockBounds.left + (dockBounds.width - shown * slotW) / 2
                    val index = ((p.x - start) / slotW).roundToInt().coerceIn(0, shown)
                    orbit.layout.moveToDock(app.key, index, s.homeCols, s.homeCols, s.homeRows)
                }
                gridBounds.contains(p) -> {
                    val x = ((p.x - gridBounds.left) / (gridBounds.width / s.homeCols)).toInt().coerceIn(0, s.homeCols - 1)
                    val y = ((p.y - gridBounds.top) / (gridBounds.height / s.homeRows)).toInt().coerceIn(0, s.homeRows - 1)
                    orbit.layout.moveToCell(app.key, homePager.currentPage, x, y, s.homeCols, s.homeRows)
                }
            }
        }
        drag.reset()
    }

    val homeMenuFor = { app: AppInfo ->
        listOf(
            MenuAction("App info") { orbit.apps.openAppInfo(app) },
            MenuAction("Remove from Home") { orbit.layout.remove(app.key) },
            MenuAction("Uninstall") { orbit.apps.uninstall(app) },
        )
    }
    val drawerMenuFor = { app: AppInfo ->
        listOfNotNull(
            if (app.isPrivate || layout.contains(app.key)) null else MenuAction("Add to Home") {
                orbit.layout.add(app.key, settings.homeCols, settings.homeRows)
            },
            MenuAction("App info") { orbit.apps.openAppInfo(app) },
            MenuAction("Uninstall") { orbit.apps.uninstall(app) },
        )
    }

    LaunchedEffect(settings.homeCols, settings.homeRows) {
        orbit.layout.normalize(settings.homeCols, settings.homeRows)
    }

    LaunchedEffect(Unit) {
        homePressed.collect {
            homeMenu = false
            if (drawer.value > 0f) closeDrawer() else homePager.animateScrollToPage(0)
        }
    }

    // Blur the wallpaper in steps as the apps screen opens.
    LaunchedEffect(settings.drawerBlur) {
        val enabled = settings.drawerBlur
        snapshotFlow { drawer.value }
            .map { if (enabled) (it * 8).roundToInt() * 10 else 0 }
            .distinctUntilChanged()
            .collect { onBlur(it) }
    }

    // Hold a dragged icon at the screen edge to flip home pages.
    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        val edge = with(density) { 28.dp.toPx() }
        var dir = 0
        var since = SystemClock.uptimeMillis()
        while (drag.active) {
            val x = drag.position.x
            val d = when {
                x < edge -> -1
                x > rootWidth - edge -> 1
                else -> 0
            }
            val now = SystemClock.uptimeMillis()
            if (d != dir) {
                dir = d
                since = now
            } else if (d != 0 && now - since > 600) {
                val target = homePager.currentPage + d
                if (target in 0 until homePager.pageCount) homePager.animateScrollToPage(target)
                since = SystemClock.uptimeMillis()
            }
            delay(50)
        }
    }

    BackHandler {
        homeMenu = false
        if (drawer.value > 0f || drawer.targetValue > 0f) closeDrawer()
    }

    val swipeDown = settings.swipeDownNotifications
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootWidth = it.width.toFloat() }
            // Swipe up: apps screen (follows the finger). Swipe down on home: notifications.
            .pointerInput(swipeDown) {
                val tracker = VelocityTracker()
                var total = 0f
                var startedOnHome = true
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        tracker.resetTracking()
                        startedOnHome = drawer.value == 0f
                        homeMenu = false
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y
                        if (startedOnHome && drawer.value == 0f && total > 48.dp.toPx()) {
                            if (swipeDown) onExpandNotifications()
                        } else if (v < -800f || (v <= 800f && drawer.value > 0.4f)) {
                            openDrawer()
                        } else {
                            closeDrawer()
                        }
                    },
                    onDragCancel = { if (drawer.value > 0.4f) openDrawer() else closeDrawer() },
                    onVerticalDrag = { change, dy ->
                        tracker.addPointerInputChange(change)
                        total += dy
                        if (startedOnHome && total > 0f && drawer.value == 0f) return@detectVerticalDragGestures
                        change.consume()
                        val next = (drawer.value - dy / (size.height * 0.55f)).coerceIn(0f, 1f)
                        scope.launch { drawer.snapTo(next) }
                    },
                )
            },
    ) {
        // Dim the wallpaper as the apps screen opens.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = settings.drawerDim * drawer.value)) },
        )

        HomeScreen(
            layout = layout,
            appMap = appMap,
            settings = settings,
            pager = homePager,
            drag = drag,
            menuFor = homeMenuFor,
            onOpen = ::open,
            onDrop = ::drop,
            onLongPressEmpty = { homeMenu = true },
            onDoubleTapEmpty = onDoubleTap,
            onGridBounds = { gridBounds = it },
            onDockBounds = { dockBounds = it },
            modifier = Modifier.graphicsLayer {
                val p = drawer.value
                alpha = 1f - p
                scaleX = 1f - 0.06f * p
                scaleY = 1f - 0.06f * p
            },
        )

        val drawerVisible by remember { derivedStateOf { drawer.value > 0f } }
        // Stays composed while an icon dragged out of it is still in the air.
        if (drawerVisible || (drag.active && drag.fromDrawer)) {
            AppDrawer(
                apps = mainApps,
                settings = settings,
                query = query,
                onQuery = { query = it },
                drag = drag,
                menuFor = drawerMenuFor,
                onOpen = ::open,
                onDragOut = { closeDrawer() },
                onDrop = ::drop,
                onSettings = onOpenSettings,
                privateSpace = privateSpace,
                privateApps = privateApps,
                onPrivateLocked = { orbit.apps.setPrivateSpaceLocked(it) },
                onPrivateSettings = { orbit.apps.openPrivateSpaceSettings() },
                modifier = Modifier.graphicsLayer {
                    val p = drawer.value
                    alpha = p
                    translationY = (1f - p) * size.height * 0.2f
                },
            )
        }

        drag.app?.takeIf { drag.active }?.let { DraggedIcon(it, drag) }

        if (homeMenu) {
            HomeMenu(
                onDismiss = { homeMenu = false },
                onWallpaper = {
                    homeMenu = false
                    runCatching {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                    }
                },
                onSettings = {
                    homeMenu = false
                    onOpenSettings()
                },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    layout: Layout,
    appMap: Map<String, AppInfo>,
    settings: Settings,
    pager: PagerState,
    drag: DragState,
    menuFor: (AppInfo) -> List<MenuAction>,
    onOpen: (AppInfo, android.graphics.Rect?) -> Unit,
    onDrop: () -> Unit,
    onLongPressEmpty: () -> Unit,
    onDoubleTapEmpty: () -> Unit,
    onGridBounds: (Rect) -> Unit,
    onDockBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().systemBarsPadding()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 24.dp)
                .onGloballyPositioned { onGridBounds(it.boundsInRoot()) },
            beyondViewportPageCount = 1,
        ) { page ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cellW = maxWidth / settings.homeCols
                val cellH = maxHeight / settings.homeRows
                val iconSize = minOf(cellW * 0.66f, cellH * 0.56f, 62.dp)
                // Empty space: long-press for wallpaper & settings, double-tap to lock.
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onLongPressEmpty() }, onDoubleTap = { onDoubleTapEmpty() })
                    },
                )
                for (item in layout.items) {
                    if (item.page != page) continue
                    val app = appMap[item.key] ?: continue
                    key(item.key) {
                        AppTile(
                            app = app,
                            iconSize = iconSize,
                            showLabel = settings.showLabels,
                            drag = drag,
                            fromDrawer = false,
                            menu = { menuFor(app) },
                            onOpen = onOpen,
                            onDragOut = {},
                            onDrop = onDrop,
                            modifier = Modifier
                                .offset(cellW * item.x, cellH * item.y)
                                .size(cellW, cellH),
                        )
                    }
                }
            }
        }

        PageDots(pager.pageCount, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))

        Dock(
            keys = layout.dock,
            appMap = appMap,
            slots = settings.homeCols,
            drag = drag,
            menuFor = menuFor,
            onOpen = onOpen,
            onDrop = onDrop,
            onBounds = onDockBounds,
        )
    }
}

@Composable
private fun Dock(
    keys: List<String>,
    appMap: Map<String, AppInfo>,
    slots: Int,
    drag: DragState,
    menuFor: (AppInfo) -> List<MenuAction>,
    onOpen: (AppInfo, android.graphics.Rect?) -> Unit,
    onDrop: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
            .height(84.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
    ) {
        val slotW = maxWidth / slots
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            keys.mapNotNull { appMap[it] }.forEach { app ->
                key(app.key) {
                    AppTile(
                        app = app,
                        iconSize = minOf(slotW * 0.66f, 62.dp),
                        showLabel = false,
                        drag = drag,
                        fromDrawer = false,
                        menu = { menuFor(app) },
                        onOpen = onOpen,
                        onDragOut = {},
                        onDrop = onDrop,
                        modifier = Modifier.width(slotW).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DraggedIcon(app: AppInfo, drag: DragState) {
    val icon = rememberAppIcon(app) ?: return
    val size = 62.dp
    val half = with(LocalDensity.current) { size.toPx() / 2 }
    Image(
        icon,
        contentDescription = null,
        modifier = Modifier
            .offset { IntOffset((drag.position.x - half).roundToInt(), (drag.position.y - half).roundToInt()) }
            .size(size)
            .graphicsLayer {
                scaleX = 1.12f
                scaleY = 1.12f
            },
    )
}

/** Long-press on empty home space: a small bar like One UI's edit mode. */
@Composable
private fun HomeMenu(onDismiss: () -> Unit, onWallpaper: () -> Unit, onSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeMenuButton(Icons.Default.Edit, "Wallpaper", onWallpaper)
            HomeMenuButton(Icons.Default.Settings, "Settings", onSettings)
        }
    }
}

@Composable
private fun HomeMenuButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
