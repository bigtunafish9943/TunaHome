package com.tunastream.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * A section's row of apps. When [remember] is on, moving up or down into this section lands on
 * the app you were on last time you were here, instead of whichever app happens to be nearest.
 */
class SectionList @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {
    var remember = false

    /** The position of the app last chosen in this section, or -1. */
    private var lastPosition = -1

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            val position = getChildAdapterPosition(child)
            if (position != NO_POSITION) {
                lastPosition = position
            }
        }
    }

    /** Forgets the remembered app (the section's apps changed). */
    fun forget() {
        lastPosition = -1
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        // Arriving from another section: offer only the remembered app, if it's on screen
        if (remember && !hasFocus() && lastPosition >= 0 &&
            (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)
        ) {
            val child = layoutManager?.findViewByPosition(lastPosition)
            if (child != null && child.isFocusable && child.visibility == View.VISIBLE &&
                child.left < width && child.right > 0
            ) {
                views.add(child)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }
}
