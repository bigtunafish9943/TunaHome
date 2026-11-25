package com.tunastream.home

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * The home screen's up-and-down scrolling. When [aligned] says a part of the screen is placed by
 * the home screen itself (the section you're on, glided to a fixed height), the usual
 * "scroll just enough to show it" jump is left out, so the two don't fight.
 */
class HomeScroll @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ScrollView(context, attrs, defStyle) {
    /** Given a part of the screen (in scrolled coordinates), whether the home screen places it. */
    var aligned: ((Rect) -> Boolean)? = null

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (aligned?.invoke(rect) == true) {
            return 0
        }
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }
}
