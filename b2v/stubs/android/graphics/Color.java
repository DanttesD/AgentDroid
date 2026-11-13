package android.graphics;

/** Sandbox stand-in: the channel accessors of the real class (the unit-test android.jar returns 0 instead). */
public class Color {
    public static int red(int color) { return (color >> 16) & 0xFF; }
    public static int green(int color) { return (color >> 8) & 0xFF; }
    public static int blue(int color) { return color & 0xFF; }
}
