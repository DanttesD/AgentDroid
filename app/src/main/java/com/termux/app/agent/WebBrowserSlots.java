package com.termux.app.agent;

/** Browser profiles 1..7: same screen, separate process and separate cookies (see AndroidManifest). */
public final class WebBrowserSlots {
    private WebBrowserSlots() {}

    public static class Slot1 extends WebBrowserActivity { @Override protected int slot() { return 1; } }
    public static class Slot2 extends WebBrowserActivity { @Override protected int slot() { return 2; } }
    public static class Slot3 extends WebBrowserActivity { @Override protected int slot() { return 3; } }
    public static class Slot4 extends WebBrowserActivity { @Override protected int slot() { return 4; } }
    public static class Slot5 extends WebBrowserActivity { @Override protected int slot() { return 5; } }
    public static class Slot6 extends WebBrowserActivity { @Override protected int slot() { return 6; } }
    public static class Slot7 extends WebBrowserActivity { @Override protected int slot() { return 7; } }
}
