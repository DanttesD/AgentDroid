package com.termux.app.workspace

import android.R
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.termux.shared.terminal.io.TerminalExtraKeys
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.shared.terminal.io.extrakeys.SpecialButton
import com.termux.view.TerminalView
import java.util.function.Supplier

/** Chat extra keys edit a composer; they never write into the bridge PTY.*/
class ChatExtraKeys : TerminalExtraKeys {
    private val keys: ExtraKeysView
    private val target: Supplier<EditText?>

    constructor(terminal: TerminalView?, keys: ExtraKeysView, target: Supplier<EditText?>) : super(terminal!!) {
        this.keys = keys
        this.target = target
    }

    private fun down(b: SpecialButton): Boolean {
        return java.lang.Boolean.TRUE.equals(keys.readSpecialButton(b, true))
    }

    public override fun onTerminalExtraKeyButtonClick(source: View, key: String, ctrl: Boolean, alt: Boolean, shift: Boolean, fn: Boolean) {
        var ctrl = ctrl
        var alt = alt
        var shift = shift
        var fn = fn
        ctrl = ctrl or down(SpecialButton.CTRL)
        alt = alt or down(SpecialButton.ALT)
        shift = shift or down(SpecialButton.SHIFT)
        fn = fn or down(SpecialButton.FN)
        val input: EditText? = target.get()
        if (input == null || !input.isEnabled()) {
            return
        }
        if (key.equals("KEYBOARD")) {
            input.requestFocus()
            (input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?)!!.showSoftInput(input, 0)
            return
        }
        if (key.equals("PASTE")) {
            input.onTextContextMenuItem(R.id.paste)
            return
        }
        if (key.equals("DRAWER")) {
            return
        }
        var code: Int? = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.get(key)
        if (code == null && key.length == 1 && Character.toLowerCase(key[0]) >= 'a' && Character.toLowerCase(key[0]) <= 'z' && (ctrl || alt)) {
            code = KeyEvent.KEYCODE_A + Character.toLowerCase(key[0]).code - 'a'.code
        }
        if (code != null) {
            val meta: Int = (if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0) or (if (fn) KeyEvent.META_FUNCTION_ON else 0)
            input.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, meta))
            input.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, code, 0, meta))
        } else if (!ctrl && !alt) {
            val start: Int = Math.max(0, input.getSelectionStart())
            val end: Int = Math.max(0, input.getSelectionEnd())
            input.getText().replace(Math.min(start, end), Math.max(start, end), key)
        }
    }
}
