package com.agentdroid.agent;

/**
 * The phone's accessibility service as seen by the tools. Implemented by the app; absent (null) until
 * the user enables the service. Element numbers are valid only until the next {@link #read} call.
 */
public interface ScreenBridge {
    /** Set by the app when the accessibility service connects, cleared when it stops. */
    final class Holder {
        public static volatile ScreenBridge current;
        private Holder() {}
    }

    /** Numbered text snapshot of the screen. {@code all} also lists non-interactive elements without text. */
    String read(boolean all);

    String click(int id);

    String longClick(int id);

    String setText(int id, String text);

    /** direction: up, down, left, right (the finger's direction is the opposite of the content's). */
    String scroll(int id, String direction);

    /** back, home, recents, notifications, quick_settings, lock_screen, power_dialog, screenshot. */
    String global(String action);

    /** PNG bytes of the screen or null when unsupported (needs Android 11+). */
    byte[] screenshot();

    /** Package of the app in the foreground, or empty when unknown. */
    String foregroundPackage();
}
