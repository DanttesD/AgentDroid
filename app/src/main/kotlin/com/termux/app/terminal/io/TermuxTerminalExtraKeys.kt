package com.termux.app.terminal.io

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.app.terminal.TermuxTerminalSessionClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.shared.terminal.io.TerminalExtraKeys
import com.termux.view.TerminalView

open class TermuxTerminalExtraKeys : TerminalExtraKeys {

    @JvmField var mTermuxTerminalViewClient: TermuxTerminalViewClient? = null
    @JvmField var mTermuxTerminalSessionClient: TermuxTerminalSessionClient? = null

    constructor(terminalView: TerminalView?, termuxTerminalViewClient: TermuxTerminalViewClient?, termuxTerminalSessionClient: TermuxTerminalSessionClient?) : super(terminalView!!) {
        mTermuxTerminalViewClient = termuxTerminalViewClient
        mTermuxTerminalSessionClient = termuxTerminalSessionClient
    }

    @SuppressLint("RtlHardcoded")
    public override fun onTerminalExtraKeyButtonClick(view: View, key: String, ctrlDown: Boolean, altDown: Boolean, shiftDown: Boolean, fnDown: Boolean) {
        if ("KEYBOARD".equals(key)) {
            if (mTermuxTerminalViewClient != null) {
                mTermuxTerminalViewClient!!.onToggleSoftKeyboardRequest()
            }
        } else if ("DRAWER".equals(key)) {
            val drawerLayout: DrawerLayout? = mTermuxTerminalViewClient!!.getActivity().getDrawer()
            if (drawerLayout!!.isDrawerOpen(Gravity.LEFT)) {
                drawerLayout.closeDrawer(Gravity.LEFT)
            } else {
                drawerLayout.openDrawer(Gravity.LEFT)
            }
        } else if ("PASTE".equals(key)) {
            if (mTermuxTerminalSessionClient != null) {
                mTermuxTerminalSessionClient!!.onPasteTextFromClipboard(null)
            }
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
        }
    }
}
