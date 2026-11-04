package com.agentdroid.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class StuckDetectorTest {
    @Test public void sameCallThreeTimes() {
        StuckDetector d = new StuckDetector();
        assertNull(d.record("click", "{\"id\":3}", "ok", false));
        assertNull(d.record("click", "{\"id\":3}", "ok", false));
        assertNotNull(d.record("click", "{\"id\":3}", "ok", false));
    }

    @Test public void differentCallsAreFine() {
        StuckDetector d = new StuckDetector();
        for (int i = 0; i < 10; i++) assertNull(d.record("click", "{\"id\":" + i + "}", "r" + i, false));
    }

    @Test public void identicalResultsFourTimes() {
        StuckDetector d = new StuckDetector();
        assertNull(d.record("click", "{\"id\":1}", "same", false));
        assertNull(d.record("tap", "{\"x\":1}", "same", false));
        assertNull(d.record("click", "{\"id\":2}", "same", false));
        assertNotNull(d.record("key", "{}", "same", false));
    }

    @Test public void onlyLookingFiveTimes() {
        StuckDetector d = new StuckDetector();
        String h = null;
        for (int i = 0; i < 5; i++) h = d.record(i % 2 == 0 ? "ui_read" : "scroll", "{\"n\":" + i + "}", "r" + i, false);
        assertNotNull(h);
        assertTrue(h.contains("looking"));
    }

    @Test public void actionResetsObserving() {
        StuckDetector d = new StuckDetector();
        for (int i = 0; i < 4; i++) d.record("ui_read", "{\"n\":" + i + "}", "r" + i, false);
        d.record("click", "{}", "x", false);
        assertNull(d.record("ui_read", "{\"n\":9}", "r9", false));
    }

    @Test public void givesUpAfterThreeHints() {
        StuckDetector d = new StuckDetector();
        for (int h = 0; h < 3; h++) for (int i = 0; i < 3; i++) d.record("click", "{}", "ok", false);
        assertTrue(d.shouldGiveUp());
    }
}
