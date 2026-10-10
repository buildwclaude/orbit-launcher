package app.orbit.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
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
import app.orbit.launcher.data.HomeWidget
import app.orbit.launcher.data.Layout
import app.orbit.launcher.data.Settings
import app.orbit.launcher.orbit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val DrawerSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@Composable
fun Launcher(
    homePressed: Flow<Unit>,
    onBlur: (Int) -> Unit,
    onExpandNotifications: () -> Unit,
    onAddWidget: (AppWidgetProviderInfo, Int) -> Unit,
    showPage: Flow<Int>,
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
    /** One UI edit mode (long-press empty home space): pages zoomed out, add/delete pages, widgets. */
    var editMode by remember { mutableStateOf(false) }
    var widgetPicker by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf<Int?>(null) }
    var confirmDeletePage by remember { mutableStateOf<Int?>(null) }
    var rootWidth by remember { mutableFloatStateOf(0f) }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }

    // While dragging, an extra empty page waits on the right for a new page; in edit mode, the "+" page.
    val homePager = rememberPagerState { layout.pageCount + if (drag.active || editMode) 1 else 0 }

    fun openDrawer() {
        editMode = false
        scope.launch { drawer.animateTo(1f, DrawerSpring) }
    }

    fun closeDrawer() {
        focus.clearFocus()
        keyboard?.hide()
        query = ""
        scope.launch { drawer.animateTo(0f, DrawerSpring) }
    }

    fun open(app: AppInfo, bounds: android.graphics.Rect?) = orbit.apps.launch(app, bounds)

    fun deletePage(page: Int) {
        orbit.layout.deletePage(page).forEach { orbit.widgetHost.deleteAppWidgetId(it) }
    }

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
            widgetPicker = false
            editMode = false
            if (drawer.value > 0f) closeDrawer() else homePager.animateScrollToPage(0)
        }
    }

    // A widget was just added: show the page it's on.
    LaunchedEffect(Unit) {
        showPage.collect { page ->
            editMode = false
            withTimeoutOrNull(1000) { snapshotFlow { homePager.pageCount }.first { it > page } }
            homePager.animateScrollToPage(page)
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
        // Picking up an icon leaves edit mode, so it drops onto the full-size grid.
        editMode = false
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
        when {
            widgetPicker -> widgetPicker = false
            editMode -> editMode = false
            drawer.value > 0f || drawer.targetValue > 0f -> closeDrawer()
        }
    }

    val swipeDown = settings.swipeDownNotifications
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootWidth = it.width.toFloat() }
            // Swipe up: apps screen (follows the finger). Swipe down on home: notifications.
            .pointerInput(swipeDown, editMode) {
                // Edit mode: swiping does nothing but flip pages.
                if (editMode) return@pointerInput
                val tracker = VelocityTracker()
                var total = 0f
                var startedOnHome = true
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        tracker.resetTracking()
                        startedOnHome = drawer.value == 0f
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
            editMode = editMode,
            onLongPressEmpty = { editMode = true },
            onTapEmpty = { editMode = false },
            onAddPage = {
                val p = orbit.layout.addPage()
                scope.launch { homePager.animateScrollToPage(p) }
            },
            onDeletePage = { p -> if (layout.isPageEmpty(p)) deletePage(p) else confirmDeletePage = p },
            onResizeWidget = { resizing = it.id },
            onWallpaper = {
                editMode = false
                runCatching {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                }
            },
            onWidgets = { widgetPicker = true },
            onSettings = {
                editMode = false
                onOpenSettings()
            },
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

        if (widgetPicker) {
            WidgetPicker(
                cols = settings.homeCols,
                rows = settings.homeRows,
                onDismiss = { widgetPicker = false },
                onPick = { info ->
                    widgetPicker = false
                    onAddWidget(info, homePager.currentPage.coerceAtMost(layout.pageCount - 1))
                },
            )
        }

        resizing?.let { id ->
            ResizeWidgetDialog(id, settings.homeCols, settings.homeRows, onDismiss = { resizing = null })
        }

        confirmDeletePage?.let { page ->
            AlertDialog(
                onDismissRequest = { confirmDeletePage = null },
                title = { Text("Delete this page?") },
                text = { Text("The apps and widgets on it will be removed from Home. Apps stay on the apps screen.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeletePage = null
                        deletePage(page)
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDeletePage = null }) { Text("Cancel") } },
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
    editMode: Boolean,
    onLongPressEmpty: () -> Unit,
    onTapEmpty: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (Int) -> Unit,
    onResizeWidget: (HomeWidget) -> Unit,
    onWallpaper: () -> Unit,
    onWidgets: () -> Unit,
    onSettings: () -> Unit,
    onGridBounds: (Rect) -> Unit,
    onDockBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentEdit by rememberUpdatedState(editMode)
    val currentTapEmpty by rememberUpdatedState(onTapEmpty)
    val pageShape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxSize().systemBarsPadding()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 24.dp)
                .onGloballyPositioned { onGridBounds(it.boundsInRoot()) },
            // Edit mode: smaller pages with the next ones peeking in, like One UI.
            contentPadding = if (editMode) PaddingValues(horizontal = 36.dp) else PaddingValues(0.dp),
            pageSpacing = if (editMode) 12.dp else 0.dp,
            beyondViewportPageCount = 1,
        ) { page ->
            if (editMode && page >= layout.pageCount) {
                AddPageCard(onAddPage, Modifier.padding(vertical = 16.dp).fillMaxSize())
                return@HorizontalPager
            }
            val frame = if (editMode) {
                Modifier
                    .padding(vertical = 16.dp)
                    .clip(pageShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), pageShape)
                    .padding(top = 44.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)
            } else {
                Modifier
            }
            Box(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().then(frame)) {
                    val cellW = maxWidth / settings.homeCols
                    val cellH = maxHeight / settings.homeRows
                    val iconSize = minOf(cellW * 0.66f, cellH * 0.56f, 62.dp)
                    // Empty space: long-press for edit mode; in edit mode, a tap goes back.
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { if (currentEdit) currentTapEmpty() },
                                onLongPress = { onLongPressEmpty() },
                            )
                        },
                    )
                    for (w in layout.widgets) {
                        if (w.page != page) continue
                        key("widget:${w.id}") {
                            HomeWidgetView(
                                widget = w,
                                cellW = cellW,
                                cellH = cellH,
                                cols = settings.homeCols,
                                rows = settings.homeRows,
                                onResize = onResizeWidget,
                                modifier = Modifier
                                    .offset(cellW * w.x, cellH * w.y)
                                    .size(cellW * w.w, cellH * w.h),
                            )
                        }
                    }
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
                if (editMode && layout.pageCount > 1) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 22.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onDeletePage(page) }
                            .padding(6.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete page", tint = Color.White)
                    }
                }
            }
        }

        PageDots(pager.pageCount, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))

        if (editMode) {
            EditBar(onWallpaper = onWallpaper, onWidgets = onWidgets, onSettings = onSettings)
            return@Column
        }

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

/** Edit mode's bottom bar, where the dock usually is (One UI: Wallpapers, Widgets, Settings). */
@Composable
private fun EditBar(onWallpaper: () -> Unit, onWidgets: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
            .height(84.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeMenuButton(Icons.Default.Edit, "Wallpaper", onWallpaper)
        HomeMenuButton(Icons.Default.AddCircle, "Widgets", onWidgets)
        HomeMenuButton(Icons.Default.Settings, "Settings", onSettings)
    }
}

/** The page after the last one in edit mode: tap to add an empty page. */
@Composable
private fun AddPageCard(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.5.dp, Color.White.copy(alpha = 0.4f), shape)
            .clickable(onClick = onAdd),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text("Add page", color = Color.White, fontSize = 15.sp)
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
