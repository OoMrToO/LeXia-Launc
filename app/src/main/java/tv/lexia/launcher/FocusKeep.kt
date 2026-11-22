package tv.lexia.launcher

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/**
 * Where the remote was, kept across a page drawing itself again (back from the player or a film
 * page): the same film's poster (the same one of them when a film is in several rows) gets the
 * focus back, instead of the page jumping to its top or to a tab.
 */
internal object FocusKeep {
    /** [around]: the views holding the film, nearest first; a row drawn again keeps its holder. */
    class Spot(val key: String, val nth: Int, val around: List<View>)

    /** The film (or episode) a view stands for: its own tag or one of its parents'. */
    private fun keyOf(view: View?): String? {
        var v = view
        while (v != null) {
            (v.getTag(R.id.lexia_pick) as? Pick)?.let { return "pick:" + it.key }
            (v.tag as? String)?.takeIf { it.startsWith("episode:") }?.let { return it }
            v = v.parent as? View
        }
        return null
    }

    /** The view's own key (not a parent's): the views that can take the focus back. */
    private fun ownKey(v: View): String? =
        (v.getTag(R.id.lexia_pick) as? Pick)?.let { "pick:" + it.key } ?: (v.tag as? String)?.takeIf { it.startsWith("episode:") }

    private fun keyed(root: View, key: String, out: MutableList<View>) {
        if (ownKey(root) == key) out.add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) keyed(root.getChildAt(i), key, out)
    }

    /** Where the focus is now on [activity]; null when it is not on a film. */
    fun save(activity: Activity): Spot? {
        val focus = activity.currentFocus ?: return null
        val key = keyOf(focus) ?: return null
        val root = activity.window.decorView
        val all = ArrayList<View>()
        keyed(root, key, all)
        var holder: View? = focus
        while (holder != null && ownKey(holder) != key) holder = holder.parent as? View
        val around = ArrayList<View>()
        var up = holder?.parent as? View
        while (up != null && around.size < 6) {
            around.add(up)
            up = up.parent as? View
        }
        return Spot(key, all.indexOf(holder).coerceAtLeast(0), around)
    }

    /** Puts the focus back on [spot]'s film; false when it is no longer on the page. */
    fun restore(activity: Activity, spot: Spot?): Boolean {
        spot ?: return false
        val all = ArrayList<View>()
        keyed(activity.window.decorView, spot.key, all)
        // The film in the same row as before (a row is often drawn again in its old holder), else
        // the same one of its copies, else any.
        fun inside(v: View, holder: View): Boolean {
            var x = v.parent as? View
            while (x != null) {
                if (x === holder) return true
                x = x.parent as? View
            }
            return false
        }
        val sameRow = spot.around.asSequence()
            .filter { it.isAttachedToWindow }
            .mapNotNull { h -> all.filter { inside(it, h) }.takeIf { c -> c.isNotEmpty() } }
            .firstOrNull()
        val view = sameRow?.let { if (it.size == 1) it[0] else all.getOrNull(spot.nth)?.takeIf { v -> v in it } ?: it[0] }
            ?: all.getOrNull(spot.nth) ?: all.firstOrNull() ?: return false
        val target = if (view.isFocusable) view else (view as? ViewGroup)?.let { firstFocusable(it) } ?: return false
        return target.requestFocus()
    }

    /** The first thing on [g] the remote can stand on (the film was taken off the page). */
    fun first(g: ViewGroup): Boolean = firstFocusable(g)?.requestFocus() == true

    private fun firstFocusable(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }
}
