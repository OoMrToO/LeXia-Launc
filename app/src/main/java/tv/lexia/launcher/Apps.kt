package tv.lexia.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

/** [tv]: made for TV (it is in the TV's own app list); the rest are phone-style apps on the box. */
class AppEntry(val key: String, val pkg: String, val label: String, val info: ActivityInfo, val tv: Boolean)

object Apps {
    /** Corner radius (design units) of an app card of [size] for the chosen shape; icon shapes use the icon. */
    fun radius(shape: String, size: Int): Int = when (shape) {
        "sharp" -> 3
        "circle" -> size / 2
        "square" -> size / 4
        else -> D.CARD
    }

    fun iconShape(shape: String) = shape == "circle" || shape == "square"

    private val hues = HashMap<String, Int>()

    /** The main colour of an app's icon, opaque; 0 when the icon is grey or black and white. */
    fun mainColor(key: String, art: Drawable?): Int {
        hues[key]?.let { return it }
        if (art == null) return 0
        val c = try {
            val d = art.constantState?.newDrawable()?.mutate() ?: art
            val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 24, 24)
            d.draw(Canvas(b))
            val hsv = FloatArray(3)
            var x = 0.0
            var y = 0.0
            var w = 0.0
            var sat = 0.0
            for (i in 0 until 24) for (j in 0 until 24) {
                val p = b.getPixel(i, j)
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                val k = (hsv[1] * hsv[2]).toDouble()
                if (k < 0.15) continue
                val r = Math.toRadians(hsv[0].toDouble())
                x += Math.cos(r) * k
                y += Math.sin(r) * k
                sat += hsv[1] * k
                w += k
            }
            b.recycle()
            if (w < 8) 0 else {
                var hue = Math.toDegrees(Math.atan2(y, x)).toFloat()
                if (hue < 0) hue += 360f
                Color.HSVToColor(floatArrayOf(hue, (sat / w).toFloat().coerceIn(0.4f, 0.8f), 1f))
            }
        } catch (e: Exception) {
            0
        }
        hues[key] = c
        return c
    }

    /**
     * A card for an app with no TV picture (phone-style apps on boxes): a deep gradient of the
     * icon's own colour, the icon large in the middle and, when [name] is given, the name at the
     * bottom. Replaces the grey plate with a small icon, so the row looks of one piece.
     */
    fun iconCard(ui: Ui, card: FrameLayout, key: String, icon: Drawable?, name: String?, radius: Int, height: Int) {
        val c = mainColor(key, icon)
        val hsv = FloatArray(3)
        val top: Int
        val bottom: Int
        if (c == 0) {
            top = Color.parseColor("#3B3D44")
            bottom = Color.parseColor("#17181C")
        } else {
            Color.colorToHSV(c, hsv)
            top = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.85f, 0.46f))
            bottom = Color.HSVToColor(floatArrayOf((hsv[0] + 18f) % 360f, hsv[1], 0.16f))
        }
        card.background = ui.gradient(GradientDrawable.Orientation.TL_BR, top, bottom, radius = radius)
        // A soft light from the top left, like the glass on TV banners.
        val shine = android.view.View(card.context)
        shine.background = ui.gradient(
            GradientDrawable.Orientation.TOP_BOTTOM, Color.argb(34, 255, 255, 255), Color.TRANSPARENT, radius = radius
        )
        card.addView(shine, ui.fl(ui.match, ui.match))
        val img = ImageView(card.context)
        img.setImageDrawable(icon)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.elevation = ui.uf(6)
        val size = height * (if (name != null) 42 else 56) / 100
        val lp = ui.fl(ui.u(size), ui.u(size), Gravity.CENTER)
        if (name != null) lp.bottomMargin = ui.u(height * 14 / 100)
        card.addView(img, lp)
        if (name != null) {
            val t = ui.text(name, (height * 12 / 100).coerceIn(13, 19), Color.argb(225, 255, 255, 255), 600)
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            val tl = ui.fl(ui.match, ui.wrap, Gravity.BOTTOM)
            tl.setMargins(ui.u(14), 0, ui.u(14), ui.u(height * 9 / 100))
            card.addView(t, tl)
        }
    }

    /** The platforms' own apps, so a look-alike (a TV maker's helper) never wins over the real one. */
    private val platformPackages = listOf(
        "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        "disney" to listOf("com.disney.disneyplus"),
        "prime" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "amazon" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "apple" to listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv"),
        "mubi" to listOf("com.mubi"),
        "crunchyroll" to listOf("com.crunchyroll.crunchyroid"),
        "max" to listOf("com.wbd.stream", "com.hbo.hbonow")
    )

    /** "com.mstar.netflixo": a label that is only a package name, as some TVs show for their helper apps. */
    private val packageLike = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /**
     * The app of a streaming platform ("Netflix", "Disney Plus", "Amazon Prime Video") if it is on this TV:
     * the platform's own app first, then a TV app whose name matches; helper apps named like
     * "com.mstar.netflixo" never count.
     */
    fun forPlatform(ctx: Context, provider: String): AppEntry? {
        val low = provider.lowercase()
        val apps = all(ctx)
        for ((word, pkgs) in platformPackages) {
            if (word !in low) continue
            for (p in pkgs) apps.firstOrNull { it.pkg == p }?.let { return it }
        }
        val words = low.split(' ', '+', '-', '.').filter { it.length >= 3 && it !in setOf("video", "plus", "the", "app") }
        if (words.isEmpty()) return null
        return apps.filter { a -> !packageLike.matches(a.label) && words.any { a.label.lowercase().contains(it) } }
            .sortedWith(compareBy({ if (it.tv) 0 else 1 }, { it.label.length }))
            .firstOrNull()
    }

    /**
     * Every app that can be opened: TV apps and the phone-style ones some boxes carry (file manager,
     * the satellite receiver's own apps...). One entry per app, the TV entry when it has both.
     */
    fun all(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        for ((category, tv) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            val list = try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in list) {
                val ai = ri.activityInfo
                if (ai.packageName == ctx.packageName || seen.containsKey(ai.packageName)) continue
                val label = nameOf(pm, ri, tv) ?: continue
                seen[ai.packageName] = AppEntry("${ai.packageName}/${ai.name}", ai.packageName, label, ai, tv)
            }
        }
        return sorted(seen.values.toList(), Prefs(ctx).order)
    }

    /**
     * A readable name. Some TVs and boxes ship helper apps whose name is only their package
     * ("com.mstar.netflixo"): the app's own name is tried instead; a phone-style helper with no real
     * name is left out of the lists, a TV app gets its last word ("Netflixo").
     */
    private fun nameOf(pm: android.content.pm.PackageManager, ri: android.content.pm.ResolveInfo, tv: Boolean): String? {
        val label = try { ri.loadLabel(pm).toString().trim() } catch (e: Exception) { "" }
        if (label.isNotEmpty() && !packageLike.matches(label)) return label
        val app = try { ri.activityInfo.applicationInfo.loadLabel(pm).toString().trim() } catch (e: Exception) { "" }
        if (app.isNotEmpty() && !packageLike.matches(app)) return app
        if (!tv) return null
        return ri.activityInfo.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /**
     * The apps on the home screen (favourites). The first time, these are the TV apps that were
     * not hidden before; a TV app installed later joins them by itself, a phone-style app only
     * when it is added.
     */
    fun favorites(prefs: Prefs, all: List<AppEntry>): Set<String> {
        val tv = all.filter { it.tv }.map { it.key }
        val saved = prefs.favorites
        if (saved == null) {
            val hidden = prefs.hidden
            // A box with no TV apps at all: everything, as before.
            val start = (if (tv.isEmpty()) all.map { it.key } else tv).filter { it !in hidden }.toSet()
            prefs.favorites = start
            prefs.knownTv = tv.toSet()
            return start
        }
        val known = prefs.knownTv
        val added = tv.filter { it !in known }
        if (added.isEmpty()) return saved
        val now = saved + added
        prefs.favorites = now
        prefs.knownTv = known + added
        return now
    }

    /** What the home screen shows: the favourites, recent ones first if asked. */
    fun shown(ctx: Context, prefs: Prefs): List<AppEntry> {
        val all = all(ctx)
        val fav = favorites(prefs, all)
        val list = all.filter { it.key in fav }
        return when (prefs.appSort) {
            "recent" -> {
                val used = prefs.usedAt()
                list.sortedByDescending { used[it.key] ?: 0L }
            }
            "time" -> {
                // Apps you open at this time of day first; the hours around it count a little too.
                val usage = prefs.usage()
                val slot = Prefs.daySlot()
                fun score(k: String): Double {
                    val a = usage[k] ?: return 0.0
                    return a[slot] + 0.3 * (a[(slot + 1) % 4] + a[(slot + 3) % 4])
                }
                list.sortedByDescending { score(it.key) }
            }
            else -> list
        }
    }

    /** User order first, the rest alphabetically. */
    fun sorted(list: List<AppEntry>, order: List<String>): List<AppEntry> {
        val idx = HashMap<String, Int>()
        order.forEachIndexed { i, k -> idx[k] = i }
        return list.sortedWith(compareBy<AppEntry>({ idx[it.key] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
    }

    /** The platform's app is not on this TV: the TV's app store, searching for it. */
    fun findInStore(ctx: Context, name: String) {
        val q = android.net.Uri.encode(name)
        for (u in listOf("market://search?q=$q&c=apps", "https://play.google.com/store/search?q=$q&c=apps")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
            }
        }
        Glass.toast(ctx, "Uygulama mağazası açılamadı")
    }

    fun launchIntent(app: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN).setClassName(app.pkg, app.info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
