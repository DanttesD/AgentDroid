package com.termux.app.workspace

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Keep independent panels usable when the keyboard reduces the viewport.*/
class AdaptiveWorkspaceLayout : ScrollView {
    constructor(context: Context) : super(context) {
        setFillViewport(true)
    }

    protected override fun measureChildWithMargins(child: View, parentWidthSpec: Int, widthUsed: Int, parentHeightSpec: Int, heightUsed: Int) {
        val params: ViewGroup.MarginLayoutParams? = child.getLayoutParams() as ViewGroup.MarginLayoutParams?
        if (params!!.height >= 0) {
            val widthSpec: Int = ViewGroup.getChildMeasureSpec(parentWidthSpec, getPaddingLeft() + getPaddingRight() + params.leftMargin + params.rightMargin + widthUsed, params.width)
            child.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
        } else {
            super.measureChildWithMargins(child, parentWidthSpec, widthUsed, parentHeightSpec, heightUsed)
        }
    }

    protected override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (getChildCount() > 0 && View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
            val body: LinearLayout? = getChildAt(0) as LinearLayout?
            val width: Int = Math.max(0, View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight() - body!!.getPaddingLeft() - body.getPaddingRight())
            var minimum: Int = body.getPaddingTop() + body.getPaddingBottom()
            val panelHeight: Int = Math.round(160 * getResources().getDisplayMetrics().density * Math.max(1f, getResources().getConfiguration().fontScale))
            var i = 0
            while (i < body.getChildCount()) {
                val child: View = body.getChildAt(i)
                if (child.getVisibility() == View.GONE) {
                    i++
                    continue
                }
                val params: LinearLayout.LayoutParams? = child.getLayoutParams() as LinearLayout.LayoutParams?
                minimum += params!!.topMargin + params.bottomMargin
                if (params.weight > 0) {
                    minimum += panelHeight
                } else {
                    val childWidth: Int = ViewGroup.getChildMeasureSpec(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), params.leftMargin + params.rightMargin, params.width)
                    val childHeight: Int = if (params.height >= 0) View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    child.measure(childWidth, childHeight)
                    minimum += child.getMeasuredHeight()
                }
                i++
            }
            body.getLayoutParams().height = Math.max(minimum, View.MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom())
        }
        super.onMeasure(widthSpec, heightSpec)
    }
}
