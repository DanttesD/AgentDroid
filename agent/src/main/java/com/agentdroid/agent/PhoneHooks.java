package com.agentdroid.agent;

/** Things only the Android app can do (they need a Context). */
public interface PhoneHooks {
    void setClipboard(String text);

    String getClipboard();

    void notifyUser(String title, String text);
}
