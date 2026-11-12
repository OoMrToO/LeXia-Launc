package tv.lexia.launcher

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A mouse or an air remote's pointer on screens built for the arrow keys. Pointing at a card selects
 * it, but only along the row that is already selected: the home screen slides rows up and down as the
 * selection moves, and selecting across rows would make it run away under a pointer. The wheel moves
 * between rows like the up and down keys; a click opens whatever is under the pointer.
 */
object Pointer {
    private var lastX = -1f
    private var lastY = -1f
    private var lastWheel = 0L

    /** Call from Activity.dispatchGenericMotionEvent; returns true when the event was used. */
    fun handle(a: Activity, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v == 0f) return false
                val now = System.currentTimeMillis()
                if (now - lastWheel < 180) return true
                lastWheel = now
                val code = if (v > 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                if (abs(e.rawX - lastX) < 4 && abs(e.rawY - lastY) < 4) return false
                lastX = e.rawX
                lastY = e.rawY
                val target = find(a.window.decorView, e.rawX.toInt(), e.rawY.toInt()) ?: return false
                if (target.isFocused) return false
                val current = a.currentFocus
                if (current != null && rowOf(current) !== rowOf(target)) return false
                target.requestFocusFromTouch()
                return false
            }
        }
        return false
    }

    /** The topmost focusable view under the point, or null. */
    private fun find(v: View, x: Int, y: Int): View? {
        if (v.visibility != View.VISIBLE || v.alpha < 0.05f) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width || y >= loc[1] + v.height) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) find(v.getChildAt(i), x, y)?.let { return it }
        }
        return if (v.isFocusable && v.isEnabled) v else null
    }

    /** The horizontal line of cards or buttons a view belongs to. */
    private fun rowOf(v: View): Any? {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p is LinearLayout && p.orientation == LinearLayout.HORIZONTAL && p.childCount > 1) return p
            p = p.parent
        }
        return null
    }
}
