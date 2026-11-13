package com.agentdroid.agent

/**
 * The Obsidian palette: one accent hue in, a full set of surface and text colours out (ARGB ints).
 * Dark with a pure-black AMOLED background, dark tonal, or light. Pure maths so the contrast rules are unit-tested.
 */
class ThemeColors private constructor(
    @JvmField val dark: Boolean,
    @JvmField val bg: Int,
    @JvmField val s1: Int,
    @JvmField val s2: Int,
    @JvmField val s3: Int,
    @JvmField val line: Int,
    @JvmField val on: Int,
    @JvmField val on2: Int,
    @JvmField val p: Int,
    @JvmField val pc: Int,
    @JvmField val onpc: Int,
    @JvmField val onp: Int,
    @JvmField val err: Int,
    @JvmField val err2: Int,
    @JvmField val ok: Int,
) {
    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        @JvmStatic
        fun of(hue: Float, dark: Boolean, amoled: Boolean): ThemeColors {
            if (dark) {
                return ThemeColors(
                    true,
                    if (amoled) BLACK else hsl(hue, 14f, 8f),
                    hsl(hue, 12f, if (amoled) 7f else 11f),
                    hsl(hue, 12f, if (amoled) 11f else 15f),
                    hsl(hue, 12f, if (amoled) 16f else 20f),
                    hsl(hue, 10f, if (amoled) 20f else 26f),
                    hsl(hue, 10f, 92f),
                    hsl(hue, 8f, 68f),
                    hsl(hue, 72f, 74f),
                    hsl(hue, 40f, 22f),
                    hsl(hue, 70f, 90f),
                    hsl(hue, 60f, 12f),
                    0xFFF2B8B5.toInt(),
                    0xFF4A1C1C.toInt(),
                    hsl(150f, 50f, 65f),
                )
            }
            return ThemeColors(
                false,
                hsl(hue, 25f, 98f),
                hsl(hue, 22f, 95f),
                hsl(hue, 20f, 92f),
                hsl(hue, 18f, 86f),
                hsl(hue, 14f, 80f),
                hsl(hue, 15f, 10f),
                hsl(hue, 10f, 35f),
                hsl(hue, 60f, 30f),
                hsl(hue, 55f, 88f),
                hsl(hue, 70f, 12f),
                WHITE,
                0xFFB3261E.toInt(),
                0xFFF9DEDC.toInt(),
                hsl(150f, 60f, 26f),
            )
        }

        /** Hue (0-359) of an ARGB colour; 0 for greys. */
        @JvmStatic
        fun hueOf(argb: Int): Float {
            val r = ((argb shr 16) and 255) / 255f
            val g = ((argb shr 8) and 255) / 255f
            val b = (argb and 255) / 255f
            val mx = Math.max(r, Math.max(g, b))
            val mn = Math.min(r, Math.min(g, b))
            val d = mx - mn
            if (d == 0f) return 0f
            var h: Float = if (mx == r) {
                ((g - b) / d) % 6
            } else if (mx == g) {
                (b - r) / d + 2
            } else {
                (r - g) / d + 4
            }
            h *= 60
            if (h < 0) h += 360
            return h
        }

        @JvmStatic
        fun hsl(h: Float, s: Float, l: Float): Int {
            val sat = s / 100f
            val lig = l / 100f
            val c = (1 - Math.abs(2 * lig - 1)) * sat
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
            val m = lig - c / 2
            return BLACK or (Math.round((r + m) * 255) shl 16) or (Math.round((g + m) * 255) shl 8) or Math.round((b + m) * 255)
        }

        private fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }

        private fun lum(argb: Int): Double =
            0.2126 * lin((argb shr 16) and 255) + 0.7152 * lin((argb shr 8) and 255) + 0.0722 * lin(argb and 255)

        /** WCAG contrast ratio, 1..21. */
        @JvmStatic
        fun contrast(a: Int, b: Int): Double {
            val la = lum(a)
            val lb = lum(b)
            return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
        }
    }
}
