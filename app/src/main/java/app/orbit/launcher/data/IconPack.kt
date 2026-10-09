package app.orbit.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * An installed icon pack in the Nova / ADW format: an app whose appfilter.xml
 * maps `ComponentInfo{package/activity}` to one of its drawables.
 */
class IconPack private constructor(
    private val res: Resources,
    private val pkg: String,
    private val exact: Map<String, String>,
    private val byPackage: Map<String, String>,
) {
    fun iconFor(cn: ComponentName): Drawable? {
        val name = exact["${cn.packageName}/${cn.className}"] ?: byPackage[cn.packageName] ?: return null
        val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", pkg).takeIf { it != 0 }
            ?: return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    data class Installed(val packageName: String, val label: String)

    companion object {
        private val ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
        )
        private val COMPONENT = Regex("""ComponentInfo\{([^/]+)/([^}]+)\}""")

        fun installed(context: Context): List<Installed> {
            val pm = context.packageManager
            return ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), 0) }
                .map { Installed(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }

        fun load(context: Context, pkg: String): IconPack? = runCatching {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val xmlId = res.getIdentifier("appfilter", "xml", pkg)
            val parser: XmlPullParser = if (xmlId != 0) {
                res.getXml(xmlId)
            } else {
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "UTF-8")
                }
            }
            val exact = HashMap<String, String>()
            val byPackage = HashMap<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    val m = component?.let { COMPONENT.find(it) }
                    if (m != null && !drawable.isNullOrBlank()) {
                        val p = m.groupValues[1]
                        val cls = m.groupValues[2].let { if (it.startsWith(".")) p + it else it }
                        exact.putIfAbsent("$p/$cls", drawable)
                        byPackage.putIfAbsent(p, drawable)
                    }
                }
                parser.next()
            }
            IconPack(res, pkg, exact, byPackage)
        }.getOrNull()
    }
}
