package com.agentdroid.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class CssThemeTest {
    @Test public void darkModeInvertsLightness() {
        assertEquals("#000000", CssTheme.map("fff", 150, true));
        assertTrue(CssTheme.map("242a26", 150, true).compareTo("#cccccc") > 0 || CssTheme.map("242a26", 150, true).charAt(1) >= '8');
    }

    @Test public void lightModeKeepsNeutralsAndKeepsAlpha() {
        assertEquals("#ffffff", CssTheme.map("fff", 150, false));
        assertEquals("#ffffff99", CssTheme.map("fff9", 150, false));
    }

    @Test public void greensTakeTheThemeHueButRedsStay() {
        String c = CssTheme.map("c2574a", 150, false);
        assertEquals("#c2574a", c);
        String g = CssTheme.map("1c6b52", 250, false);
        int b = Integer.parseInt(g.substring(5, 7), 16), gr = Integer.parseInt(g.substring(3, 5), 16);
        assertTrue("blue should dominate for hue 250: " + g, b > gr);
    }

    @Test public void remapsWholeStylesheet() {
        String out = CssTheme.remap("a{color:#fff;background:#edeae9}b{x:url(#frag)}", 150, true);
        assertTrue(out.contains("color:#000000"));
        assertTrue(out.contains("url(#frag)"));
    }
}
