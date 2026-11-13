package com.termux.terminal

import java.util.HashMap
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_BREAK
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F1
import android.view.KeyEvent.KEYCODE_F10
import android.view.KeyEvent.KEYCODE_F11
import android.view.KeyEvent.KEYCODE_F12
import android.view.KeyEvent.KEYCODE_F2
import android.view.KeyEvent.KEYCODE_F3
import android.view.KeyEvent.KEYCODE_F4
import android.view.KeyEvent.KEYCODE_F5
import android.view.KeyEvent.KEYCODE_F6
import android.view.KeyEvent.KEYCODE_F7
import android.view.KeyEvent.KEYCODE_F8
import android.view.KeyEvent.KEYCODE_F9
import android.view.KeyEvent.KEYCODE_FORWARD_DEL
import android.view.KeyEvent.KEYCODE_INSERT
import android.view.KeyEvent.KEYCODE_MOVE_END
import android.view.KeyEvent.KEYCODE_MOVE_HOME
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_1
import android.view.KeyEvent.KEYCODE_NUMPAD_2
import android.view.KeyEvent.KEYCODE_NUMPAD_3
import android.view.KeyEvent.KEYCODE_NUMPAD_4
import android.view.KeyEvent.KEYCODE_NUMPAD_5
import android.view.KeyEvent.KEYCODE_NUMPAD_6
import android.view.KeyEvent.KEYCODE_NUMPAD_7
import android.view.KeyEvent.KEYCODE_NUMPAD_8
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_COMMA
import android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
import android.view.KeyEvent.KEYCODE_NUMPAD_DOT
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS
import android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_NUM_LOCK
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_SYSRQ
import android.view.KeyEvent.KEYCODE_TAB

object KeyHandler {

    const val KEYMOD_ALT = 0x80000000.toInt()
    const val KEYMOD_CTRL = 0x40000000
    const val KEYMOD_SHIFT = 0x20000000
    const val KEYMOD_NUM_LOCK = 0x10000000

    private val TERMCAP_TO_KEYCODE = HashMap<String, Int>()

    init {
        // terminfo: http://pubs.opengroup.org/onlinepubs/7990989799/xcurses/terminfo.html
        // termcap: http://man7.org/linux/man-pages/man5/termcap.5.html
        TERMCAP_TO_KEYCODE["%i"] = KEYMOD_SHIFT or KEYCODE_DPAD_RIGHT
        TERMCAP_TO_KEYCODE["#2"] = KEYMOD_SHIFT or KEYCODE_MOVE_HOME // Shifted home
        TERMCAP_TO_KEYCODE["#4"] = KEYMOD_SHIFT or KEYCODE_DPAD_LEFT
        TERMCAP_TO_KEYCODE["*7"] = KEYMOD_SHIFT or KEYCODE_MOVE_END // Shifted end key
        TERMCAP_TO_KEYCODE["k1"] = KEYCODE_F1
        TERMCAP_TO_KEYCODE["k2"] = KEYCODE_F2
        TERMCAP_TO_KEYCODE["k3"] = KEYCODE_F3
        TERMCAP_TO_KEYCODE["k4"] = KEYCODE_F4
        TERMCAP_TO_KEYCODE["k5"] = KEYCODE_F5
        TERMCAP_TO_KEYCODE["k6"] = KEYCODE_F6
        TERMCAP_TO_KEYCODE["k7"] = KEYCODE_F7
        TERMCAP_TO_KEYCODE["k8"] = KEYCODE_F8
        TERMCAP_TO_KEYCODE["k9"] = KEYCODE_F9
        TERMCAP_TO_KEYCODE["k;"] = KEYCODE_F10
        TERMCAP_TO_KEYCODE["F1"] = KEYCODE_F11
        TERMCAP_TO_KEYCODE["F2"] = KEYCODE_F12
        TERMCAP_TO_KEYCODE["F3"] = KEYMOD_SHIFT or KEYCODE_F1
        TERMCAP_TO_KEYCODE["F4"] = KEYMOD_SHIFT or KEYCODE_F2
        TERMCAP_TO_KEYCODE["F5"] = KEYMOD_SHIFT or KEYCODE_F3
        TERMCAP_TO_KEYCODE["F6"] = KEYMOD_SHIFT or KEYCODE_F4
        TERMCAP_TO_KEYCODE["F7"] = KEYMOD_SHIFT or KEYCODE_F5
        TERMCAP_TO_KEYCODE["F8"] = KEYMOD_SHIFT or KEYCODE_F6
        TERMCAP_TO_KEYCODE["F9"] = KEYMOD_SHIFT or KEYCODE_F7
        TERMCAP_TO_KEYCODE["FA"] = KEYMOD_SHIFT or KEYCODE_F8
        TERMCAP_TO_KEYCODE["FB"] = KEYMOD_SHIFT or KEYCODE_F9
        TERMCAP_TO_KEYCODE["FC"] = KEYMOD_SHIFT or KEYCODE_F10
        TERMCAP_TO_KEYCODE["FD"] = KEYMOD_SHIFT or KEYCODE_F11
        TERMCAP_TO_KEYCODE["FE"] = KEYMOD_SHIFT or KEYCODE_F12
        TERMCAP_TO_KEYCODE["kb"] = KEYCODE_DEL // backspace key
        TERMCAP_TO_KEYCODE["kd"] = KEYCODE_DPAD_DOWN // terminfo=kcud1, down-arrow key
        TERMCAP_TO_KEYCODE["kh"] = KEYCODE_MOVE_HOME
        TERMCAP_TO_KEYCODE["kl"] = KEYCODE_DPAD_LEFT
        TERMCAP_TO_KEYCODE["kr"] = KEYCODE_DPAD_RIGHT

        // K1=Upper left of keypad:
        TERMCAP_TO_KEYCODE["K1"] = KEYCODE_MOVE_HOME
        TERMCAP_TO_KEYCODE["K3"] = KEYCODE_PAGE_UP
        TERMCAP_TO_KEYCODE["K4"] = KEYCODE_MOVE_END
        TERMCAP_TO_KEYCODE["K5"] = KEYCODE_PAGE_DOWN
        TERMCAP_TO_KEYCODE["ku"] = KEYCODE_DPAD_UP
        TERMCAP_TO_KEYCODE["kB"] = KEYMOD_SHIFT or KEYCODE_TAB // termcap=kB, terminfo=kcbt: Back-tab
        TERMCAP_TO_KEYCODE["kD"] = KEYCODE_FORWARD_DEL // terminfo=kdch1, delete-character key
        TERMCAP_TO_KEYCODE["kDN"] = KEYMOD_SHIFT or KEYCODE_DPAD_DOWN // non-standard shifted arrow down
        TERMCAP_TO_KEYCODE["kF"] = KEYMOD_SHIFT or KEYCODE_DPAD_DOWN // terminfo=kind, scroll-forward key
        TERMCAP_TO_KEYCODE["kI"] = KEYCODE_INSERT
        TERMCAP_TO_KEYCODE["kN"] = KEYCODE_PAGE_UP
        TERMCAP_TO_KEYCODE["kP"] = KEYCODE_PAGE_DOWN
        TERMCAP_TO_KEYCODE["kR"] = KEYMOD_SHIFT or KEYCODE_DPAD_UP // terminfo=kri, scroll-backward key
        TERMCAP_TO_KEYCODE["kUP"] = KEYMOD_SHIFT or KEYCODE_DPAD_UP // non-standard shifted up
        TERMCAP_TO_KEYCODE["@7"] = KEYCODE_MOVE_END
        TERMCAP_TO_KEYCODE["@8"] = KEYCODE_NUMPAD_ENTER
    }

    @JvmStatic
    fun getCodeFromTermcap(termcap: String?, cursorKeysApplication: Boolean, keypadApplication: Boolean): String? {
        val keyCodeAndMod: Int = TERMCAP_TO_KEYCODE[termcap] ?: return null
        var keyCode = keyCodeAndMod
        var keyMod = 0
        if ((keyCode and KEYMOD_SHIFT) != 0) {
            keyMod = keyMod or KEYMOD_SHIFT
            keyCode = keyCode and KEYMOD_SHIFT.inv()
        }
        if ((keyCode and KEYMOD_CTRL) != 0) {
            keyMod = keyMod or KEYMOD_CTRL
            keyCode = keyCode and KEYMOD_CTRL.inv()
        }
        if ((keyCode and KEYMOD_ALT) != 0) {
            keyMod = keyMod or KEYMOD_ALT
            keyCode = keyCode and KEYMOD_ALT.inv()
        }
        if ((keyCode and KEYMOD_NUM_LOCK) != 0) {
            keyMod = keyMod or KEYMOD_NUM_LOCK
            keyCode = keyCode and KEYMOD_NUM_LOCK.inv()
        }
        return getCode(keyCode, keyMod, cursorKeysApplication, keypadApplication)
    }

    @JvmStatic
    fun getCode(keyCode: Int, keyMode: Int, cursorApp: Boolean, keypadApplication: Boolean): String? {
        var keyMode = keyMode
        val numLockOn = (keyMode and KEYMOD_NUM_LOCK) != 0
        keyMode = keyMode and KEYMOD_NUM_LOCK.inv()
        when (keyCode) {
            KEYCODE_DPAD_CENTER ->
                return "\r"

            KEYCODE_DPAD_UP ->
                return if (keyMode == 0) (if (cursorApp) "\u001bOA" else "\u001b[A") else transformForModifiers("\u001b[1", keyMode, 'A')
            KEYCODE_DPAD_DOWN ->
                return if (keyMode == 0) (if (cursorApp) "\u001bOB" else "\u001b[B") else transformForModifiers("\u001b[1", keyMode, 'B')
            KEYCODE_DPAD_RIGHT ->
                return if (keyMode == 0) (if (cursorApp) "\u001bOC" else "\u001b[C") else transformForModifiers("\u001b[1", keyMode, 'C')
            KEYCODE_DPAD_LEFT ->
                return if (keyMode == 0) (if (cursorApp) "\u001bOD" else "\u001b[D") else transformForModifiers("\u001b[1", keyMode, 'D')

            KEYCODE_MOVE_HOME ->
                // Note that KEYCODE_HOME is handled by the system and never delivered to applications.
                // On a Logitech k810 keyboard KEYCODE_MOVE_HOME is sent by FN+LeftArrow.
                return if (keyMode == 0) (if (cursorApp) "\u001bOH" else "\u001b[H") else transformForModifiers("\u001b[1", keyMode, 'H')
            KEYCODE_MOVE_END ->
                return if (keyMode == 0) (if (cursorApp) "\u001bOF" else "\u001b[F") else transformForModifiers("\u001b[1", keyMode, 'F')

            // An xterm can send function keys F1 to F4 in two modes: vt100 compatible or
            // not. Because Vim may not know what the xterm is sending, both types of keys
            // are recognized. The same happens for the <Home> and <End> keys.
            // normal vt100 ~
            // <F1> t_k1 <Esc>[11~ <xF1> <Esc>OP *<xF1>-xterm*
            // <F2> t_k2 <Esc>[12~ <xF2> <Esc>OQ *<xF2>-xterm*
            // <F3> t_k3 <Esc>[13~ <xF3> <Esc>OR *<xF3>-xterm*
            // <F4> t_k4 <Esc>[14~ <xF4> <Esc>OS *<xF4>-xterm*
            // <Home> t_kh <Esc>[7~ <xHome> <Esc>OH *<xHome>-xterm*
            // <End> t_@7 <Esc>[4~ <xEnd> <Esc>OF *<xEnd>-xterm*
            KEYCODE_F1 ->
                return if (keyMode == 0) "\u001bOP" else transformForModifiers("\u001b[1", keyMode, 'P')
            KEYCODE_F2 ->
                return if (keyMode == 0) "\u001bOQ" else transformForModifiers("\u001b[1", keyMode, 'Q')
            KEYCODE_F3 ->
                return if (keyMode == 0) "\u001bOR" else transformForModifiers("\u001b[1", keyMode, 'R')
            KEYCODE_F4 ->
                return if (keyMode == 0) "\u001bOS" else transformForModifiers("\u001b[1", keyMode, 'S')
            KEYCODE_F5 ->
                return transformForModifiers("\u001b[15", keyMode, '~')
            KEYCODE_F6 ->
                return transformForModifiers("\u001b[17", keyMode, '~')
            KEYCODE_F7 ->
                return transformForModifiers("\u001b[18", keyMode, '~')
            KEYCODE_F8 ->
                return transformForModifiers("\u001b[19", keyMode, '~')
            KEYCODE_F9 ->
                return transformForModifiers("\u001b[20", keyMode, '~')
            KEYCODE_F10 ->
                return transformForModifiers("\u001b[21", keyMode, '~')
            KEYCODE_F11 ->
                return transformForModifiers("\u001b[23", keyMode, '~')
            KEYCODE_F12 ->
                return transformForModifiers("\u001b[24", keyMode, '~')

            KEYCODE_SYSRQ ->
                return "\u001b[32~" // Sys Request / Print
            // Is this Scroll lock? case Cancel: return "\033[33~";
            KEYCODE_BREAK ->
                return "\u001b[34~" // Pause/Break

            KEYCODE_ESCAPE, KEYCODE_BACK ->
                return "\u001b"

            KEYCODE_INSERT ->
                return transformForModifiers("\u001b[2", keyMode, '~')
            KEYCODE_FORWARD_DEL ->
                return transformForModifiers("\u001b[3", keyMode, '~')

            KEYCODE_PAGE_UP ->
                return "\u001b[5~"
            KEYCODE_PAGE_DOWN ->
                return "\u001b[6~"
            KEYCODE_DEL -> {
                val prefix = if ((keyMode and KEYMOD_ALT) == 0) "" else "\u001b"
                // Just do what xterm and gnome-terminal does:
                return prefix + (if ((keyMode and KEYMOD_CTRL) == 0) "\u007F" else "\u0008")
            }
            KEYCODE_NUM_LOCK ->
                return if (keypadApplication) {
                    "\u001bOP"
                } else {
                    null
                }
            KEYCODE_SPACE ->
                // If ctrl is not down, return null so that it goes through normal input processing (which may e.g. cause a
                // combining accent to be written):
                return if ((keyMode and KEYMOD_CTRL) == 0) null else "\u0000"
            KEYCODE_TAB ->
                // This is back-tab when shifted:
                return if ((keyMode and KEYMOD_SHIFT) == 0) "\t" else "\u001b[Z"
            KEYCODE_ENTER ->
                return if ((keyMode and KEYMOD_ALT) == 0) "\r" else "\u001b\r"

            KEYCODE_NUMPAD_ENTER ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'M') else "\n"
            KEYCODE_NUMPAD_MULTIPLY ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'j') else "*"
            KEYCODE_NUMPAD_ADD ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'k') else "+"
            KEYCODE_NUMPAD_COMMA ->
                return ","
            KEYCODE_NUMPAD_DOT ->
                return if (numLockOn) {
                    if (keypadApplication) "\u001bOn" else "."
                } else {
                    // DELETE
                    transformForModifiers("\u001b[3", keyMode, '~')
                }
            KEYCODE_NUMPAD_SUBTRACT ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'm') else "-"
            KEYCODE_NUMPAD_DIVIDE ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'o') else "/"
            KEYCODE_NUMPAD_0 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'p') else "0"
                } else {
                    // INSERT
                    transformForModifiers("\u001b[2", keyMode, '~')
                }
            KEYCODE_NUMPAD_1 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'q') else "1"
                } else {
                    // END
                    if (keyMode == 0) (if (cursorApp) "\u001bOF" else "\u001b[F") else transformForModifiers("\u001b[1", keyMode, 'F')
                }
            KEYCODE_NUMPAD_2 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'r') else "2"
                } else {
                    // DOWN
                    if (keyMode == 0) (if (cursorApp) "\u001bOB" else "\u001b[B") else transformForModifiers("\u001b[1", keyMode, 'B')
                }
            KEYCODE_NUMPAD_3 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 's') else "3"
                } else {
                    // PGDN
                    "\u001b[6~"
                }
            KEYCODE_NUMPAD_4 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 't') else "4"
                } else {
                    // LEFT
                    if (keyMode == 0) (if (cursorApp) "\u001bOD" else "\u001b[D") else transformForModifiers("\u001b[1", keyMode, 'D')
                }
            KEYCODE_NUMPAD_5 ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'u') else "5"
            KEYCODE_NUMPAD_6 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'v') else "6"
                } else {
                    // RIGHT
                    if (keyMode == 0) (if (cursorApp) "\u001bOC" else "\u001b[C") else transformForModifiers("\u001b[1", keyMode, 'C')
                }
            KEYCODE_NUMPAD_7 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'w') else "7"
                } else {
                    // HOME
                    if (keyMode == 0) (if (cursorApp) "\u001bOH" else "\u001b[H") else transformForModifiers("\u001b[1", keyMode, 'H')
                }
            KEYCODE_NUMPAD_8 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'x') else "8"
                } else {
                    // UP
                    if (keyMode == 0) (if (cursorApp) "\u001bOA" else "\u001b[A") else transformForModifiers("\u001b[1", keyMode, 'A')
                }
            KEYCODE_NUMPAD_9 ->
                return if (numLockOn) {
                    if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'y') else "9"
                } else {
                    // PGUP
                    "\u001b[5~"
                }
            KEYCODE_NUMPAD_EQUALS ->
                return if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'X') else "="
        }

        return null
    }

    private fun transformForModifiers(start: String, keymod: Int, lastChar: Char): String {
        val modifier: Int
        when (keymod) {
            KEYMOD_SHIFT ->
                modifier = 2
            KEYMOD_ALT ->
                modifier = 3
            (KEYMOD_SHIFT or KEYMOD_ALT) ->
                modifier = 4
            KEYMOD_CTRL ->
                modifier = 5
            KEYMOD_SHIFT or KEYMOD_CTRL ->
                modifier = 6
            KEYMOD_ALT or KEYMOD_CTRL ->
                modifier = 7
            KEYMOD_SHIFT or KEYMOD_ALT or KEYMOD_CTRL ->
                modifier = 8
            else ->
                return start + lastChar
        }
        return start + (";" + modifier) + lastChar
    }
}
