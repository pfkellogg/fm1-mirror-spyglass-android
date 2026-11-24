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

    static final int NOTHING = 0, HELLO = 1, PAINTED = 2, LIGHTS = 3, ANSWERED = 4;
    private static final int REQUEST = 0x50, RECT = 0x51, FILL = 0x52, INPUT = 0x53, REMOTE = 0x54, SIZE = Codec.SIZE;
    /** The lights of the last input report (LIGHTS): Big Knobs and Key Lights speak its panel messages (Panel). */
    static volatile Panel.Lights lights;

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
        if (command == INPUT) {
            Panel.Lights l = lights(b);
            if (l == null) return NOTHING;
            lights = l;
            return LIGHTS;
        }
        if (command == REMOTE) return ANSWERED;
        if (command == RECT) return rect(pixels, b) ? PAINTED : NOTHING;
        if (command == FILL) return fill(pixels, b) ? PAINTED : NOTHING;
        return NOTHING;
    }

    /** Its B_* (FX SEL ENV LFO EDIT GLO HOME SAVE ARP SEQ PLAY REC OCT- OCT+) as our button ids (Panel.BUTTONS). */
    private static int ourButtons(int b) {
        int m = 0;
        for (int i = 0; i < 14; i++)
            if ((b >> i & 1) == 1) m |= 1 << (i < 12 ? i + 2 : i - 12);
        if ((b >> 15 & 1) == 1) m |= 1 << 12; // PLAY lit red: lit
        return m;
    }

    /** Its input report (0x53): the lit buttons u16 at 15, lit keys u32 at 17, then (version 4) the dim ones at 21, 23. */
    static Panel.Lights lights(byte[] b) {
        if (b.length < 21) return null;
        int btns = ourButtons((b[15] & 255) | (b[16] & 255) << 8), keys = le32(b, 17) & ((1 << 27) - 1);
        if (b.length < 27) return new Panel.Lights(keys, btns, 0, 0);
        return new Panel.Lights(keys, btns, le32(b, 23) & ((1 << 27) - 1) & ~keys,
                ourButtons((b[21] & 255) | (b[22] & 255) << 8) & ~btns);
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 255) | (b[i + 1] & 255) << 8 | (b[i + 2] & 255) << 16 | (b[i + 3] & 255) << 24;
    }

    /** Big Knobs through its remote (0x54: seq, op, a, b, c), one command at a time: turns gathered per knob, presses in order. */
    static final class Remote {
        private int seq;
        private long waiting;
        private final int[] turns = new int[7];
        private int master = -1;
        private final java.util.ArrayDeque<int[]> presses = new java.util.ArrayDeque<>();

        /** A Big Screen build's message (Panel: 04 turn, 06 MASTER, 07 press), in its terms. */
        synchronized void take(byte[] m) {
            if (m.length != 8) return;
            if (m[4] == 4) {
                int en = new int[] {0, 1, 3, 4, 5, 6, 2}[Math.min(6, m[5] & 7)], n = m[6] & 127;
                turns[en] = Math.max(-127, Math.min(127, turns[en] + (n >= 64 ? n - 128 : n)));
            } else if (m[4] == 6) {
                master = ((m[5] & 127) | (m[6] & 127) << 7) * 4096 / 1023;
            } else if (m[4] == 7) {
                int id = m[5] & 127;
                presses.add(id < 14 ? new int[] {1, id >= 2 ? id - 2 : 12 + id, m[6]} : new int[] {2, Math.min(26, id - 14), m[6]});
            }
        }

        synchronized void answered() {
            waiting = 0;
        }

        synchronized void clear() {
            java.util.Arrays.fill(turns, 0);
            master = -1;
            presses.clear();
            waiting = 0;
        }

        /** The next command to send, or null (none waiting, or one on its way: 300 ms for a lost answer). */
        synchronized byte[] next() {
            long now = System.currentTimeMillis();
            if (waiting != 0 && now - waiting < 300) return null;
            int[] body = null;
            if (!presses.isEmpty()) {
                int[] p = presses.poll();
                body = new int[] {p[0], p[1], p[2]};
            } else {
                for (int e = 0; e < 7 && body == null; e++)
                    if (turns[e] != 0) {
                        body = new int[] {3, e, turns[e] & 255};
                        turns[e] = 0;
                    }
                if (body == null && master >= 0) {
                    body = new int[] {4, master & 255, master >> 8};
                    master = -1;
                }
            }
            if (body == null) return null;
            seq = (seq + 1) & 255;
            waiting = now;
            return Codec.frame(REMOTE, new byte[] {(byte) seq, (byte) body[0], (byte) body[1], (byte) body[2], 0});
        }
    }

    static final Remote panel = new Remote();

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
