package com.agentdroid.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class ThemeColorsTest {
    private static void check(String what, int fg, int bg, double min) {
        double c = ThemeColors.contrast(fg, bg);
        assertTrue(what + " contrast " + c + " < " + min, c >= min);
    }

    @Test public void contrastRulesHoldForEveryHueAndMode() {
        for (int h = 0; h < 360; h += 10) {
            for (int mode = 0; mode < 3; mode++) {
                ThemeColors t = ThemeColors.of(h, mode != 2, mode == 0);
                String m = "hue " + h + " mode " + mode + " ";
                for (int surface : new int[]{t.bg, t.s1, t.s2, t.s3}) {
                    check(m + "on", t.on, surface, 7.0);
                    check(m + "on2", t.on2, surface, 4.5);
                }
                check(m + "onp/p", t.onp, t.p, 4.5);
                check(m + "onpc/pc", t.onpc, t.pc, 4.5);
                check(m + "err/err2", t.err, t.err2, 4.5);
                check(m + "p/bg", t.p, t.bg, 3.0);
                check(m + "ok/s1", t.ok, t.s1, 3.0);
            }
        }
    }

    @Test public void amoledBackgroundIsPureBlack() {
        assertEquals(0xFF000000, ThemeColors.of(170, true, true).bg);
        assertNotEquals(0xFF000000, ThemeColors.of(170, true, false).bg);
    }

    @Test public void hueRoundTrip() {
        assertEquals(0f, ThemeColors.hueOf(0xFF808080), 0.01);
        assertEquals(120f, ThemeColors.hueOf(0xFF00FF00), 0.5);
        assertEquals(240f, ThemeColors.hueOf(0xFF0000FF), 0.5);
        float h = ThemeColors.hueOf(0xFF7DD3C0);
        assertTrue(h > 160 && h < 180);
    }

    @Test public void hslBasics() {
        assertEquals(0xFFFF0000, ThemeColors.hsl(0, 100, 50));
        assertEquals(0xFFFFFFFF, ThemeColors.hsl(0, 0, 100));
        assertEquals(0xFF000000, ThemeColors.hsl(0, 0, 0));
    }
}
