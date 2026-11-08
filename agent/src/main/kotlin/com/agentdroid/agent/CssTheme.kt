package com.agentdroid.agent

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Recolours a light stylesheet for the app theme: in dark mode every hex colour has its lightness inverted, and in
 * both modes the greenish tints take the theme hue. Reds, ambers and neutrals keep their meaning.
 */
object CssTheme {
    private val HEX: Pattern =
        Pattern.compile("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})(?![0-9a-zA-Z])")

    @JvmStatic
    fun remap(css: String, hue: Float, dark: Boolean): String {
        val m = HEX.matcher(css)
        val out = StringBuffer()
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(map(m.group(1), hue, dark)))
        m.appendTail(out)
        return out.toString()
    }

    @JvmStatic
    fun map(hex: String, hue: Float, dark: Boolean): String {
        var h = hex
        if (h.length <= 4) {
            val b = StringBuilder()
            for (c in h) b.append(c).append(c)
            h = b.toString()
        }
        val r = Integer.parseInt(h.substring(0, 2), 16)
        val g = Integer.parseInt(h.substring(2, 4), 16)
        val bl = Integer.parseInt(h.substring(4, 6), 16)
        val alpha = if (h.length == 8) h.substring(6, 8) else ""
        val hsl = toHsl(r / 255f, g / 255f, bl / 255f)
        var hh = hsl[0]
        val s = hsl[1]
        var l = hsl[2]
        val green = s > 0.04f && hh >= 70 && hh <= 190
        if (green) hh = hue
        if (dark) l = (0.95 * Math.pow((1 - l).toDouble(), 0.85)).toFloat()
        val rgb = fromHsl(hh, s, l)
        return String.format("#%02x%02x%02x%s", rgb[0], rgb[1], rgb[2], alpha)
    }

    private fun toHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = Math.max(r, Math.max(g, b))
        val min = Math.min(r, Math.min(g, b))
        val l = (max + min) / 2f
        var h = 0f
        var s = 0f
        val d = max - min
        if (d > 1e-6f) {
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = if (max == r) {
                ((g - b) / d + (if (g < b) 6 else 0))
            } else if (max == g) {
                (b - r) / d + 2
            } else {
                (r - g) / d + 4
            }
            h *= 60
        }
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): IntArray {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val hp = (((h % 360) + 360) % 360) / 60f
        val x = c * (1 - Math.abs(hp % 2 - 1))
        var r = 0f
        var g = 0f
        var b = 0f
        if (hp < 1) {
            r = c; g = x
        } else if (hp < 2) {
            r = x; g = c
        } else if (hp < 3) {
            g = c; b = x
        } else if (hp < 4) {
            g = x; b = c
        } else if (hp < 5) {
            r = x; b = c
        } else {
            r = c; b = x
        }
        val m = l - c / 2f
        return intArrayOf(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255))
    }
}
