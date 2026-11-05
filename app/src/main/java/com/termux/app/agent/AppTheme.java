package com.termux.app.agent;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;

import com.agentdroid.agent.ThemeColors;

/**
 * Obsidian theme settings and the resulting palette. Material You takes the accent hue from the wallpaper (Android 12+),
 * otherwise one of four fixed accents is used; AMOLED turns the dark background pure black.
 */
public final class AppTheme {
    public static final int MODE_SYSTEM = 0, MODE_DARK = 1, MODE_LIGHT = 2;
    public static final int[] SEEDS = {0xFF7DD3C0, 0xFFB6A4FF, 0xFFFFB86B, 0xFF8AB4FF};

    private AppTheme() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("agentdroid_theme", Context.MODE_PRIVATE);
    }

    public static int mode(Context c) { return prefs(c).getInt("mode", MODE_SYSTEM); }
    public static boolean materialYou(Context c) { return prefs(c).getBoolean("you", true); }
    public static boolean amoled(Context c) { return prefs(c).getBoolean("amoled", true); }
    public static int seed(Context c) { return Math.max(0, Math.min(SEEDS.length - 1, prefs(c).getInt("seed", 0))); }

    public static void setMode(Context c, int v) { prefs(c).edit().putInt("mode", v).apply(); }
    public static void setMaterialYou(Context c, boolean v) { prefs(c).edit().putBoolean("you", v).apply(); }
    public static void setAmoled(Context c, boolean v) { prefs(c).edit().putBoolean("amoled", v).apply(); }
    public static void setSeed(Context c, int v) { prefs(c).edit().putInt("seed", v).apply(); }

    public static boolean isDark(Context c) {
        int m = mode(c);
        if (m == MODE_DARK) return true;
        if (m == MODE_LIGHT) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** True when the wallpaper colour is really used (Material You on and Android 12+). */
    public static boolean wallpaperColors(Context c) { return materialYou(c) && Build.VERSION.SDK_INT >= 31; }

    public static float hue(Context c) {
        if (wallpaperColors(c)) {
            try { return ThemeColors.hueOf(c.getColor(android.R.color.system_accent1_500)); } catch (RuntimeException ignored) { }
        }
        return ThemeColors.hueOf(SEEDS[seed(c)]);
    }

    public static ThemeColors colors(Context c) { return ThemeColors.of(hue(c), isDark(c), amoled(c)); }

    /** Window background and system bars in the palette; call at the start of onCreate. */
    public static ThemeColors apply(Activity a) {
        ThemeColors t = colors(a);
        a.getWindow().getDecorView().setBackgroundColor(t.bg);
        a.getWindow().setStatusBarColor(t.bg);
        a.getWindow().setNavigationBarColor(t.bg);
        if (!t.dark) {
            int f = a.getWindow().getDecorView().getSystemUiVisibility();
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            a.getWindow().getDecorView().setSystemUiVisibility(f);
        }
        return t;
    }
}
