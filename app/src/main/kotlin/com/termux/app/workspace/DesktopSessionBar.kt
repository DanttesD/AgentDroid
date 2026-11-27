package com.termux.app.workspace

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Two independent actions; animation invalidates only the programs button drawable.*/
class DesktopSessionBar : LinearLayout {
    @JvmField val desktop: TextView
    @JvmField val programs: TextView
    @JvmField val border: WorkspaceTransferBorder?

    constructor(context: Context, label: String, switchDesktop: Runnable, openPrograms: Runnable) : super(context) {
        setOrientation(LinearLayout.HORIZONTAL)
        desktop = AgentStatusUi.chip(context, label, AgentStatusUi.tr(context, "Переключение терминала и рабочего стола", "Switch terminal and desktop"), switchDesktop)
        desktop.setTag("session-x11-desktop")
        programs = AgentStatusUi.chip(context, AgentStatusUi.tr(context, "Программы", "Programs"), AgentStatusUi.tr(context, "Программы рабочего стола", "Desktop programs"), openPrograms)
        programs.setTag("session-x11-programs")
        val left = LinearLayout.LayoutParams(0, -2, 1.0f)
        val right = LinearLayout.LayoutParams(0, -2, 1.0f)
        right.leftMargin = WorkspaceDialogs.dp(context, 6.0f)
        addView(desktop, left)
        addView(programs, right)
        border = WorkspaceTransferBorder(context.getResources().getDisplayMetrics().density)
        programs.setBackground(LayerDrawable(arrayOf<Drawable?>(programs.getBackground(), border)))
    }

    fun working(active: Boolean) {
        border!!.active(active)
    }

    protected override fun onDetachedFromWindow() {
        border!!.active(false)
        super.onDetachedFromWindow()
    }

    protected override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        if (border != null && visibility != View.VISIBLE) {
            border.active(false)
        }
    }
}
