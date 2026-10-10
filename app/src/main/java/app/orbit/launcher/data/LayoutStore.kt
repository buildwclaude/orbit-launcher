package app.orbit.launcher.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HomeItem(val key: String, val page: Int, val x: Int, val y: Int)

/** A widget on home: its Android widget id, and the cells it covers (w × h from x, y). */
data class HomeWidget(val id: Int, val page: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun covers(p: Int, cx: Int, cy: Int) = p == page && cx in x until x + w && cy in y until y + h
}

data class Layout(
    val pageCount: Int = 1,
    val items: List<HomeItem> = emptyList(),
    val dock: List<String> = emptyList(),
    val widgets: List<HomeWidget> = emptyList(),
) {
    fun at(page: Int, x: Int, y: Int) = items.firstOrNull { it.page == page && it.x == x && it.y == y }
    fun widgetAt(page: Int, x: Int, y: Int) = widgets.firstOrNull { it.covers(page, x, y) }
    fun isFree(page: Int, x: Int, y: Int) = at(page, x, y) == null && widgetAt(page, x, y) == null
    fun isPageEmpty(page: Int) = items.none { it.page == page } && widgets.none { it.page == page }
    fun contains(key: String) = key in dock || items.any { it.key == key }
}

private data class Cell(val page: Int, val x: Int, val y: Int)

/**
 * Home screen pages, widgets and dock, saved as JSON in the launcher's preferences.
 * Pages stay until you delete them (One UI edit mode), even when empty.
 */
class LayoutStore(private val sp: SharedPreferences) {
    private val _layout = MutableStateFlow(load())
    val layout: StateFlow<Layout> = _layout

    private fun set(l: Layout) {
        val last = (l.items.map { it.page } + l.widgets.map { it.page }).maxOrNull() ?: 0
        val clean = l.copy(pageCount = maxOf(1, l.pageCount, last + 1))
        _layout.value = clean
        sp.edit().putString(KEY, toJson(clean)).apply()
    }

    // ---- Edits ----

    /** "Add to Home": first free cell, on a new page if all are full. */
    fun add(key: String, cols: Int, rows: Int) {
        val l = _layout.value
        if (l.contains(key)) return
        val c = freeCell(l, cols, rows)
        set(l.copy(pageCount = maxOf(l.pageCount, c.page + 1), items = l.items + HomeItem(key, c.page, c.x, c.y)))
    }

    fun remove(key: String) {
        val l = _layout.value
        set(l.copy(items = l.items.filterNot { it.key == key }, dock = l.dock - key))
    }

    /** An app was uninstalled for one user (main, work or private). */
    fun removePackage(pkg: String, userSerial: Long) {
        val l = _layout.value
        val gone = { k: String -> k.startsWith("$pkg/") && k.endsWith("#$userSerial") }
        if (l.dock.none(gone) && l.items.none { gone(it.key) }) return
        set(l.copy(items = l.items.filterNot { gone(it.key) }, dock = l.dock.filterNot(gone)))
    }

    /**
     * Drop [key] on a home cell. An icon already there swaps into the dragged
     * icon's old place (home cell or dock slot), or moves to a free cell when
     * the dragged icon came from the apps screen.
     */
    fun moveToCell(key: String, page: Int, x: Int, y: Int, cols: Int, rows: Int) {
        val l = _layout.value
        val from = l.items.firstOrNull { it.key == key }
        val dockIndex = l.dock.indexOf(key)
        val occupant = l.at(page, x, y)
        if (occupant?.key == key) return
        // A widget is there: the icon goes back where it was.
        if (l.widgetAt(page, x, y) != null) return

        var items = l.items.filterNot { it.key == key || it.key == occupant?.key } + HomeItem(key, page, x, y)
        var dock = l.dock
        var pageCount = maxOf(l.pageCount, page + 1)
        when {
            occupant == null -> if (dockIndex >= 0) dock = dock - key
            from != null -> items = items + occupant.copy(page = from.page, x = from.x, y = from.y)
            dockIndex >= 0 -> dock = dock.toMutableList().also { it[dockIndex] = occupant.key }
            else -> {
                val c = freeCell(l.copy(pageCount = pageCount, items = items, dock = dock), cols, rows)
                items = items + HomeItem(occupant.key, c.page, c.x, c.y)
                pageCount = maxOf(pageCount, c.page + 1)
            }
        }
        set(l.copy(pageCount = pageCount, items = items, dock = dock))
    }

    /** Drop [key] into the dock at [index]; a full dock swaps with the icon in that slot. */
    fun moveToDock(key: String, index: Int, slots: Int, cols: Int, rows: Int) {
        val l = _layout.value
        val from = l.items.firstOrNull { it.key == key }
        var items = l.items.filterNot { it.key == key }
        val dock = l.dock.toMutableList()
        var pageCount = l.pageCount
        when {
            key in dock -> {
                dock.remove(key)
                dock.add(index.coerceIn(0, dock.size), key)
            }
            dock.size < slots -> dock.add(index.coerceIn(0, dock.size), key)
            else -> {
                val i = index.coerceIn(0, dock.size - 1)
                val displaced = dock[i]
                dock[i] = key
                val c = if (from != null) Cell(from.page, from.x, from.y) else freeCell(l.copy(pageCount = pageCount, items = items, dock = dock), cols, rows)
                items = items + HomeItem(displaced, c.page, c.x, c.y)
                pageCount = maxOf(pageCount, c.page + 1)
            }
        }
        set(l.copy(pageCount = pageCount, items = items, dock = dock))
    }

    /** After a grid size change: shrink widgets to fit, move icons that no longer fit, and trim the dock. */
    fun normalize(cols: Int, rows: Int) {
        val l = _layout.value
        val widgets = l.widgets.map { wd ->
            val w = wd.w.coerceIn(1, cols)
            val h = wd.h.coerceIn(1, rows)
            wd.copy(w = w, h = h, x = wd.x.coerceIn(0, cols - w), y = wd.y.coerceIn(0, rows - h))
        }
        val seen = HashSet<Cell>()
        val keep = ArrayList<HomeItem>()
        val misfits = ArrayList<String>()
        for (item in l.items.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            val c = Cell(item.page, item.x, item.y)
            val underWidget = widgets.any { it.covers(item.page, item.x, item.y) }
            if (item.x < cols && item.y < rows && !underWidget && seen.add(c)) keep += item else misfits += item.key
        }
        misfits += l.dock.drop(cols)
        if (misfits.isEmpty() && widgets == l.widgets) return
        var result = Layout(l.pageCount, keep, l.dock.take(cols), widgets)
        for (key in misfits) {
            val c = freeCell(result, cols, rows)
            result = result.copy(pageCount = maxOf(result.pageCount, c.page + 1), items = result.items + HomeItem(key, c.page, c.x, c.y))
        }
        set(result)
    }

    // ---- Pages (edit mode) ----

    /** A new empty page at the end; returns its index. */
    fun addPage(): Int {
        val l = _layout.value
        set(l.copy(pageCount = l.pageCount + 1))
        return l.pageCount
    }

    /** Removes page [page] and everything on it. Returns the ids of the widgets that were on it. */
    fun deletePage(page: Int): List<Int> {
        val l = _layout.value
        if (l.pageCount <= 1 || page !in 0 until l.pageCount) return emptyList()
        val gone = l.widgets.filter { it.page == page }.map { it.id }
        fun shift(p: Int) = if (p > page) p - 1 else p
        set(
            l.copy(
                pageCount = l.pageCount - 1,
                items = l.items.filter { it.page != page }.map { it.copy(page = shift(it.page)) },
                widgets = l.widgets.filter { it.page != page }.map { it.copy(page = shift(it.page)) },
            ),
        )
        return gone
    }

    // ---- Widgets ----

    /** Puts a new widget in the first space big enough, trying [page] first; returns the page it landed on. */
    fun addWidget(id: Int, w: Int, h: Int, page: Int, cols: Int, rows: Int): Int {
        val l = _layout.value
        val ww = w.coerceIn(1, cols)
        val hh = h.coerceIn(1, rows)
        val order = listOf(page.coerceIn(0, l.pageCount - 1)) + (0 until l.pageCount).filter { it != page }
        for (p in order) for (y in 0..rows - hh) for (x in 0..cols - ww) {
            if (fits(l, p, x, y, ww, hh, null, cols, rows)) {
                set(l.copy(widgets = l.widgets + HomeWidget(id, p, x, y, ww, hh)))
                return p
            }
        }
        val p = l.pageCount
        set(l.copy(pageCount = p + 1, widgets = l.widgets + HomeWidget(id, p, 0, 0, ww, hh)))
        return p
    }

    /** Moves a widget on its page; does nothing if it wouldn't fit there. */
    fun moveWidget(id: Int, x: Int, y: Int, cols: Int, rows: Int) {
        val l = _layout.value
        val wd = l.widgets.firstOrNull { it.id == id } ?: return
        if ((wd.x == x && wd.y == y) || !fits(l, wd.page, x, y, wd.w, wd.h, id, cols, rows)) return
        set(l.copy(widgets = l.widgets.map { if (it.id == id) it.copy(x = x, y = y) else it }))
    }

    fun canResize(id: Int, w: Int, h: Int, cols: Int, rows: Int): Boolean {
        val l = _layout.value
        val wd = l.widgets.firstOrNull { it.id == id } ?: return false
        return w >= 1 && h >= 1 && fits(l, wd.page, wd.x, wd.y, w, h, id, cols, rows)
    }

    fun resizeWidget(id: Int, w: Int, h: Int, cols: Int, rows: Int) {
        if (!canResize(id, w, h, cols, rows)) return
        val l = _layout.value
        set(l.copy(widgets = l.widgets.map { if (it.id == id) it.copy(w = w, h = h) else it }))
    }

    fun removeWidget(id: Int) {
        val l = _layout.value
        set(l.copy(widgets = l.widgets.filterNot { it.id == id }))
    }

    private fun fits(l: Layout, page: Int, x: Int, y: Int, w: Int, h: Int, ignore: Int?, cols: Int, rows: Int): Boolean {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return false
        for (cx in x until x + w) for (cy in y until y + h) {
            if (l.at(page, cx, cy) != null) return false
            if (l.widgets.any { it.id != ignore && it.covers(page, cx, cy) }) return false
        }
        return true
    }

    // ---- First run ----

    val initialized: Boolean get() = sp.contains(KEY)

    /** Dock: phone, messages, browser, camera. First page: a few Google apps in the bottom row. */
    fun seed(context: Context, apps: List<AppInfo>, cols: Int, rows: Int) {
        if (initialized) return
        val pm = context.packageManager
        fun pkgFor(intent: Intent): String? =
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" }
                ?: pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
        fun keyFor(pkg: String?) = pkg?.let { p -> apps.firstOrNull { it.packageName == p && it.isMainUser }?.key }

        val dock = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        ).mapNotNull { keyFor(pkgFor(it)) }.distinct().take(cols)

        val favourites = listOf(
            "com.android.vending",
            "com.google.android.apps.photos",
            "com.google.android.gm",
            "com.google.android.youtube",
            "com.google.android.apps.maps",
            "com.android.settings",
        ).mapNotNull { keyFor(it) }.filterNot { it in dock }.take(cols)
        val items = favourites.mapIndexed { i, key -> HomeItem(key, 0, i, rows - 1) }

        set(Layout(1, items, dock))
    }

    // ---- Helpers ----

    private fun freeCell(l: Layout, cols: Int, rows: Int): Cell {
        for (p in 0 until l.pageCount) for (y in 0 until rows) for (x in 0 until cols) {
            if (l.isFree(p, x, y)) return Cell(p, x, y)
        }
        return Cell(l.pageCount, 0, 0)
    }

    private fun load(): Layout = runCatching {
        val o = JSONObject(sp.getString(KEY, null) ?: return Layout())
        val items = o.getJSONArray("items").let { a ->
            (0 until a.length()).map { i ->
                val it = a.getJSONObject(i)
                HomeItem(it.getString("k"), it.getInt("p"), it.getInt("x"), it.getInt("y"))
            }
        }
        val dock = o.getJSONArray("dock").let { a -> (0 until a.length()).map { a.getString(it) } }
        val widgets = o.optJSONArray("widgets")?.let { a ->
            (0 until a.length()).map { i ->
                val it = a.getJSONObject(i)
                HomeWidget(it.getInt("i"), it.getInt("p"), it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h"))
            }
        }.orEmpty()
        Layout(o.optInt("pages", 1), items, dock, widgets)
    }.getOrDefault(Layout())

    private fun toJson(l: Layout): String = JSONObject()
        .put("pages", l.pageCount)
        .put("dock", JSONArray(l.dock))
        .put(
            "items",
            JSONArray().apply {
                l.items.forEach { put(JSONObject().put("k", it.key).put("p", it.page).put("x", it.x).put("y", it.y)) }
            },
        )
        .put(
            "widgets",
            JSONArray().apply {
                l.widgets.forEach {
                    put(JSONObject().put("i", it.id).put("p", it.page).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h))
                }
            },
        )
        .toString()

    companion object {
        const val KEY = "layout"
    }
}
