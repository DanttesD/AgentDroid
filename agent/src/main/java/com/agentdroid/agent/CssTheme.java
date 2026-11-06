package com.agentdroid.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recolours a light stylesheet for the app theme: in dark mode every hex colour has its lightness inverted, and in
 * both modes the greenish tints take the theme hue. Reds, ambers and neutrals keep their meaning.
 */
public final class CssTheme {
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})(?![0-9a-zA-Z])");

    private CssTheme() { }

    public static String remap(String css, float hue, boolean dark) {
        Matcher m = HEX.matcher(css);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(map(m.group(1), hue, dark)));
        m.appendTail(out);
        return out.toString();
    }

    static String map(String hex, float hue, boolean dark) {
        String h = hex;
        if (h.length() <= 4) {
            StringBuilder b = new StringBuilder();
            for (char c : h.toCharArray()) b.append(c).append(c);
            h = b.toString();
        }
        int r = Integer.parseInt(h.substring(0, 2), 16), g = Integer.parseInt(h.substring(2, 4), 16), bl = Integer.parseInt(h.substring(4, 6), 16);
        String alpha = h.length() == 8 ? h.substring(6, 8) : "";
        float[] hsl = toHsl(r / 255f, g / 255f, bl / 255f);
        float hh = hsl[0], s = hsl[1], l = hsl[2];
        boolean green = s > 0.04f && hh >= 70 && hh <= 190;
        if (green) hh = hue;
        if (dark) l = (float) (0.95 * Math.pow(1 - l, 0.85));
        int[] rgb = fromHsl(hh, s, l);
        return String.format("#%02x%02x%02x%s", rgb[0], rgb[1], rgb[2], alpha);
    }

    static float[] toHsl(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f, h = 0, s = 0, d = max - min;
        if (d > 1e-6f) {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) h = ((g - b) / d + (g < b ? 6 : 0));
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
        }
        return new float[]{h, s, l};
    }

    static int[] fromHsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s, hp = (((h % 360) + 360) % 360) / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1)), r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
        float m = l - c / 2f;
        return new int[]{Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)};
    }
}
