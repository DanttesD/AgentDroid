package com.termux.shared.terminal.io

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import com.termux.shared.terminal.io.extrakeys.ExtraKeyButton
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.shared.terminal.io.extrakeys.SpecialButton
import com.termux.view.TerminalView
import java.util.function.IntConsumer

open class TerminalExtraKeys : ExtraKeysView.IExtraKeysView {

    private val mTerminalView: TerminalView

    constructor(terminalView: TerminalView) {
        mTerminalView = terminalView
    }

    override fun onExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton?, button: Button?) {
        if (buttonInfo!!.isMacro()) {
            val keys: Array<String> = (buttonInfo!!.getKey() as java.lang.String).split(" ")
            var ctrlDown = false
            var altDown = false
            var shiftDown = false
            var fnDown = false
            for (key in keys) {
                if (SpecialButton.CTRL.getKey().equals(key)) {
                    ctrlDown = true
                } else if (SpecialButton.ALT.getKey().equals(key)) {
                    altDown = true
                } else if (SpecialButton.SHIFT.getKey().equals(key)) {
                    shiftDown = true
                } else if (SpecialButton.FN.getKey().equals(key)) {
                    fnDown = true
                } else {
                    onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
                    ctrlDown = false
                    altDown = false
                    shiftDown = false
                    fnDown = false
                }
            }
        } else {
            onTerminalExtraKeyButtonClick(view, buttonInfo!!.getKey(), false, false, false, false)
        }
    }

    protected open fun onTerminalExtraKeyButtonClick(view: View, key: String, ctrlDown: Boolean, altDown: Boolean, shiftDown: Boolean, fnDown: Boolean) {
        if (ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
            val keyCode: Int? = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.get(key)
            if (keyCode == null) {
                return
            }
            var metaState = 0
            if (ctrlDown) {
                metaState = metaState or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            }
            if (altDown) {
                metaState = metaState or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
            }
            if (shiftDown) {
                metaState = metaState or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
            }
            if (fnDown) {
                metaState = metaState or KeyEvent.META_FUNCTION_ON
            }

            val keyEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            mTerminalView.onKeyDown(keyCode, keyEvent)
        } else {
            // not a control char
            (key as java.lang.String).codePoints().forEach(IntConsumer { codePoint -> mTerminalView.inputCodePoint(codePoint, ctrlDown, altDown) })
        }
    }

    override fun performExtraKeyButtonHapticFeedback(view: View, buttonInfo: ExtraKeyButton?, button: Button?): Boolean {
        return false
    }
}
