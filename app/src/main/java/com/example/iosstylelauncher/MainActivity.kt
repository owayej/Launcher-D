package com.example.iosstylelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Item(val id: String, var label: String, val icon: Drawable?, var kids: MutableList<Item>? = null) {
    val isFolder get() = kids != null
}

class Dots(c: Context) : View(c) {
    var n = 1; var cur = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val r = 3.5f * resources.displayMetrics.density; val g = r * 3.2f
        val x0 = width / 2f - (n - 1) * g / 2
        for (i in 0 until n) { p.color = if (i == cur) Color.WHITE else 0x66FFFFFF; cv.drawCircle(x0 + i * g, height / 2f, r, p) }
    }
}

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var strip: FrameLayout
    private lateinit var dragLayer: FrameLayout
    private lateinit var dockBg: View
    private lateinit var dots: Dots
    private val h = Handler(Looper.getMainLooper())
    private val apps = LinkedHashMap<String, Item>()
    private var pages = ArrayList<ArrayList<Item>>()
    private val dock = ArrayList<Item>()
    private val views = HashMap<Item, View>()
    private val sx = HashMap<View, SpringAnimation>(); private val sy = HashMap<View, SpringAnimation>()
    private var stripAnim: SpringAnimation? = null
    private var W = 0; private var H = 0; private var topI = 0; private var botI = 0
    private var dn = 1f; private var cw = 0f; private var ch = 0f; private var isz = 0f
    private var gTop = 0f; private var dockTop = 0f; private var dockH = 0f; private var rows = 5
    private var cur = 0; private var loaded = false
    private var folderOv: View? = null; private var menu: PopupMenu? = null
    private var downX = 0f; private var downY = 0f; private var moved = false; private var lpFired = false; private var swiping = false
    private var press: Item? = null; private var vt: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val lpRun = Runnable {
        if (!moved) {
            lpFired = true; root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val p = press
            if (p != null) showMenu(p, views[p], null)
            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
        }
    }
    private var drag: Item? = null; private var dragV: View? = null; private var fromDock = false
    private var ox = 0f; private var oy = 0f; private var px = 0f; private var py = 0f
    private var lastX = 0f; private var lastY = 0f; private var lastT = 0L; private var speed = 0f
    private var hovT: Item? = null; private var armed = false
    private var armRun: Runnable? = null; private var rRun: Runnable? = null; private var rIdx = -1; private var edgeRun: Runnable? = null
    private val tick = object : Runnable { override fun run() { if (drag == null) return; hover(); h.postDelayed(this, 50) } }
    private val roundOutline = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.225f) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        dn = resources.displayMetrics.density
        root = FrameLayout(this); strip = FrameLayout(this); dragLayer = FrameLayout(this); dockBg = View(this); dots = Dots(this)
        root.addView(dockBg); root.addView(strip); root.addView(dots)
        root.addView(dragLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, i -> topI = i.systemWindowInsetTop; botI = i.systemWindowInsetBottom; if (W > 0) relayout(); i }
        root.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ ->
            val nw = r - l; val nh = bt - t
            if (nw > 0 && (nw != W || nh != H)) { W = nw; H = nh; relayout() }
        }
        root.setOnTouchListener { _, e -> onTouch(e) }
    }

    override fun onResume() { super.onResume(); if (loaded && drag == null) { sync(); rebuildViews() } }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (i?.action == Intent.ACTION_MAIN) { closeFolder(); menu?.dismiss(); if (loaded) goPage(0) } }
    override fun onBackPressed() { if (folderOv != null) closeFolder() }

    private fun scan(): List<Item> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0).filter { it.activityInfo.packageName != packageName }.map {
            val cn = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            Item(cn.flattenToString(), it.loadLabel(packageManager).toString(), it.loadIcon(packageManager))
        }.sortedBy { it.label.lowercase() }
    }

    private fun sync() {
        val found = scan(); apps.clear(); found.forEach { apps[it.id] = it }
        val placed = HashSet<String>(); pages = ArrayList(); dock.clear()
        val s = getSharedPreferences("layout", 0).getString("s", null)
        if (s != null) try {
            val j = JSONObject(s)
            fun get(id: String): Item? = apps[id]?.also { placed.add(id) }
            val pa = j.getJSONArray("p")
            for (a in 0 until pa.length()) {
                val pg = ArrayList<Item>(); val ar = pa.getJSONArray(a)
                for (b in 0 until ar.length()) {
                    val o = ar.get(b)
                    if (o is JSONObject) {
                        val kids = ArrayList<Item>(); val ka = o.getJSONArray("k")
                        for (c in 0 until ka.length()) get(ka.getString(c))?.let { kids.add(it) }
                        if (kids.size > 1) pg.add(Item(o.getString("f"), o.getString("n"), null, kids)) else kids.firstOrNull()?.let { pg.add(it) }
                    } else get(o as String)?.let { pg.add(it) }
                }
                pages.add(pg)
            }
            val da = j.getJSONArray("d")
            for (a in 0 until da.length()) get(da.getString(a))?.let { dock.add(it) }
        } catch (e: Exception) { pages = ArrayList(); dock.clear(); placed.clear() }
        if (pages.isEmpty()) pages.add(ArrayList())
        found.filter { it.id !in placed }.forEach { pages.last().add(it) }
        normalize(); cur = cur.coerceIn(0, pages.size - 1)
    }

    private fun save() {
        val j = JSONObject()
        val pa = JSONArray()
        pages.forEach { p -> val ar = JSONArray(); p.forEach { ar.put(if (it.isFolder) JSONObject().put("f", it.id).put("n", it.label).put("k", JSONArray(it.kids!!.map { k -> k.id })) else it.id) }; pa.put(ar) }
        j.put("p", pa).put("d", JSONArray(dock.map { it.id }))
        getSharedPreferences("layout", 0).edit().putString("s", j.toString()).apply()
    }

    private fun normalize() {
        val cap = rows * 4; var i = 0
        while (i < pages.size) {
            while (pages[i].size > cap) { val x = pages[i].removeAt(pages[i].size - 1); if (i + 1 == pages.size) pages.add(ArrayList()); pages[i + 1].add(0, x) }
            i++
        }
        pages.removeAll { it.isEmpty() }; if (pages.isEmpty()) pages.add(ArrayList())
        cur = cur.coerceIn(0, pages.size - 1)
    }

    private fun relayout() {
        cw = W / 4f; isz = min(cw * 0.74f, 64 * dn); dockH = isz + 28 * dn
        dockTop = H - botI - dockH - 8 * dn
        val dotsY = dockTop - 26 * dn; gTop = topI + 14 * dn
        val gridH = dotsY - gTop - 4 * dn
        rows = max(3, (gridH / (isz + 22 * dn)).toInt()); ch = gridH / rows
        dockBg.layoutParams = FrameLayout.LayoutParams((W - 24 * dn).toInt(), dockH.toInt())
        dockBg.translationX = 12 * dn; dockBg.translationY = dockTop
        dockBg.background = GradientDrawable().apply { cornerRadius = 30 * dn; setColor(0x44FFFFFF) }
        dots.layoutParams = FrameLayout.LayoutParams(W, (16 * dn).toInt()); dots.translationY = dotsY
        if (!loaded) { loaded = true; sync() } else normalize()
        rebuildViews()
    }

    private fun resizeStrip() { strip.layoutParams = FrameLayout.LayoutParams(W * pages.size, H); dots.n = pages.size; dots.cur = cur; dots.invalidate() }

    private fun rebuildViews() {
        views.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }; views.clear(); sx.values.forEach { it.cancel() }; sy.values.forEach { it.cancel() }; sx.clear(); sy.clear()
        resizeStrip(); strip.translationX = -cur * W.toFloat()
        pages.forEach { p -> p.forEach { views[it] = makeView(it, true).also { v -> strip.addView(v) } } }
        dock.forEach { views[it] = makeView(it, false).also { v -> root.addView(v) } }
        dragLayer.bringToFront(); layoutAll(false)
    }

    private fun layoutAll(anim: Boolean) {
        pages.forEachIndexed { p, pg -> pg.forEachIndexed { i, it -> if (it !== drag) views[it]?.let { v -> moveTo(v, (i % 4) * cw + p * W, gTop + (i / 4) * ch, anim) } } }
        dock.forEachIndexed { i, it -> views[it]?.let { v -> moveTo(v, i * cw, dockTop + (dockH - isz) / 2, anim) } }
    }

    private fun moveTo(v: View, x: Float, y: Float, anim: Boolean) {
        sx[v]?.cancel(); sy[v]?.cancel()
        if (!anim) { v.translationX = x; v.translationY = y; return }
        sx[v] = spring(v, DynamicAnimation.TRANSLATION_X, x); sy[v] = spring(v, DynamicAnimation.TRANSLATION_Y, y)
    }

    private fun spring(v: View, p: DynamicAnimation.ViewProperty, t: Float): SpringAnimation =
        SpringAnimation(v, p, t).apply { spring.setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.85f) }.also { it.start() }

    private fun goPage(n: Int) {
        cur = n.coerceIn(0, pages.size - 1); dots.cur = cur; dots.invalidate()
        stripAnim?.cancel(); stripAnim = spring(strip, DynamicAnimation.TRANSLATION_X, -cur * W.toFloat())
    }

    private fun makeView(item: Item, label: Boolean): View {
        val v = FrameLayout(this); v.layoutParams = FrameLayout.LayoutParams(cw.toInt(), ch.toInt())
        val ic: View = if (item.isFolder) folderIcon(item) else ImageView(this).apply { setImageDrawable(dr(item)); scaleType = ImageView.ScaleType.FIT_XY }
        ic.outlineProvider = roundOutline; ic.clipToOutline = true
        v.addView(ic, FrameLayout.LayoutParams(isz.toInt(), isz.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        if (label) {
            val t = TextView(this).apply { text = item.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setShadowLayer(4f, 0f, 1f, 0x88000000.toInt()) }
            v.addView(t, FrameLayout.LayoutParams(cw.toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = (isz + 5 * dn).toInt() })
        }
        return v
    }

    private fun folderIcon(f: Item): View {
        val c = FrameLayout(this); c.background = GradientDrawable().apply { cornerRadius = isz * 0.225f; setColor(0x66FFFFFF) }
        val m = isz * 0.24f; val g = isz * 0.065f; val pad = (isz - 3 * m - 2 * g) / 2
        f.kids!!.take(9).forEachIndexed { i, k ->
            val iv = ImageView(this); iv.setImageDrawable(dr(k)); iv.scaleType = ImageView.ScaleType.FIT_XY; iv.outlineProvider = roundOutline; iv.clipToOutline = true
            val lp = FrameLayout.LayoutParams(m.toInt(), m.toInt()); lp.leftMargin = (pad + (i % 3) * (m + g)).toInt(); lp.topMargin = (pad + (i / 3) * (m + g)).toInt(); c.addView(iv, lp)
        }
        return c
    }

    private fun launch(item: Item, anchor: View?) {
        val cn = ComponentName.unflattenFromString(item.id) ?: return
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            if (anchor != null) startActivity(i, ActivityOptions.makeScaleUpAnimation(anchor, 0, 0, anchor.width, anchor.height).toBundle()) else startActivity(i)
        } catch (e: Exception) { }
    }

    private fun dr(i: Item): Drawable? = i.icon?.constantState?.newDrawable()?.mutate() ?: i.icon

    private fun pkgOf(item: Item) = ComponentName.unflattenFromString(item.id)?.packageName

    private fun showMenu(item: Item, anchor: View?, inFolder: Item?) {
        if (anchor == null) return
        val m = PopupMenu(this, anchor); menu = m
        if (item.isFolder) m.menu.add("Rename") else {
            m.menu.add("App info"); m.menu.add("Uninstall")
            if (inFolder != null) m.menu.add("Remove from folder")
            else if (dock.contains(item)) m.menu.add("Remove from dock") else if (dock.size < 4) m.menu.add("Add to dock")
        }
        m.setOnMenuItemClickListener { mi ->
            when (mi.title.toString()) {
                "Rename" -> rename(item)
                "App info" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkgOf(item))))
                "Uninstall" -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkgOf(item))))
                "Remove from dock" -> { dock.remove(item); pages[cur].add(item); commit() }
                "Add to dock" -> { pages.forEach { it.remove(item) }; dock.add(item); commit() }
                "Remove from folder" -> inFolder?.let { ungroup(it, item) }
            }
            true
        }
        m.show()
    }

    private fun commit() { normalize(); save(); rebuildViews() }

    private fun ungroup(f: Item, k: Item) {
        f.kids!!.remove(k); val p = pages.first { it.contains(f) }; val i = p.indexOf(f)
        p.add(i + 1, k)
        if (f.kids!!.size < 2) { f.kids!!.firstOrNull()?.let { p[i] = it; p.remove(k); p.add(i + 1, k) } ?: p.remove(f) }
        closeFolder(); commit()
    }

    private fun rename(f: Item) {
        val e = EditText(this); e.setText(f.label)
        AlertDialog.Builder(this).setTitle("Folder name").setView(e).setPositiveButton("OK") { _, _ ->
            f.label = e.text.toString().ifBlank { "Folder" }; closeFolder(); save(); rebuildViews()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openFolder(f: Item) {
        closeFolder()
        val ov = FrameLayout(this); ov.setBackgroundColor(0x77000000); ov.setOnClickListener { closeFolder() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val title = TextView(this).apply { text = f.label; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, (12 * dn).toInt()); setOnClickListener { rename(f) } }
        val pad = (8 * dn).toInt()
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(pad, pad * 2, pad, pad); background = GradientDrawable().apply { cornerRadius = 34 * dn; setColor(0xCC30303C.toInt()) } }
        f.kids!!.forEach { k ->
            val v = makeView(k, true)
            v.setOnClickListener { launch(k, v); closeFolder() }
            v.setOnLongClickListener { showMenu(k, v, f); true }
            grid.addView(v)
        }
        col.addView(title); col.addView(grid)
        ov.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); root.addView(ov); folderOv = ov
        col.scaleX = 0.3f; col.scaleY = 0.3f; col.alpha = 0f
        col.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    private fun closeFolder() { folderOv?.let { root.removeView(it) }; folderOv = null }

    private fun hit(x: Float, y: Float): Item? {
        if (y > dockTop) return dock.getOrNull((x / cw).toInt())
        val row = ((y - gTop) / ch).toInt(); val col = (x / cw).toInt()
        if (row < 0 || col < 0 || col > 3 || y < gTop) return null
        return pages.getOrNull(cur)?.getOrNull(row * 4 + col)
    }

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; lpFired = false; swiping = false; stripAnim?.cancel()
                press = hit(e.x, e.y); vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                h.postDelayed(lpRun, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e); val dx = e.x - downX; val dy = e.y - downY
                if (drag != null) { dragMove(e.x, e.y); return true }
                if (!moved && hypot(dx, dy) > slop) {
                    moved = true; h.removeCallbacks(lpRun)
                    val p = press
                    if (p != null && lpFired) { menu?.dismiss(); startDrag(p, e.x, e.y); return true }
                    if (abs(dx) > abs(dy)) swiping = true
                }
                if (swiping) { val d = if ((cur == 0 && dx > 0) || (cur == pages.size - 1 && dx < 0)) dx * 0.35f else dx; strip.translationX = -cur * W + d }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(lpRun); vt?.addMovement(e)
                if (drag != null) drop()
                else if (swiping) {
                    vt?.computeCurrentVelocity(1000); val vx = vt?.xVelocity ?: 0f; val dx = e.x - downX; var np = cur
                    if (dx < -W * 0.2f || vx < -800) np++ else if (dx > W * 0.2f || vx > 800) np--
                    goPage(np)
                } else if (!moved && !lpFired && press != null && e.actionMasked == MotionEvent.ACTION_UP) {
                    val p = press!!; if (p.isFolder) openFolder(p) else launch(p, views[p])
                }
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun startDrag(item: Item, x: Float, y: Float) {
        val v = views[item] ?: return; sx[v]?.cancel(); sy[v]?.cancel()
        fromDock = dock.contains(item)
        val rx = v.translationX + (if (v.parent === strip) strip.translationX else 0f); val ry = v.translationY
        (v.parent as ViewGroup).removeView(v); dragLayer.addView(v); v.translationX = rx; v.translationY = ry
        ox = x - rx; oy = y - ry; px = x; py = y; lastX = x; lastY = y; lastT = SystemClock.uptimeMillis(); speed = 0f
        drag = item; dragV = v; v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
        if (fromDock) { dock.remove(item); layoutAll(true) }
        h.post(tick)
    }

    private fun dragMove(x: Float, y: Float) {
        val v = dragV ?: return; v.translationX = x - ox; v.translationY = y - oy
        val n = SystemClock.uptimeMillis(); val dt = max(1L, n - lastT)
        speed = 0.6f * speed + 0.4f * hypot(x - lastX, y - lastY) / dt
        lastX = x; lastY = y; lastT = n; px = x; py = y
    }

    private fun hover() {
        val d = drag ?: return
        if (SystemClock.uptimeMillis() - lastT > 80) speed = 0f
        val slow = speed < 0.4f * dn; val x = px; val y = py
        val edge = if (x < 22 * dn) -1 else if (x > W - 22 * dn) 1 else 0
        if (edge != 0 && edgeRun == null) { edgeRun = Runnable { edgeRun = null; flip(edge) }; h.postDelayed(edgeRun!!, 700) }
        else if (edge == 0) cancelEdge()
        if (y > dockTop) { clearHov(); cancelReorder(); return }
        val pg = pages[cur]; val col = (x / cw).toInt().coerceIn(0, 3); val row = ((y - gTop) / ch).toInt()
        if (row < 0 || row >= rows) { clearHov(); return }
        var idx = row * 4 + col; val here = pg.indexOf(d); val t = pg.getOrNull(idx)
        val k = if (hovT === t) 0.75f else 0.5f; val cx = col * cw + cw / 2; val cy = gTop + row * ch + isz / 2
        val can = t != null && t !== d && !d.isFolder && abs(x - cx) < isz * k && abs(y - cy) < isz * k
        if (can) {
            cancelReorder()
            if (hovT !== t) { clearHov(); hovT = t }
            if (!armed) {
                if (slow && armRun == null) { armRun = Runnable { arm() }; h.postDelayed(armRun!!, 380) }
                else if (!slow && armRun != null) { h.removeCallbacks(armRun!!); armRun = null }
            }
        } else {
            if (hovT != null) clearHov()
            if (idx >= pg.size) idx = pg.size - 1
            if (here >= 0 && idx != here && slow) {
                if (rIdx != idx) {
                    cancelReorder(); rIdx = idx; val target = idx
                    rRun = Runnable { rRun = null; rIdx = -1; val p = pages[cur]; val f = p.indexOf(d); if (f >= 0) { p.removeAt(f); p.add(min(target, p.size), d); layoutAll(true) } }
                    h.postDelayed(rRun!!, 200)
                }
            } else if (rIdx != -1 && (!slow || idx == here)) cancelReorder()
        }
    }

    private fun arm() {
        armed = true; armRun = null; root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        hovT?.let { views[it]?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(150)?.start() }
        dragV?.animate()?.scaleX(0.75f)?.scaleY(0.75f)?.setDuration(150)?.start()
    }

    private fun clearHov() {
        armRun?.let { h.removeCallbacks(it) }; armRun = null
        if (armed) { hovT?.let { views[it]?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start() }; dragV?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(150)?.start() }
        armed = false; hovT = null
    }
    private fun cancelReorder() { rRun?.let { h.removeCallbacks(it) }; rRun = null; rIdx = -1 }
    private fun cancelEdge() { edgeRun?.let { h.removeCallbacks(it) }; edgeRun = null }

    private fun flip(e: Int) {
        val d = drag ?: return; val to = cur + e; if (to < 0) return
        if (to >= pages.size) { if (!fromDock && pages[cur].size <= 1) return; pages.add(ArrayList()) }
        clearHov(); cancelReorder(); pages.forEach { it.remove(d) }; pages[to].add(d); fromDock = false
        cur = to; resizeStrip(); layoutAll(true); goPage(to)
    }

    private fun drop() {
        val d = drag ?: return; val v = dragV!!; drag = null; h.removeCallbacks(tick); cancelEdge(); cancelReorder()
        if (armed && hovT != null) { val t = hovT!!; armed = false; hovT = null; group(t, d, v); return }
        clearHov()
        val onDock = py > dockTop
        if (onDock && !d.isFolder && dock.size < 4) { pages.forEach { it.remove(d) }; dock.add(min(dock.size, (px / (W / (dock.size + 1f))).toInt()), d) }
        else if (fromDock) pages[cur].add(d)
        val pi = pages.indexOfFirst { it.contains(d) }
        val tx: Float; val ty: Float
        if (pi >= 0) { val i = pages[pi].indexOf(d); tx = (i % 4) * cw + (pi - cur) * W; ty = gTop + (i / 4) * ch }
        else { val i = dock.indexOf(d); tx = i * cw; ty = dockTop + (dockH - isz) / 2 }
        v.animate().translationX(tx).translationY(ty).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).withEndAction { commit() }.start()
    }

    private fun group(t: Item, d: Item, v: View) {
        pages.forEach { it.remove(d) }
        val pi = pages.indexOfFirst { it.contains(t) }; val ti = pages[pi].indexOf(t)
        val tx = (ti % 4) * cw + (pi - cur) * W; val ty = gTop + (ti / 4) * ch
        if (t.isFolder) t.kids!!.add(d)
        else { val f = Item("f:" + System.nanoTime(), "Folder", null, arrayListOf(t, d)); pages[pi][ti] = f }
        v.animate().translationX(tx).translationY(ty).scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(220).withEndAction { commit() }.start()
    }
}
