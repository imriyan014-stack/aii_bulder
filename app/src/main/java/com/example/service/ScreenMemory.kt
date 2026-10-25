package com.example.service

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class UiElem(
    val label: String,
    val cx: Int,
    val cy: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val seen: Long = System.currentTimeMillis()
)

/**
 * Remembers where every button / field was seen, per app (saved on the phone).
 * The agent uses it to go straight to the right place the next time instead of searching again.
 */
object ScreenMemory {
    private const val PREFS = "omni_screen_memory"
    private val map = ConcurrentHashMap<String, LinkedHashMap<String, UiElem>>()
    @Volatile private var lastSave = 0L
    @Volatile private var loaded = false
    @Volatile var currentPkg: String = ""
    @Volatile var currentElems: List<UiElem> = emptyList()

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("m", null) ?: return
            val root = JSONObject(raw)
            root.keys().forEach { pkg ->
                val arr = root.getJSONArray(pkg)
                val m = LinkedHashMap<String, UiElem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    m[o.getString("l")] = UiElem(o.getString("l"), o.getInt("x"), o.getInt("y"),
                        o.optBoolean("c"), o.optBoolean("e"), o.optLong("t"))
                }
                map[pkg] = m
            }
        } catch (_: Exception) {}
    }

    fun record(ctx: Context?, pkg: String, elems: List<UiElem>) {
        currentPkg = pkg
        currentElems = elems
        val m = map.getOrPut(pkg) { LinkedHashMap() }
        synchronized(m) {
            for (e in elems) { m.remove(e.label); m[e.label] = e }
            while (m.size > 120) m.remove(m.keys.first())
        }
        val now = System.currentTimeMillis()
        if (ctx != null && now - lastSave > 8000L) { lastSave = now; save(ctx) }
    }

    fun save(ctx: Context) {
        try {
            val root = JSONObject()
            map.forEach { (pkg, m) ->
                val arr = JSONArray()
                synchronized(m) {
                    m.values.forEach { e ->
                        arr.put(JSONObject().put("l", e.label).put("x", e.cx).put("y", e.cy)
                            .put("c", e.clickable).put("e", e.editable).put("t", e.seen))
                    }
                }
                root.put(pkg, arr)
            }
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("m", root.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Search remembered elements. pkgHint may be a package name, an app label fragment, or blank (= all apps). */
    fun lookup(ctx: Context, query: String, pkgHint: String): List<Triple<String, String, UiElem>> {
        val q = query.trim().lowercase()
        val hint = pkgHint.trim().lowercase()
        val out = mutableListOf<Triple<String, String, UiElem>>()
        map.forEach { (pkg, m) ->
            if (hint.isNotBlank() && !pkg.lowercase().contains(hint) &&
                InstalledAppsIndex.list(ctx).none { it.pkg == pkg && it.label.lowercase().contains(hint) }) return@forEach
            synchronized(m) {
                m.values.filter { q.isBlank() || it.label.lowercase().contains(q) }.forEach { out.add(Triple(pkg, it.label, it)) }
            }
        }
        return out.sortedByDescending { it.third.seen }.take(15)
    }

    fun describeCurrent(limit: Int = 30): String {
        val e = currentElems
        val useful = e.filter { it.clickable || it.editable }.ifEmpty { e }
        return useful.take(limit).joinToString("; ") {
            "${it.label.take(40)}@${it.cx},${it.cy}${if (it.editable) "[input]" else ""}"
        }
    }
}

object InstalledAppsIndex {
    data class AppInfo(val label: String, val pkg: String)
    @Volatile private var cache: List<AppInfo> = emptyList()
    @Volatile private var at = 0L

    fun list(ctx: Context): List<AppInfo> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - at < 10 * 60_000L) return cache
        return try {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            cache = pm.queryIntentActivities(intent, 0)
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            at = now
            cache
        } catch (_: Exception) { cache }
    }

    fun search(ctx: Context, q: String): List<AppInfo> {
        val s = q.trim().lowercase()
        return list(ctx).filter { it.label.lowercase().contains(s) || it.pkg.lowercase().contains(s) }.take(10)
    }

    fun summary(ctx: Context, limit: Int = 150): String =
        list(ctx).take(limit).joinToString(", ") { it.label }
}
