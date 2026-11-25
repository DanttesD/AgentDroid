package com.termux.app.agent

import android.R
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.view.View
import com.agentdroid.agent.ThemeColors
import com.termux.app.workspace.WorkspaceLanguage

/**
 * Obsidian theme settings and the resulting palette. Material You takes the accent hue from the wallpaper (Android 12+),
 * otherwise one of four fixed accents is used; AMOLED turns the dark background pure black.
 **/
object AppTheme {
    const val MODE_SYSTEM: Int = 0
    const val MODE_DARK: Int = 1
    const val MODE_LIGHT: Int = 2
    @JvmField val SEEDS: IntArray = intArrayOf(0xFF7DD3C0.toInt(), 0xFFB6A4FF.toInt(), 0xFFFFB86B.toInt(), 0xFF8AB4FF.toInt())

    @JvmStatic
    private fun prefs(c: Context): SharedPreferences {
        return c.getApplicationContext().getSharedPreferences("agentdroid_theme", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun mode(c: Context): Int {
        return prefs(c).getInt("mode", MODE_SYSTEM)
    }

    @JvmStatic
    fun materialYou(c: Context): Boolean {
        return prefs(c).getBoolean("you", true)
    }

    @JvmStatic
    fun amoled(c: Context): Boolean {
        return prefs(c).getBoolean("amoled", true)
    }

    @JvmStatic
    fun seed(c: Context): Int {
        return Math.max(0, Math.min(SEEDS.size - 1, prefs(c).getInt("seed", 0)))
    }

    @JvmStatic
    fun setMode(c: Context, v: Int) {
        prefs(c).edit().putInt("mode", v).apply()
    }

    @JvmStatic
    fun setMaterialYou(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("you", v).apply()
    }

    @JvmStatic
    fun setAmoled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("amoled", v).apply()
    }

    @JvmStatic
    fun setSeed(c: Context, v: Int) {
        prefs(c).edit().putInt("seed", v).apply()
    }

    @JvmStatic
    fun isDark(c: Context): Boolean {
        val m: Int = mode(c)
        if (m == MODE_DARK) {
            return true
        }
        if (m == MODE_LIGHT) {
            return false
        }
        return c.getResources().getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** True when the wallpaper colour is really used (Material You on and Android 12+).*/
    @JvmStatic
    fun wallpaperColors(c: Context): Boolean {
        return materialYou(c) && Build.VERSION.SDK_INT >= 31
    }

    @JvmStatic
    fun hue(c: Context): Float {
        if (wallpaperColors(c)) {
            try {
                return ThemeColors.hueOf(c.getColor(R.color.system_accent1_500))
            } catch (ignored: RuntimeException) {
            }
        }
        return ThemeColors.hueOf(SEEDS[seed(c)])
    }

    @JvmStatic
    fun colors(c: Context): ThemeColors {
        return ThemeColors.of(hue(c), isDark(c), amoled(c))
    }

    /** Changes whenever the visible palette changes (settings, system dark mode, wallpaper hue).*/
    @JvmStatic
    fun signature(c: Context): String {
        return mode(c).toString() + "/" + isDark(c) + "/" + amoled(c) + "/" + Math.round(hue(c)) + "/" + textScale(c) + "/" + WorkspaceLanguage.code(c)
    }

    /** Text scale chosen in Settings, on top of the system font size.*/
    @JvmField val TEXT_SCALES: FloatArray = floatArrayOf(0.85f, 1f, 1.15f, 1.3f)

    @JvmStatic
    fun textScale(c: Context): Float {
        val v: Float = prefs(c).getFloat("text_scale", 1f)
        for (s in TEXT_SCALES) {
            if (Math.abs(s - v) < 0.01f) {
                return s
            }
        }
        return 1f
    }

    @JvmStatic
    fun setTextScale(c: Context, v: Float) {
        prefs(c).edit().putFloat("text_scale", v).apply()
    }

    /** Makes sp sizes follow the Settings text size (system font size times the chosen scale).*/
    @JvmStatic
    fun applyTextScale(a: Activity) {
        val base: Float = Settings.System.getFloat(a.getContentResolver(), Settings.System.FONT_SCALE, 1f)
        val c = Configuration(a.getResources().getConfiguration())
        c.fontScale = base * textScale(a)
        a.getResources().updateConfiguration(c, a.getResources().getDisplayMetrics())
    }

    /** Language, text size, window background and system bars; call at the start of onCreate.*/
    @JvmStatic
    fun apply(a: Activity): ThemeColors {
        WorkspaceLanguage.apply(a)
        applyTextScale(a)
        val t: ThemeColors = colors(a)
        a.getWindow().getDecorView().setBackgroundColor(t.bg)
        a.getWindow().setStatusBarColor(t.bg)
        a.getWindow().setNavigationBarColor(t.bg)
        if (!t.dark) {
            var f: Int = a.getWindow().getDecorView().getSystemUiVisibility()
            f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            a.getWindow().getDecorView().setSystemUiVisibility(f)
        }
        return t
    }
}
