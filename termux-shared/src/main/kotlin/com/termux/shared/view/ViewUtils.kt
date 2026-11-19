package com.termux.shared.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.window.WindowManager
import androidx.window.WindowMetrics
import com.termux.shared.logger.Logger

object ViewUtils {

    /** Log root view events.*/
    @JvmField var VIEW_UTILS_LOGGING_ENABLED: Boolean = false

    private const val LOG_TAG: String = "ViewUtils"

    /**
     * Sets whether view utils logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     **/
    @JvmStatic
    fun setIsViewUtilsLoggingEnabled(value: Boolean) {
        VIEW_UTILS_LOGGING_ENABLED = value
    }

    /**
     * Check if a [View] is fully visible and not hidden or partially covered by another view.
     *
     * https://stackoverflow.com/a/51078418/14686958
     *
     * @param view The [View] to check.
     * @param statusBarHeight The status bar height received by [View.OnApplyWindowInsetsListener].
     * @return Returns `true` if view is fully visible.
     **/
    @JvmStatic
    fun isViewFullyVisible(view: View, statusBarHeight: Int): Boolean {
        val windowAndViewRects: Array<Rect>? = getWindowAndViewRects(view, statusBarHeight)
        if (windowAndViewRects == null) {
            return false
        }
        return windowAndViewRects[0].contains(windowAndViewRects[1])
    }

    /**
     * Get the [Rect] of a [View] and the  [Rect] of the window inside which it
     * exists.
     *
     * https://stackoverflow.com/a/51078418/14686958
     *
     * @param view The [View] inside the window whose [Rect] to get.
     * @param statusBarHeight The status bar height received by [View.OnApplyWindowInsetsListener].
     * @return Returns [Rect[]] if view is visible where Rect[0] will contain window
     * [Rect] and Rect[1] will contain view [Rect]. This will be `null`
     * if view is not visible.
     **/
    @JvmStatic
    fun getWindowAndViewRects(view: View?, statusBarHeight: Int): Array<Rect>? {
        if (view == null || !view.isShown()) {
            return null
        }

        val view_utils_logging_enabled: Boolean = VIEW_UTILS_LOGGING_ENABLED

        // windowRect - will hold available area where content remain visible to users
        // Takes into account screen decorations (e.g. statusbar)
        val windowRect = Rect()
        view.getWindowVisibleDisplayFrame(windowRect)

        // If there is actionbar, get his height
        var actionBarHeight = 0
        var isInMultiWindowMode = false
        val context: Context = view.getContext()
        if (context is AppCompatActivity) {
            val actionBar: ActionBar? = (context as AppCompatActivity).getSupportActionBar()
            if (actionBar != null) {
                actionBarHeight = actionBar.getHeight()
            }
            isInMultiWindowMode = (context as AppCompatActivity).isInMultiWindowMode()
        } else if (context is Activity) {
            val actionBar: android.app.ActionBar? = (context as Activity).getActionBar()
            if (actionBar != null) {
                actionBarHeight = actionBar.getHeight()
            }
            isInMultiWindowMode = (context as Activity).isInMultiWindowMode()
        }

        val displayOrientation: Int = getDisplayOrientation(context)

        // windowAvailableRect - takes into account actionbar and statusbar height
        var windowAvailableRect: Rect
        windowAvailableRect = Rect(windowRect.left, windowRect.top + actionBarHeight, windowRect.right, windowRect.bottom)

        // viewRect - holds position of the view in window
        // (methods as getGlobalVisibleRect, getHitRect, getDrawingRect can return different result,
        // when partialy visible)
        var viewRect: Rect
        val viewsLocationInWindow = IntArray(2)
        view.getLocationInWindow(viewsLocationInWindow)
        var viewLeft: Int = viewsLocationInWindow[0]
        var viewTop: Int = viewsLocationInWindow[1]

        if (view_utils_logging_enabled) {
            Logger.logVerbose(LOG_TAG, "getWindowAndViewRects:")
            Logger.logVerbose(LOG_TAG, "windowRect: " + toRectString(windowRect) + ", windowAvailableRect: " + toRectString(windowAvailableRect))
            Logger.logVerbose(LOG_TAG, "viewsLocationInWindow: " + toPointString(Point(viewLeft, viewTop)))
            Logger.logVerbose(LOG_TAG, "activitySize: " + toPointString(getDisplaySize(context, true)) + ", displaySize: " + toPointString(getDisplaySize(context, false)) + ", displayOrientation=" + displayOrientation)
        }

        if (isInMultiWindowMode) {
            if (displayOrientation == Configuration.ORIENTATION_PORTRAIT) {
                // The windowRect.top of the window at the of split screen mode should start right
                // below the status bar
                if (statusBarHeight != windowRect.top) {
                    if (view_utils_logging_enabled) {
                        Logger.logVerbose(LOG_TAG, "Window top does not equal statusBarHeight " + statusBarHeight + " in multi-window portrait mode. Window is possibly bottom app in split screen mode. Adding windowRect.top " + windowRect.top + " to viewTop.")
                    }
                    viewTop += windowRect.top
                } else {
                    if (view_utils_logging_enabled) {
                        Logger.logVerbose(LOG_TAG, "windowRect.top equals statusBarHeight " + statusBarHeight + " in multi-window portrait mode. Window is possibly top app in split screen mode.")
                    }
                }
            } else if (displayOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                // If window is on the right in landscape mode of split screen, the viewLeft actually
                // starts at windowRect.left instead of 0 returned by getLocationInWindow
                viewLeft += windowRect.left
            }
        }

        val viewRight: Int = viewLeft + view.getWidth()
        val viewBottom: Int = viewTop + view.getHeight()
        viewRect = Rect(viewLeft, viewTop, viewRight, viewBottom)

        if (displayOrientation == Configuration.ORIENTATION_LANDSCAPE && viewRight > windowAvailableRect.right) {
            if (view_utils_logging_enabled) {
                Logger.logVerbose(LOG_TAG, "viewRight " + viewRight + " is greater than windowAvailableRect.right " + windowAvailableRect.right + " in landscape mode. Setting windowAvailableRect.right to viewRight since it may not include navbar height.")
            }
            windowAvailableRect.right = viewRight
        }

        return arrayOf<Rect>(windowAvailableRect, viewRect)
    }

    /**
     * Check if [Rect] r2 is above r2. An empty rectangle never contains another rectangle.
     *
     * @param r1 The base rectangle.
     * @param r2 The rectangle being tested that should be above.
     * @return Returns `true` if r2 is above r1.
     **/
    @JvmStatic
    fun isRectAbove(r1: Rect, r2: Rect): Boolean {
        // check for empty first
        return r1.left < r1.right && r1.top < r1.bottom && r1.left <= r2.left && r1.bottom >= r2.bottom
    }

    /**
     * Get device orientation.
     *
     * Related: https://stackoverflow.com/a/29392593/14686958
     *
     * @param context The [Context] to check with.
     * @return [Configuration.ORIENTATION_PORTRAIT] or [Configuration.ORIENTATION_LANDSCAPE].
     **/
    @JvmStatic
    fun getDisplayOrientation(context: Context): Int {
        val size: Point = getDisplaySize(context, false)
        return if (size.x < size.y) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Get device display size.
     *
     * @param context The [Context] to check with. It must be [Activity] context, otherwise
     *                android will throw:
     *                `java.lang.IllegalArgumentException: Used non-visual Context to obtain an instance of WindowManager. Please use an Activity or a ContextWrapper around one instead.`
     * @param activitySize The set to [true], then size returned will be that of the activity
     *                     and can be smaller than physical display size in multi-window mode.
     * @return Returns the display size as [Point].
     **/
    @JvmStatic
    fun getDisplaySize(context: Context, activitySize: Boolean): Point {
        // android.view.WindowManager.getDefaultDisplay() and Display.getSize() are deprecated in
        // API 30 and give wrong values in API 30 for activitySize=false in multi-window
        val windowManager = WindowManager(context)
        var windowMetrics: WindowMetrics
        if (activitySize) {
            windowMetrics = windowManager.getCurrentWindowMetrics()
        } else {
            windowMetrics = windowManager.getMaximumWindowMetrics()
        }
        return Point(windowMetrics.getBounds().width(), windowMetrics.getBounds().height())
    }

    /** Convert [Rect] to [String].*/
    @JvmStatic
    fun toRectString(rect: Rect?): String {
        if (rect == null) {
            return "null"
        }
        return "(" + rect.left + "," + rect.top + "), (" + rect.right + "," + rect.bottom + ")"
    }

    /** Convert [Point] to [String].*/
    @JvmStatic
    fun toPointString(point: Point?): String {
        if (point == null) {
            return "null"
        }
        return "(" + point.x + "," + point.y + ")"
    }

    /** Get the [Activity] associated with the [Context] if available.*/
    @JvmStatic
    fun getActivity(context: Context): Activity? {
        var context = context
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context as Activity
            }
            context = (context as ContextWrapper).getBaseContext()
        }
        return null
    }

    /** Convert value in device independent pixels (dp) to pixels (px) units.*/
    @JvmStatic
    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.getResources().getDisplayMetrics()).toInt()
    }

    @JvmStatic
    fun setLayoutMarginsInDp(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        val context: Context = view.getContext()
        setLayoutMarginsInPixels(view, dpToPx(context, left), dpToPx(context, top), dpToPx(context, right), dpToPx(context, bottom))
    }

    @JvmStatic
    fun setLayoutMarginsInPixels(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view.getLayoutParams() is ViewGroup.MarginLayoutParams) {
            val params: ViewGroup.MarginLayoutParams = view.getLayoutParams() as ViewGroup.MarginLayoutParams
            params.setMargins(left, top, right, bottom)
            view.setLayoutParams(params)
        }
    }
}
