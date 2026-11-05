package com.termux.app.workspace;

import android.content.Context;

import com.agentdroid.agent.ThemeColors;
import com.termux.app.agent.AppTheme;

/** Colours of the workspace screens, taken from the app theme (Material You / AMOLED). Call {@link #init} before building views. */
public final class Pal {
    public static int ACCENT, TEXT, MUTED, PANEL, BORDER, BG, ONACCENT, TEXT2, CARD2, TINT, TINT_BORDER, RIPPLE, DANGER, TRACK;

    static { init0(ThemeColors.of(170, true, true)); }

    private Pal() {}

    public static void init(Context c) { init0(AppTheme.colors(c)); }

    private static void init0(ThemeColors t) {
        ACCENT = t.p; TEXT = t.on; TEXT2 = t.on; MUTED = t.on2; PANEL = t.s1; BORDER = t.line; BG = t.bg; ONACCENT = t.onp;
        CARD2 = t.s2; TINT = t.pc; TINT_BORDER = t.s3; RIPPLE = (t.p & 0xFFFFFF) | 0x44000000; DANGER = t.err; TRACK = t.s3;
    }
}
