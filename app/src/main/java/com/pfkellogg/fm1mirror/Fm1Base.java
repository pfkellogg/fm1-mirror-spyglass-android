package com.pfkellogg.fm1mirror;

/**
 * The other FM-1 screen mirror: Charles Vestal's, in firmware built on fm1-base with {@code mirror = true} in its
 * fm1.toml (LOOM-8, Polly, Ocarina, and others by other makers). It speaks in the updater's framing (F0, [0, 89,
 * command, length (3 bytes), body..., checksum] packed 7 bits at a time, F7), as documented in device/mirror.c of
 * github.com/charlesvestal/fm1-base. This is our own decoder of what that file describes (the same as the Mac app's
 * src/fm1base.rs):
 * <ul>
 * <li>we send command 0x50 with one byte: 2 = on, and the whole screen again; 1 = on (and keep on: at least every
 *     6 seconds, or it stops); 0 = off;</li>
 * <li>it answers 0x50 (hello), then sends 0x51 rectangles (x, y, w, h, seq, then the pixels in a small code) and
 *     0x52 fills (x, y, w, h, seq, colour high, low).</li>
 * </ul>
 * The pixel code, per rectangle (the colour now starts at 0, and a cache of 64 colours all 0): 0nnnnnnn = n + 1 more
 * pixels of the colour now; 10iiiiii = the colour now = cache[i], one pixel; 11nnnnnn hi lo = the colour now =
 * hi << 8 | lo, kept in cache[hash], n + 1 pixels; hash(c) = (c * 40503) >> 10 & 63.
 */
final class Fm1Base {
    private Fm1Base() {}

    static final int NOTHING = 0, HELLO = 1, PAINTED = 2;
    private static final int REQUEST = 0x50, RECT = 0x51, FILL = 0x52, SIZE = Codec.SIZE;

    /** Asks for the screen: 2 on with the whole screen again, 1 keep on, 0 off. */
    static byte[] request(int what) {
        return Codec.frame(REQUEST, new byte[] {(byte) what});
    }

    /** One message from the FM-1, into pixels (RGB565, row by row): NOTHING, HELLO or PAINTED. */
    static int apply(int[] pixels, byte[] m) {
        byte[][] f = parse(m);
        if (f == null) return NOTHING;
        int command = f[0][0] & 255;
        byte[] b = f[1];
        if (command == REQUEST) return HELLO;
        if (command == RECT) return rect(pixels, b) ? PAINTED : NOTHING;
        if (command == FILL) return fill(pixels, b) ? PAINTED : NOTHING;
        return NOTHING;
    }

    /** An updater-framed message of any length: {{command}, body}, or null. */
    private static byte[][] parse(byte[] m) {
        int n = m.length;
        if (n < 10 || m[0] != (byte) 0xf0 || m[n - 1] != (byte) 0xf7) return null;
        byte[] raw = new byte[n];
        int k = 0, bits = 0, value = 0;
        for (int i = 1; i < n - 1; i++) {
            int b = m[i] & 255;
            if (b > 127) return null;
            value |= b << bits;
            bits += 7;
            while (bits >= 8) {
                raw[k++] = (byte) value;
                value >>>= 8;
                bits -= 8;
            }
        }
        if (k < 7 || raw[0] != 0 || raw[1] != 89) return null;
        int len = (raw[3] & 255) | (raw[4] & 255) << 8 | (raw[5] & 255) << 16;
        if (len + 7 > k) return null;
        int sum = 0;
        for (int i = 6; i < 7 + len; i++) sum += raw[i] & 255;
        if ((sum & 255) != 255) return null;
        byte[] body = new byte[len];
        System.arraycopy(raw, 6, body, 0, len);
        return new byte[][] {{raw[2]}, body};
    }

    private static int side(byte b) {
        return b == 0 ? 256 : b & 255;
    }

    private static int hash(int c) {
        return (int) (((c & 0xffffL) * 40503L >>> 10) & 63);
    }

    private static boolean rect(int[] pixels, byte[] b) {
        if (b.length < 5) return false;
        int x = b[0] & 255, y = b[1] & 255, w = side(b[2]), h = side(b[3]);
        if (x + w > SIZE || y + h > SIZE) return false;
        int total = w * h, p = 0, i = 5, now = 0;
        int[] cache = new int[64];
        while (i < b.length) {
            int op = b[i++] & 255, count;
            if (op < 0x80) {
                count = op + 1;
            } else if (op < 0xc0) {
                now = cache[op & 63];
                count = 1;
            } else {
                if (i + 2 > b.length) return false;
                now = (b[i] & 255) << 8 | (b[i + 1] & 255);
                i += 2;
                cache[hash(now)] = now;
                count = (op & 63) + 1;
            }
            if (p + count > total) return false;
            for (int j = 0; j < count; j++, p++) pixels[(y + p / w) * SIZE + x + p % w] = now;
        }
        return p == total;
    }

    private static boolean fill(int[] pixels, byte[] b) {
        if (b.length < 7) return false;
        int x = b[0] & 255, y = b[1] & 255, w = side(b[2]), h = side(b[3]);
        int colour = (b[5] & 255) << 8 | (b[6] & 255);
        for (int row = y; row < Math.min(SIZE, y + h); row++)
            for (int col = x; col < Math.min(SIZE, x + w); col++) pixels[row * SIZE + col] = colour;
        return true;
    }
}
