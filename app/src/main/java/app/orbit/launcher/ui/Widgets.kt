package app.orbit.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import app.orbit.launcher.data.HomeWidget
import app.orbit.launcher.data.OrbitWidgetView
import app.orbit.launcher.data.widgetSpan
import app.orbit.launcher.orbit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A widget on home. It works like normal; long-press for Resize / Remove, or
 * long-press and move to drag it to other cells on the same page.
 */
@Composable
fun HomeWidgetView(
    widget: HomeWidget,
    cellW: Dp,
    cellH: Dp,
    cols: Int,
    rows: Int,
    onResize: (HomeWidget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val orbit = ctx.orbit
    val density = LocalDensity.current
    val info = remember(widget.id) { orbit.widgetManager.getAppWidgetInfo(widget.id) }
    var menuOpen by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val current by rememberUpdatedState(widget)
    val cellPx = with(density) { cellW.toPx() to cellH.toPx() }
    val currentCell by rememberUpdatedState(cellPx)

    val gestures = remember {
        object : OrbitWidgetView.Gestures {
            override fun onLongPress() {
                menuOpen = true
            }

            override fun onDrag(dx: Float, dy: Float) {
                menuOpen = false
                offset = Offset(dx, dy)
            }

            override fun onDragEnd() {
                val (cw, ch) = currentCell
                val w = current
                val x = w.x + (offset.x / cw).roundToInt()
                val y = w.y + (offset.y / ch).roundToInt()
                offset = Offset.Zero
                orbit.layout.moveWidget(w.id, x, y, cols, rows)
            }
        }
    }

    val wDp = cellW.value * widget.w - 8f
    val hDp = cellH.value * widget.h - 8f
    Box(
        modifier
            .zIndex(if (offset != Offset.Zero) 1f else 0f)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .graphicsLayer { alpha = if (offset != Offset.Zero) 0.85f else 1f }
            .padding(4.dp),
    ) {
        if (info == null) {
            // Its app was uninstalled or updated without it.
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) },
                contentAlignment = Alignment.Center,
            ) {
                Text("Widget isn't available", style = LabelStyle, textAlign = TextAlign.Center)
            }
        } else {
            AndroidView(
                factory = { c -> orbit.widgetHost.createView(c, widget.id, info) },
                update = { v ->
                    (v as? OrbitWidgetView)?.let {
                        it.gestures = gestures
                        it.setSizeDp(wDp, hDp)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (info != null) {
                DropdownMenuItem(text = { Text("Resize") }, onClick = {
                    menuOpen = false
                    onResize(widget)
                })
            }
            DropdownMenuItem(text = { Text("Remove") }, onClick = {
                menuOpen = false
                orbit.layout.removeWidget(widget.id)
                orbit.widgetHost.deleteAppWidgetId(widget.id)
            })
        }
    }
}

/** Width and height in cells, growing from the widget's top-left corner. */
@Composable
fun ResizeWidgetDialog(widgetId: Int, cols: Int, rows: Int, onDismiss: () -> Unit) {
    val orbit = LocalContext.current.orbit
    val layout by orbit.layout.layout.collectAsState()
    val w = layout.widgets.firstOrNull { it.id == widgetId }
    if (w == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    fun can(nw: Int, nh: Int) = orbit.layout.canResize(widgetId, nw, nh, cols, rows)
    fun set(nw: Int, nh: Int) = orbit.layout.resizeWidget(widgetId, nw, nh, cols, rows)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Widget size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper("Width", w.w, can(w.w - 1, w.h), can(w.w + 1, w.h), { set(w.w - 1, w.h) }, { set(w.w + 1, w.h) })
                Stepper("Height", w.h, can(w.w, w.h - 1), can(w.w, w.h + 1), { set(w.w, w.h - 1) }, { set(w.w, w.h + 1) })
                Text("It grows to the right and down, into empty cells.", fontSize = 13.sp)
            }
        },
    )
}

@Composable
private fun Stepper(label: String, value: Int, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        OutlinedButton(onClick = onDown, enabled = canDown, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
            Text("−", fontSize = 20.sp)
        }
        Text("$value", modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, fontSize = 18.sp)
        OutlinedButton(onClick = onUp, enabled = canUp, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
            Text("+", fontSize = 20.sp)
        }
    }
}

private class WidgetGroup(val label: String, val icon: ImageBitmap?, val widgets: List<AppWidgetProviderInfo>)

/** One UI style: apps with widgets, tap one to see its widgets, tap a widget to add it. */
@Composable
fun WidgetPicker(cols: Int, rows: Int, onDismiss: () -> Unit, onPick: (AppWidgetProviderInfo) -> Unit) {
    val ctx = LocalContext.current
    val orbit = ctx.orbit
    val density = LocalDensity.current
    val iconPx = with(density) { 36.dp.roundToPx() }
    val groups by produceState<List<WidgetGroup>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            orbit.widgetManager.installedProviders
                .groupBy { it.provider.packageName }
                .mapNotNull { (pkg, list) ->
                    val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                        ?: return@mapNotNull null
                    val icon = runCatching { pm.getApplicationIcon(app).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull()
                    WidgetGroup(pm.getApplicationLabel(app).toString(), icon, list.sortedBy { it.loadLabel(pm) })
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    var open by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2101418))
            // Nothing behind the picker reacts while it's open.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Widgets", color = Color.White, fontSize = 24.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
            }
            val list = groups
            if (list == null) {
                Text("Loading…", style = LabelStyle.copy(fontSize = 15.sp), modifier = Modifier.padding(24.dp))
                return@Column
            }
            if (list.isEmpty()) {
                Text("No apps with widgets", style = LabelStyle.copy(fontSize = 15.sp), modifier = Modifier.padding(24.dp))
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.label + it.widgets.first().provider.packageName }) { g ->
                    val key = g.widgets.first().provider.packageName
                    val expanded = open == key
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { open = if (expanded) null else key }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (g.icon != null) Image(g.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(g.label, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (g.widgets.size == 1) "1 widget" else "${g.widgets.size} widgets",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            }
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                        if (expanded) {
                            g.widgets.forEach { info -> WidgetCard(info, cols, rows) { onPick(info) } }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(info: AppWidgetProviderInfo, cols: Int, rows: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val maxPx = with(density) { 280.dp.roundToPx() }
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = info.loadPreviewImage(ctx, 0) ?: info.loadIcon(ctx, 0) ?: return@runCatching null
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxPx
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxPx
                val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    val (w, h) = widgetSpan(info, density.density, cols, rows)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        preview?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 160.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(info.loadLabel(ctx.packageManager), color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text("$w × $h", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}
