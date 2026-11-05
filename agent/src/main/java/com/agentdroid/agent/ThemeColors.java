package com.agentdroid.agent;

/**
 * The Obsidian palette: one accent hue in, a full set of surface and text colours out (ARGB ints).
 * Dark with a pure-black AMOLED background, dark tonal, or light. Pure maths so the contrast rules are unit-tested.
 */
public final class ThemeColors {
    public final boolean dark;
    public final int bg, s1, s2, s3, line, on, on2, p, pc, onpc, onp, err, err2, ok;

    private ThemeColors(boolean dark, int bg, int s1, int s2, int s3, int line, int on, int on2,
                        int p, int pc, int onpc, int onp, int err, int err2, int ok) {
        this.dark = dark; this.bg = bg; this.s1 = s1; this.s2 = s2; this.s3 = s3; this.line = line; this.on = on; this.on2 = on2;
        this.p = p; this.pc = pc; this.onpc = onpc; this.onp = onp; this.err = err; this.err2 = err2; this.ok = ok;
    }

    public static ThemeColors of(float hue, boolean dark, boolean amoled) {
        if (dark) {
            return new ThemeColors(true,
                    amoled ? 0xFF000000 : hsl(hue, 14, 8), hsl(hue, 12, amoled ? 7 : 11), hsl(hue, 12, amoled ? 11 : 15),
                    hsl(hue, 12, amoled ? 16 : 20), hsl(hue, 10, amoled ? 20 : 26), hsl(hue, 10, 92), hsl(hue, 8, 68),
                    hsl(hue, 72, 74), hsl(hue, 40, 22), hsl(hue, 70, 90), hsl(hue, 60, 12),
                    0xFFF2B8B5, 0xFF4A1C1C, hsl(150, 50, 65));
        }
        return new ThemeColors(false,
                hsl(hue, 25, 98), hsl(hue, 22, 95), hsl(hue, 20, 92), hsl(hue, 18, 86), hsl(hue, 14, 80),
                hsl(hue, 15, 10), hsl(hue, 10, 35), hsl(hue, 60, 30), hsl(hue, 55, 88), hsl(hue, 70, 12), 0xFFFFFFFF,
                0xFFB3261E, 0xFFF9DEDC, hsl(150, 60, 26));
    }

    /** Hue (0-359) of an ARGB colour; 0 for greys. */
    public static float hueOf(int argb) {
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d == 0) return 0;
        float h;
        if (mx == r) h = ((g - b) / d) % 6; else if (mx == g) h = (b - r) / d + 2; else h = (r - g) / d + 4;
        h *= 60;
        if (h < 0) h += 360;
        return h;
    }

    public static int hsl(float h, float s, float l) {
        s /= 100f; l /= 100f;
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = (((h % 360) + 360) % 360) / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
        float m = l - c / 2;
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double lum(int argb) {
        return 0.2126 * lin((argb >> 16) & 255) + 0.7152 * lin((argb >> 8) & 255) + 0.0722 * lin(argb & 255);
    }

    /** WCAG contrast ratio, 1..21. */
    public static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
