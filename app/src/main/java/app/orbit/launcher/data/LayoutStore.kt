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

data class Layout(
    val pageCount: Int = 1,
    val items: List<HomeItem> = emptyList(),
    val dock: List<String> = emptyList(),
) {
    fun at(page: Int, x: Int, y: Int) = items.firstOrNull { it.page == page && it.x == x && it.y == y }
    fun contains(key: String) = key in dock || items.any { it.key == key }
}

private data class Cell(val page: Int, val x: Int, val y: Int)

/** Home screen pages and dock, saved as JSON in the launcher's preferences. */
class LayoutStore(private val sp: SharedPreferences) {
    private val _layout = MutableStateFlow(load())
    val layout: StateFlow<Layout> = _layout

    private fun set(l: Layout) {
        val clean = dropEmptyPages(l)
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

    fun removePackage(pkg: String) {
        val l = _layout.value
        val gone = { k: String -> k.startsWith("$pkg/") }
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

        var items = l.items.filterNot { it.key == key || it.key == occupant?.key } + HomeItem(key, page, x, y)
        var dock = l.dock
        var pageCount = maxOf(l.pageCount, page + 1)
        when {
            occupant == null -> if (dockIndex >= 0) dock = dock - key
            from != null -> items = items + occupant.copy(page = from.page, x = from.x, y = from.y)
            dockIndex >= 0 -> dock = dock.toMutableList().also { it[dockIndex] = occupant.key }
            else -> {
                val c = freeCell(Layout(pageCount, items, dock), cols, rows)
                items = items + HomeItem(occupant.key, c.page, c.x, c.y)
                pageCount = maxOf(pageCount, c.page + 1)
            }
        }
        set(Layout(pageCount, items, dock))
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
                val c = if (from != null) Cell(from.page, from.x, from.y) else freeCell(Layout(pageCount, items, dock), cols, rows)
                items = items + HomeItem(displaced, c.page, c.x, c.y)
                pageCount = maxOf(pageCount, c.page + 1)
            }
        }
        set(Layout(pageCount, items, dock))
    }

    /** After a grid size change: move icons that no longer fit, and trim the dock. */
    fun normalize(cols: Int, rows: Int) {
        val l = _layout.value
        val seen = HashSet<Cell>()
        val keep = ArrayList<HomeItem>()
        val misfits = ArrayList<String>()
        for (item in l.items.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            val c = Cell(item.page, item.x, item.y)
            if (item.x < cols && item.y < rows && seen.add(c)) keep += item else misfits += item.key
        }
        misfits += l.dock.drop(cols)
        if (misfits.isEmpty()) return
        var result = Layout(l.pageCount, keep, l.dock.take(cols))
        for (key in misfits) {
            val c = freeCell(result, cols, rows)
            result = result.copy(pageCount = maxOf(result.pageCount, c.page + 1), items = result.items + HomeItem(key, c.page, c.x, c.y))
        }
        set(result)
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
            if (l.at(p, x, y) == null) return Cell(p, x, y)
        }
        return Cell(l.pageCount, 0, 0)
    }

    /** Like One UI: a page with nothing on it disappears (the first page always stays). */
    private fun dropEmptyPages(l: Layout): Layout {
        val used = l.items.map { it.page }.distinct().sorted()
        val renumber = used.withIndex().associate { (i, p) -> p to i }
        return l.copy(
            pageCount = maxOf(1, used.size),
            items = l.items.map { it.copy(page = renumber.getValue(it.page)) },
        )
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
        Layout(o.optInt("pages", 1), items, dock)
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
        .toString()

    companion object {
        const val KEY = "layout"
    }
}
