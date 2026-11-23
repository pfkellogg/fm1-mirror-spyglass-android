package com.pfkellogg.fm1mirror;

/**
 * Big Screen version 2: David Konsumer's protocol (github.com/konsumer/foma/tree/main/bigscreen, MIT), the successor
 * of our version 1 (Codec). The FM-1 sends only the 16 x 16 tiles that changed, compressed, and says when a frame is
 * whole, so we show whole frames. Our own decoder of its README, the same as src/bigscreen2.rs of the desktop app:
 *
 * - hello F0 7D 'B' 'S' 00 02 flags F7 (flags bit 0: all of the screen again); a device that speaks it answers each
 *   with INFO F0 7D 'B' 'S' 01 version w(14) h(14) format F7; version 1 firmware doesn't, and after 3 we use version 1;
 * - RECT F0 7D 'B' 'S' 02 x(14) y(14) w(14) h(14) pixels… F7, row by row; SYNC F0 7D 'B' 'S' 03 F7: the frame is
 *   whole (show it). No SYNC for about 60 ms: show what came anyway.
 *
 * Pixels, per RECT (cache: 64 zeros, last colour 0): 00iiiiii cache[i]; 010nnnnn last colour n + 1 times;
 * 0110nnnn b last colour (n << 7 | b) + 33 times; 011100hh b1 b2 colour hh << 14 | b1 << 7 | b2, into
 * cache[hash]; anything else: stop. hash(c) = (r * 3 + g * 5 + b * 7) & 63.
 * Nothing Android in it, so tools can test it on a computer.
 */
final class BigScreen2 {
    private BigScreen2() {}

    static final int VERSION = 2, NOTHING = 0, INFO = 1, PAINTED = 2, FRAME = 3;
    static final long SHOW_AFTER = 60, PROBE_EVERY = 500;
    static final int PROBE_TRIES = 3, REPROBE = 10;
    private static final int SIZE = Codec.SIZE;

    static byte[] hello(boolean all) {
        return new byte[] {(byte) 0xf0, 0x7d, 'B', 'S', 0, VERSION, (byte) (all ? 1 : 0), (byte) 0xf7};
    }

    static int hash(int c) {
        return ((c >> 11) * 3 + ((c >> 5) & 63) * 5 + (c & 31) * 7) & 63;
    }

    /** The frame being put together: rectangles go here, and are shown (copied to the screen) at a SYNC. */
    static final class Viewer {
        final int[] back = new int[SIZE * SIZE];
        int w = SIZE, h = SIZE;
        /** When the first rectangle since the frame last shown came (0: nothing waiting). */
        long waitingSince;

        int apply(byte[] m, long now) {
            int n = m.length;
            if (n < 6 || m[0] != (byte) 0xf0 || m[1] != 0x7d || m[2] != 'B' || m[3] != 'S' || m[n - 1] != (byte) 0xf7)
                return NOTHING;
            for (int i = 4; i < n - 1; i++) if (m[i] < 0) return NOTHING;
            int len = n - 5; // the body: m[4 .. n-2]
            switch (m[4]) {
                case 1:
                    if (len < 7) return NOTHING;
                    w = n14(m, 6);
                    h = n14(m, 8);
                    return INFO;
                case 3:
                    return FRAME;
                case 2:
                    if (len < 9) return NOTHING;
                    rect(n14(m, 5), n14(m, 7), n14(m, 9), n14(m, 11), m, 13, n - 1);
                    if (waitingSince == 0) waitingSince = Math.max(1, now);
                    return PAINTED;
                default:
                    return NOTHING; // (another viewer's hello, or something newer)
            }
        }

        private static int n14(byte[] m, int i) {
            return (m[i] & 127) | (m[i + 1] & 127) << 7;
        }

        private void rect(int x0, int y0, int w, int h, byte[] m, int i, int end) {
            int total = w * h;
            if (w <= 0 || total <= 0) return;
            int[] cache = new int[64];
            int c = 0, p = 0;
            while (i < end && p < total) {
                int b = m[i++] & 255, run = 1;
                if (b < 0x40) c = cache[b];
                else if (b < 0x60) run = (b & 31) + 1;
                else if (b < 0x70) {
                    run = ((b & 15) << 7 | (i < end ? m[i] & 127 : 0)) + 33;
                    i++;
                } else if (b < 0x74) {
                    c = (b & 3) << 14 | (i < end ? m[i] & 127 : 0) << 7 | (i + 1 < end ? m[i + 1] & 127 : 0);
                    i += 2;
                    cache[hash(c)] = c;
                } else break; // (not version 2: something newer)
                for (int k = Math.min(run, total - p); k > 0; k--, p++) {
                    int x = x0 + p % w, y = y0 + p / w;
                    if (x < SIZE && y < SIZE) back[y * SIZE + x] = c & 0xffff;
                }
            }
        }

        /** Time to show what came, though no SYNC has (the screen changes faster than it can be sent)? */
        boolean overdue(long now) {
            return waitingSince != 0 && now - waitingSince >= SHOW_AFTER;
        }

        void show(int[] screen) {
            System.arraycopy(back, 0, screen, 0, back.length);
            waitingSince = 0;
        }
    }

    /** Which version we're speaking: the link thread's side. `answered` counts INFOs (counted by the input). */
    static final class Asker {
        int ver;
        private int tries, count, seen;
        private boolean all = true;

        long every(long usual) {
            return ver == 0 ? PROBE_EVERY : usual;
        }

        /** The whole screen again with the next hello (the app came back to the front). */
        void wantAll() {
            all = true;
        }

        /** The next hello: version 2 first; after 3 with no answer version 1 (version 2 again every 10th); back to
         * asking if a version 2 device goes quiet. */
        byte[] next(int answered) {
            if (answered != seen) {
                seen = answered;
                tries = 0;
                if (ver != 2) {
                    ver = 2;
                    all = true;
                }
            }
            if (tries >= PROBE_TRIES) {
                if (ver == 0) {
                    ver = 1;
                    all = true;
                    count = 0;
                } else if (ver == 2) {
                    ver = 0;
                    tries = 0;
                    all = true;
                }
            }
            if (ver == 1) {
                count++;
                if (count % REPROBE != 0) {
                    boolean a = all;
                    all = false;
                    return a ? Codec.HELLO_ALL : Codec.HELLO;
                }
                return hello(false);
            }
            tries++;
            boolean a = all || ver == 0;
            all = false;
            return hello(a);
        }
    }
}
