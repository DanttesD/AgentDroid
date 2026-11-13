package android.util;

/** Sandbox stand-in for android.util.Base64.decode (same lenient state machine as the platform decoder). */
public class Base64 {
    public static final int DEFAULT = 0;

    private static final int SKIP = -1;
    private static final int EQUALS = -2;
    private static final int[] DECODE = new int[256];
    static {
        for (int i = 0; i < 256; i++) DECODE[i] = SKIP;
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < a.length(); i++) DECODE[a.charAt(i)] = i;
        DECODE['='] = EQUALS;
    }


    /** Flags 0 (DEFAULT): padding, a line break after every 76 characters and after the last line. */
    public static String encodeToString(byte[] input, int flags) {
        String enc = java.util.Base64.getEncoder().encodeToString(input);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < enc.length(); i += 76) {
            sb.append(enc, i, Math.min(enc.length(), i + 76)).append('\n');
        }
        return sb.toString();
    }

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input.length, flags);
    }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        byte[] output = new byte[len * 3 / 4];
        int state = 0, value = 0, op = 0, p = offset;
        len += offset;
        while (p < len) {
            int d = DECODE[input[p++] & 0xff];
            switch (state) {
                case 0:
                    if (d >= 0) { value = d; ++state; } else if (d != SKIP) throw new IllegalArgumentException("bad base-64");
                    break;
                case 1:
                    if (d >= 0) { value = (value << 6) | d; ++state; } else if (d != SKIP) throw new IllegalArgumentException("bad base-64");
                    break;
                case 2:
                    if (d >= 0) { value = (value << 6) | d; ++state; }
                    else if (d == EQUALS) { output[op++] = (byte) (value >> 4); state = 4; }
                    else if (d != SKIP) throw new IllegalArgumentException("bad base-64");
                    break;
                case 3:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        output[op + 2] = (byte) value; output[op + 1] = (byte) (value >> 8); output[op] = (byte) (value >> 16);
                        op += 3; state = 0;
                    } else if (d == EQUALS) {
                        output[op + 1] = (byte) (value >> 2); output[op] = (byte) (value >> 10);
                        op += 2; state = 5;
                    } else if (d != SKIP) throw new IllegalArgumentException("bad base-64");
                    break;
                case 4:
                    if (d == EQUALS) ++state; else if (d != SKIP) throw new IllegalArgumentException("bad base-64");
                    break;
                case 5:
                    if (d != SKIP) throw new IllegalArgumentException("bad base-64");
                    break;
            }
        }
        switch (state) {
            case 0: break;
            case 1: throw new IllegalArgumentException("bad base-64");
            case 2: output[op++] = (byte) (value >> 4); break;
            case 3: output[op++] = (byte) (value >> 10); output[op++] = (byte) (value >> 2); break;
            case 4: throw new IllegalArgumentException("bad base-64");
            case 5: break;
        }
        byte[] result = new byte[op];
        System.arraycopy(output, 0, result, 0, op);
        return result;
    }
}
