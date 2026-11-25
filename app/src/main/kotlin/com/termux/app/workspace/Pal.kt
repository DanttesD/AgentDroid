package com.termux.app.workspace

import android.content.Context
import com.agentdroid.agent.ThemeColors
import com.termux.app.agent.AppTheme

/** Colours of the workspace screens, taken from the app theme (Material You / AMOLED). Call [.init] before building views.*/
object Pal {
    @JvmField var ACCENT: Int = 0
    @JvmField var TEXT: Int = 0
    @JvmField var MUTED: Int = 0
    @JvmField var PANEL: Int = 0
    @JvmField var BORDER: Int = 0
    @JvmField var BG: Int = 0
    @JvmField var ONACCENT: Int = 0
    @JvmField var TEXT2: Int = 0
    @JvmField var CARD2: Int = 0
    @JvmField var TINT: Int = 0
    @JvmField var TINT_BORDER: Int = 0
    @JvmField var RIPPLE: Int = 0
    @JvmField var DANGER: Int = 0
    @JvmField var TRACK: Int = 0

    init {
        init0(ThemeColors.of(170.0f, true, true))
    }

    @JvmStatic
    fun init(c: Context) {
        init0(AppTheme.colors(c))
    }

    @JvmStatic
    private fun init0(t: ThemeColors) {
        ACCENT = t.p
        TEXT = t.on
        TEXT2 = t.on
        MUTED = t.on2
        PANEL = t.s1
        BORDER = t.line
        BG = t.bg
        ONACCENT = t.onp
        CARD2 = t.s2
        TINT = t.pc
        TINT_BORDER = t.s3
        RIPPLE = t.p and 0xFFFFFF or 0x44000000
        DANGER = t.err
        TRACK = t.s3
    }
}
