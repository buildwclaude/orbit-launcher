package app.orbit.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.launcher.data.AppInfo
import app.orbit.launcher.data.Settings

/**
 * The apps screen: a search bar on top, then the apps in alphabetical order on
 * horizontal pages (One UI style). The wallpaper shows through behind it.
 */
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    settings: Settings,
    query: String,
    onQuery: (String) -> Unit,
    drag: DragState,
    menuFor: (AppInfo) -> List<MenuAction>,
    onOpen: (AppInfo, android.graphics.Rect?) -> Unit,
    onDragOut: () -> Unit,
    onDrop: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val cols = settings.drawerCols
    val rows = settings.drawerRows
    val iconSize = if (cols >= 5) 54.dp else 60.dp
    val results = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else apps.filter { it.label.contains(q, ignoreCase = true) }
            .sortedByDescending { it.label.startsWith(q, ignoreCase = true) }
    }

    Column(
        modifier
            .fillMaxSize()
            // Makes the whole screen a touch target, so taps never fall through to home.
            .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        SearchBar(
            query = query,
            onQuery = onQuery,
            onSearch = { results.firstOrNull()?.let { onOpen(it, null) } },
            onSettings = onSettings,
        )

        if (query.isBlank()) {
            val perPage = cols * rows
            val pageCount = maxOf(1, (apps.size + perPage - 1) / perPage)
            val pager = rememberPagerState { pageCount }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                beyondViewportPageCount = 1,
            ) { page ->
                val from = minOf(page * perPage, apps.size)
                val slice = apps.subList(from, minOf(from + perPage, apps.size))
                Column(Modifier.fillMaxSize()) {
                    for (r in 0 until rows) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            for (c in 0 until cols) {
                                val app = slice.getOrNull(r * cols + c)
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    if (app != null) key(app.key) {
                                        AppTile(
                                            app = app,
                                            iconSize = iconSize,
                                            showLabel = settings.showLabels,
                                            drag = drag,
                                            fromDrawer = true,
                                            menu = { menuFor(app) },
                                            onOpen = onOpen,
                                            onDragOut = onDragOut,
                                            onDrop = onDrop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PageDots(pageCount, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                items(results, key = { it.key }) { app ->
                    AppTile(
                        app = app,
                        iconSize = iconSize,
                        showLabel = true,
                        drag = drag,
                        fromDrawer = true,
                        menu = { menuFor(app) },
                        onOpen = onOpen,
                        onDragOut = onDragOut,
                        onDrop = onDrop,
                        modifier = Modifier.height(104.dp),
                    )
                }
            }
            if (results.isEmpty()) {
                Text(
                    "No apps found",
                    style = LabelStyle.copy(fontSize = 15.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val white = Color.White
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(white.copy(alpha = 0.16f))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = white.copy(alpha = 0.85f))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search", color = white.copy(alpha = 0.7f), fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = white, fontSize = 16.sp),
                    cursorBrush = SolidColor(white),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = white)
                }
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = white)
        }
    }
}
