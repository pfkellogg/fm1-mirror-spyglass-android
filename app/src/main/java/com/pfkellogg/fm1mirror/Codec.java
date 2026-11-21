package com.pfkellogg.fm1mirror;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The FM-1's messages, with nothing Android in them (so tools/test can run them on a computer).
 * The same as src/screen.rs and src/install.rs of the desktop app.
 *
 * Screen: the app says hello (F0 7D 'F' 'S' 00 F7, or 01 to have the whole screen sent again)
 * about once a second; while it does, the FM-1 sends every row it paints as F0 7D 'F' 'S' 01,
 * the row and first column (two 7-bit bytes each), then runs of four bytes: a count, and the
 * RGB565 colour in 7, 7 and 2 bits.
 *
 * Updater: F0, [0, 89, command, length (3 bytes), payload…, checksum] packed 7 bits at a time, F7.
 */
final class Codec {
    private Codec() {}

    static final int SIZE = 240;
    static final byte[] HELLO = {(byte) 0xf0, 0x7d, 'F', 'S', 0, (byte) 0xf7};
    /** Hello, and send the whole screen again. */
    static final byte[] HELLO_ALL = {(byte) 0xf0, 0x7d, 'F', 'S', 1, (byte) 0xf7};
    static final byte[] ENTER_LOADER = {(byte) 0xf0, 0x22, 0x24, 0x35, 0x7f, (byte) 0xf7};
    /** Asks a firmware of the Felucca family what it is (its editor's INFO); others don't answer. */
    static final byte[] ASK_INFO = {(byte) 0xf0, 0x7d, 'F', 'L', 1, (byte) 0xf7};

    // ---- the screen

    /** How many times bigger the smooth picture is. */
    static final int SMOOTH_SCALE = 4;

    /** The unsharp mask: the blur's radius (sigma, in output pixels) and how much of the difference is added back. */
    private static final float SHARPEN_RADIUS = 1f, SHARPEN_AMOUNT = 2.6f;

    private static float lanczos3(float x) {
        x = Math.abs(x);
        if (x < 1e-6f) return 1f;
        if (x >= 3f) return 0f;
        double px = Math.PI * x;
        return (float) (3 * Math.sin(px) * Math.sin(px / 3) / (px * px));
    }

    /** Lanczos-3 taps for each output position along a side: 6 source indices and weights (normalised, edges clamped). */
    private static int[] tapIndex;
    private static float[] tapWeight;
    private static int tapSize;

    private static synchronized void taps(int size) {
        if (tapSize == size) return;
        int big = size * SMOOTH_SCALE;
        tapIndex = new int[big * 6];
        tapWeight = new float[big * 6];
        for (int o = 0; o < big; o++) {
            float c = (o + 0.5f) / SMOOTH_SCALE - 0.5f;
            int first = (int) Math.floor(c) - 2;
            float sum = 0;
            for (int j = 0; j < 6; j++) {
                int i = first + j;
                tapIndex[o * 6 + j] = Math.max(0, Math.min(size - 1, i));
                sum += tapWeight[o * 6 + j] = lanczos3(c - i);
            }
            for (int j = 0; j < 6; j++) tapWeight[o * 6 + j] /= sum;
        }
        tapSize = size;
    }

    /** The blur's kernel for the unsharp mask (sigma SHARPEN_RADIUS), and how far it reaches either side. */
    private static final int REACH = (int) Math.ceil(SHARPEN_RADIUS * 3);
    private static final float[] KERNEL = new float[2 * REACH + 1];

    static {
        float sum = 0;
        for (int i = -REACH; i <= REACH; i++) sum += KERNEL[i + REACH] = (float) Math.exp(-(i * i) / (2.0 * SHARPEN_RADIUS * SHARPEN_RADIUS));
        for (int i = 0; i < KERNEL.length; i++) KERNEL[i] /= sum;
    }

    /**
     * Smooth: the picture, SMOOTH_SCALE times bigger, smooth and crisp. A Lanczos-3 enlargement draws curves and
     * diagonals smoothly (and evens out the dot patterns some firmwares, like Erik37, shade their screens with), then a
     * firm unsharp mask (a blur of radius 1 taken away at 2.6) gives the edges back their bite. The same as the desktop
     * app's smooth.rs (from 1.3.10) and the iOS app. From 1.6.2; before, Scale2x twice and a light blur, which looked soft.
     * The original isn't changed.
     */
    static int[] smooth(int[] argb, int size) {
        int[] out = new int[size * SMOOTH_SCALE * size * SMOOTH_SCALE];
        smoothInto(argb, size, out, 0, 0, size, size);
        return out;
    }

    /**
     * Smooths only around a changed part of the picture (source pixels x0..x1, y0..y1, end exclusive) into `out`, the
     * whole smooth picture: a whole screen takes about half a second on a small tablet, a few key bars next to nothing.
     * Returns the part of `out` that was written: {left, top, right, bottom}.
     */
    static int[] smoothInto(int[] argb, int size, int[] out, int x0, int y0, int x1, int y1) {
        taps(size);
        final int S = SMOOTH_SCALE, big = size * S, grow = 3 * S + 1 + REACH;
        // O: what can change (Lanczos reaches 3 source pixels, the blur REACH more); E: what O's sharpening reads.
        int ox0 = Math.max(0, x0 * S - grow), oy0 = Math.max(0, y0 * S - grow);
        int ox1 = Math.min(big, x1 * S + grow), oy1 = Math.min(big, y1 * S + grow);
        int ex0 = Math.max(0, ox0 - REACH), ey0 = Math.max(0, oy0 - REACH);
        int ex1 = Math.min(big, ox1 + REACH), ey1 = Math.min(big, oy1 + REACH);
        int ew = ex1 - ex0, eh = ey1 - ey0;
        int[] ti = tapIndex;
        float[] tw = tapWeight;
        int sy0 = size, sy1 = 0;
        for (int y = ey0; y < ey1; y++)
            for (int j = 0; j < 6; j++) {
                sy0 = Math.min(sy0, ti[y * 6 + j]);
                sy1 = Math.max(sy1, ti[y * 6 + j] + 1);
            }
        int sh = sy1 - sy0;
        float[] across = new float[sh * ew], enlarged = new float[ew * eh], across2 = new float[ew * eh];
        for (int i = oy0; i < oy1; i++) for (int x = ox0; x < ox1; x++) out[i * big + x] = 0xff000000;
        for (int ch = 0; ch < 3; ch++) {
            int bits = 16 - 8 * ch;
            for (int sy = sy0; sy < sy1; sy++) {
                int row = sy * size, a = (sy - sy0) * ew;
                for (int x = ex0; x < ex1; x++) {
                    float v = 0;
                    int t = x * 6;
                    for (int j = 0; j < 6; j++) v += tw[t + j] * (argb[row + ti[t + j]] >> bits & 255);
                    across[a + x - ex0] = v;
                }
            }
            for (int y = ey0; y < ey1; y++) {
                int t = y * 6, e = (y - ey0) * ew;
                for (int x = 0; x < ew; x++) {
                    float v = 0;
                    for (int j = 0; j < 6; j++) v += tw[t + j] * across[(ti[t + j] - sy0) * ew + x];
                    enlarged[e + x] = v;
                }
            }
            // The blur, across then down, inside E (edges clamped), then sharpen O.
            for (int y = 0; y < eh; y++)
                for (int x = 0; x < ew; x++) {
                    float v = 0;
                    for (int j = -REACH; j <= REACH; j++) v += KERNEL[j + REACH] * enlarged[y * ew + Math.max(0, Math.min(ew - 1, x + j))];
                    across2[y * ew + x] = v;
                }
            for (int y = oy0; y < oy1; y++) {
                int ly = y - ey0;
                for (int x = ox0; x < ox1; x++) {
                    int lx = x - ex0;
                    float b = 0;
                    for (int j = -REACH; j <= REACH; j++) b += KERNEL[j + REACH] * across2[Math.max(0, Math.min(eh - 1, ly + j)) * ew + lx];
                    float e = enlarged[ly * ew + lx];
                    int v = Math.round(e + SHARPEN_AMOUNT * (e - b));
                    out[y * big + x] |= Math.max(0, Math.min(255, v)) << bits;
                }
            }
        }
        return new int[] {ox0, oy0, ox1, oy1};
    }

    /** The firmware's own name, if this is its answer to ASK_INFO: "FELUCCA SLOOP 2.8.4", then other things. */
    static String firmwareInfo(byte[] m) {
        int n = m.length;
        if (n < 7 || m[0] != (byte) 0xf0 || m[1] != 0x7d || m[2] != 'F' || m[3] != 'L' || m[4] != 1 || m[n - 1] != (byte) 0xf7)
            return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 5; i < n - 1 && m[i] != 0; i++)
            if (m[i] >= 0x20 && m[i] < 0x7f) sb.append((char) m[i]);
        String name = sb.toString().trim();
        return name.isEmpty() ? null : name;
    }

    /** The name to show. SLOOP and Skiff keep Felucca's USB name, and their answers start with "FELUCCA " for the web
     *  tools: "FELUCCA SLOOP 2.8.4" is SLOOP, and "FELUCCA v2.1.1" is Skiff when its identity is FM-1_7xx, else Felucca. */
    static String firmwareName(String info, String identity) {
        if (!info.startsWith("FELUCCA ")) return info;
        String rest = info.substring(8);
        boolean bareVersion = rest.length() > 1 && rest.charAt(0) == 'v' && Character.isDigit(rest.charAt(1));
        if (!bareVersion) return rest;
        // Skiff Atlatl (LIkst4's fork of Skiff, FM-1_742xx, which sends its screen itself) answers the same way.
        if (identity != null && identity.startsWith("FM-1_742")) return "Skiff Atlatl " + rest;
        return (identity != null && identity.startsWith("FM-1_7") ? "Skiff " : "Felucca ") + rest;
    }

    /** The sound playing, if this is the FM-1 saying it: F0 7D 'F' 'S' 02 name F7 (ASCII; "" for none). Firmware that
     *  knows it (JIANT + Big Screen) sends it after each hello and whenever it changes. */
    static String sound(byte[] m) {
        int n = m.length;
        if (n < 6 || m[0] != (byte) 0xf0 || m[1] != 0x7d || m[2] != 'F' || m[3] != 'S' || m[4] != 2 || m[n - 1] != (byte) 0xf7)
            return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 5; i < n - 1; i++)
            if (m[i] >= 0x20 && m[i] < 0x7f) sb.append((char) m[i]);
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** Paints one row message into `pixels` (RGB565, row by row); false if it isn't one. */
    static boolean apply(int[] pixels, byte[] m) {
        int n = m.length;
        if (n < 10 || m[0] != (byte) 0xf0 || m[1] != 0x7d || m[2] != 'F' || m[3] != 'S' || m[4] != 1 || m[n - 1] != (byte) 0xf7)
            return false;
        int y = (m[5] & 127) | (m[6] & 127) << 7, x = (m[7] & 127) | (m[8] & 127) << 7;
        if (y >= SIZE) return false;
        for (int i = 9; i + 4 <= n - 1; i += 4) {
            int c = (m[i + 1] & 127) | (m[i + 2] & 127) << 7 | (m[i + 3] & 127) << 14;
            for (int k = 0; k < (m[i] & 127); k++, x++)
                if (x < SIZE) pixels[y * SIZE + x] = c & 0xffff;
        }
        return true;
    }

    /** RGB565 to 8-bit red, green, blue. */
    static int[] rgb(int c) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        return new int[] {r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2};
    }

    /** Ways to show the screen, easier to read for some eyes. */
    enum Look {
        COLOR("Color"), BRIGHT("Bright color"), YELLOW("Yellow on black"), WHITE("White on black"), INVERTED("Black on white");

        final String label;

        Look(String label) { this.label = label; }

        /** An RGB565 pixel as ARGB. */
        int paint(int c) {
            int[] p = rgb(c);
            float luma = (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]) / 255f;
            // The FM-1 draws light text on black: lift anything that isn't background to full strength.
            int lit = (int) (Math.min(1f, luma * 1.6f) * 255f);
            switch (this) {
                case BRIGHT: return lift(bright(p[0]), bright(p[1]), bright(p[2]));
                case YELLOW: return argb(lit, lit, 0);
                case WHITE: return argb(lit, lit, lit);
                case INVERTED: return argb(255 - lit, 255 - lit, 255 - lit);
                default: return argb(p[0], p[1], p[2]);
            }
        }

        /** The whole screen in this look, into out. Bright color lifts only thin things on a dark background (text,
         *  lines): what lies in or next to a filled area (a picture, a panel, a dotted background) keeps its own
         *  colours, so a firmware's pictures (Erik37's piano) aren't washed out. */
        void paintAll(int[] px, int[] out, int side) {
            if (this != BRIGHT) {
                for (int i = 0; i < px.length; i++) out[i] = paint(px[i]);
                return;
            }
            boolean[] near = pictureMask(px, side);
            for (int i = 0; i < px.length; i++) out[i] = near[i] ? COLOR.paint(px[i]) : paint(px[i]);
        }

        /** Within 4 pixels of a spot whose 9x9 neighbourhood is more than 60% drawn (anything brighter than the black
         *  background, dark dotted shading included). Text strokes on black never get that full. */
        static boolean[] pictureMask(int[] px, int side) {
            int[] ink = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int[] p = rgb(px[i]);
                ink[i] = 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2] > 0.03f * 255f ? 1 : 0;
            }
            int[] count = new int[px.length], area = new int[px.length];
            window(ink, side, count, area);
            int[] core = new int[px.length];
            for (int i = 0; i < px.length; i++) core[i] = count[i] * 10 > area[i] * 6 ? 1 : 0;
            window(core, side, count, area);
            boolean[] near = new boolean[px.length];
            for (int i = 0; i < px.length; i++) near[i] = count[i] > 0;
            return near;
        }

        /** Counts of v in each pixel's 9x9 neighbourhood (clipped at the edges), by a summed-area table. */
        private static void window(int[] v, int side, int[] count, int[] area) {
            final int r = 4, w = side + 1;
            int[] sat = new int[w * w];
            for (int y = 0; y < side; y++)
                for (int x = 0; x < side; x++)
                    sat[(y + 1) * w + x + 1] = v[y * side + x] + sat[y * w + x + 1] + sat[(y + 1) * w + x] - sat[y * w + x];
            for (int y = 0; y < side; y++) {
                int y0 = Math.max(0, y - r), y1 = Math.min(side, y + r + 1);
                for (int x = 0; x < side; x++) {
                    int x0 = Math.max(0, x - r), x1 = Math.min(side, x + r + 1);
                    count[y * side + x] = sat[y1 * w + x1] + sat[y0 * w + x0] - sat[y0 * w + x1] - sat[y1 * w + x0];
                    area[y * side + x] = (y1 - y0) * (x1 - x0);
                }
            }
        }

        private static int bright(int v) { return (int) Math.min(255f, v * 1.6f); }

        /**
         * Brings any colour that isn't the black background up to at least 80% brightness, keeping
         * its hue: a dark purple on black becomes a light lilac that can be read.
         */
        private static int lift(int r, int g, int b) {
            float l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f, want = 0.8f;
            // A dark grey is background shading (Erik37 dots its background), not text: leave it dark. Lifting it
            // turned that whole screen light grey and hid the piano.
            int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            boolean shade = l < 0.25f && chroma < 24;
            if (l > 0.03f && l < want && !shade) {
                float t = (want - l) / (1f - l);
                r += (int) ((255 - r) * t);
                g += (int) ((255 - g) * t);
                b += (int) ((255 - b) * t);
            }
            return argb(r, g, b);
        }

        private static int argb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }
    }

    /** SysEx out of a byte stream that may arrive in pieces (Android hands USB MIDI over a few bytes at a time). */
    static final class Sysex {
        interface Sink { void message(byte[] m); }

        private final Sink sink;
        private final ByteArrayOutputStream part = new ByteArrayOutputStream();
        private boolean inside;

        Sysex(Sink sink) { this.sink = sink; }

        void feed(byte[] data, int offset, int count) {
            for (int i = offset; i < offset + count; i++) {
                int b = data[i] & 255;
                if (b == 0xf0) {
                    part.reset();
                    part.write(b);
                    inside = true;
                } else if (b >= 0xf8) {
                    // clock and other real-time bytes may come between any two others
                } else if (inside && b == 0xf7) {
                    part.write(b);
                    inside = false;
                    sink.message(part.toByteArray());
                } else if (inside && b < 0x80) {
                    part.write(b);
                } else {
                    inside = false; // a note or other message: not SysEx
                }
            }
        }
    }

    // ---- which device it is

    /** The FM-1's USB MIDI name: "FM1 Community" with Open firmware, "USB Composite Device" with the original. */
    static boolean isFm1(String name) {
        String n = name.toLowerCase();
        // (the Felucca family names itself: JIANT FM "Juan Jiant" / "JIANT", Felucca "Felucca", ChoralRoot "ChoralRoot";
        // JIANT's own updater, "JIANT Update", is not one to mirror)
        boolean felucca = (n.contains("jiant") && !n.contains("update")) || n.contains("felucca") || n.contains("choralroot");
        // (other community firmwares name their USB MIDI after themselves too: Erik37 "ERIK37", Skiff, LOOM-8)
        boolean community = n.contains("erik") || n.contains("skiff") || n.contains("loom");
        return (n.contains("fm1") || community || n.contains("fm-1") || felucca || n.contains("usb composite") || n.contains("usb-midi") || n.contains("usb midi") || isUpdaterName(n))
                && !n.contains("bridge");
    }

    /** Names its updater may have while it's waiting mid-update. */
    static boolean isUpdaterName(String n) {
        n = n.toLowerCase();
        for (String h : new String[] {"felucca", "sloop", "clipper", "ota", "sinco"})
            if (n.contains(h)) return true;
        return false;
    }

    static boolean isUpdater(String id) { return id.toLowerCase().startsWith("ota-"); }

    static boolean isOpenFirmware(String id) {
        return id.startsWith("FM-1_8") || id.equals("FM1_OFFICIAL_OPEN") || id.startsWith("FM1_COMMUNITY_");
    }

    static boolean isOriginal(String id) { return id.startsWith("FM-1_0"); }

    // ---- the updater's messages

    static byte[] frame(int command, byte[] payload) {
        int sum = 0;
        for (byte b : payload) sum += b & 255;
        int n = payload.length;
        byte[] raw = new byte[7 + n];
        raw[1] = 89;
        raw[2] = (byte) command;
        raw[3] = (byte) n;
        raw[4] = (byte) (n >> 8);
        System.arraycopy(payload, 0, raw, 6, n);
        raw[6 + n] = (byte) ~sum;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xf0);
        int bits = 0, value = 0;
        for (byte b : raw) {
            value |= (b & 255) << bits;
            bits += 8;
            while (bits >= 7) {
                out.write(value & 127);
                value >>>= 7;
                bits -= 7;
            }
        }
        if (bits > 0) out.write(value & 127);
        out.write(0xf7);
        return out.toByteArray();
    }

    /** An updater reply. */
    static final class Reply {
        final int command;
        final byte[] data;

        Reply(int command, byte[] data) {
            this.command = command;
            this.data = data;
        }
    }

    static Reply parse(byte[] m) {
        int n = m.length;
        if (n < 10 || n > 608 || m[0] != (byte) 0xf0 || m[n - 1] != (byte) 0xf7) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (int i = 1; i < n - 1; i++) {
            int b = m[i] & 255;
            if (b > 127) return null;
            value |= b << bits;
            bits += 7;
            if (bits >= 8) {
                out.write(value & 255);
                value >>>= 8;
                bits -= 8;
            }
        }
        byte[] raw = out.toByteArray();
        if (bits >= 7 || value != 0 || raw.length < 7 || raw[0] != 0 || raw[1] != 89) return null;
        int sum = 0;
        for (int i = 6; i < raw.length; i++) sum += raw[i] & 255;
        if (le(raw, 3, 3) != raw.length - 7 || (sum & 255) != 255) return null;
        return new Reply(raw[2] & 255, Arrays.copyOfRange(raw, 6, raw.length - 1));
    }

    static long le(byte[] p, int at, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v |= (long) (p[at + i] & 255) << (8 * i);
        return v;
    }

    /** What the FM-1 says it is ("FM-1_015", "FM-1_8000014", "ota-FM-1_015"), from a reply to command 17. */
    static String identity(byte[] d) {
        if (d == null || d.length != 27) return null;
        String s = new String(d, StandardCharsets.ISO_8859_1);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) end--;
        return s.substring(0, end);
    }

    /** The updater's status (a reply to command 49): family, state, current build. */
    static final class Capability {
        final int family, state;
        final String build;

        Capability(int family, int state, String build) {
            this.family = family;
            this.state = state;
            this.build = build;
        }
    }

    static Capability capability(byte[] d) {
        if (d == null || d.length != 47 || d[0] != 'F' || d[1] != 'M' || d[2] != 'C' || d[3] != '1' || d[4] != 1 || d[5] > 7
                || le(d, 8, 2) != 512 || le(d, 10, 4) != 1_048_576 || d[7] != 1)
            return null;
        StringBuilder b = new StringBuilder();
        for (int i = 14; i < 46; i++) b.append(String.format("%02x", d[i] & 255));
        return new Capability(d[5], d[6], b.toString());
    }

    /** The build a package installs, from its .build-identity.json ("build_id": "2d52…"). */
    static String buildId(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"build_id\"\\s*:\\s*\"([0-9a-fA-F]{64})\"").matcher(json);
        return m.find() ? m.group(1).toLowerCase() : "";
    }

    // ---- the package: an image with the product name woven into its first 960 bytes

    static int crc16(byte[] bytes, int from, int to) {
        int c = 0;
        for (int i = from; i < to; i++) {
            c ^= (bytes[i] & 255) << 8;
            for (int k = 0; k < 8; k++) c = ((c & 0x8000) != 0 ? (c << 1) ^ 0x1021 : c << 1) & 0xffff;
        }
        return c;
    }

    static final class Package {
        /** The product it installs as ("FM-1_8000014"). */
        final String product;
        /** What the FM-1 asks for, block by block. */
        final byte[] image;

        Package(String product, byte[] image) {
            this.product = product;
            this.image = image;
        }
    }

    /** Checked the way M-VAVE's updater checks it; throws with the reason. */
    static Package inspect(byte[] data) {
        if (data.length < 1024 || data.length > 2_097_152) throw new IllegalArgumentException("package length");
        byte[] image = new byte[data.length - 20];
        StringBuilder product = new StringBuilder();
        boolean ended = false;
        for (int i = 0; i < 20; i++) {
            System.arraycopy(data, i * 48, image, i * 47, 47);
            int marker = data[i * 48 + 47] & 255;
            if (marker == 125) {
                ended = true;
            } else {
                int c = (marker - (i + 1)) & 255;
                if (ended || c < 32 || c > 126) throw new IllegalArgumentException("package identity");
                product.append((char) c);
            }
        }
        System.arraycopy(data, 960, image, 940, data.length - 960);
        byte[] head = new byte[64];
        int key = 0xffff;
        for (int i = 0; i < 64; i++) {
            head[i] = (byte) (image[i] ^ key);
            key = ((key << 1) ^ ((key & 0x8000) != 0 ? 0x1021 : 0)) & 0xffff;
        }
        int count = (int) le(head, 8, 2);
        if (le(head, 0, 2) != crc16(head, 2, 64) || le(head, 4, 4) != image.length)
            throw new IllegalArgumentException("package header");
        if (!new String(head, 16, 6, StandardCharsets.ISO_8859_1).equals("AC791N") || count == 0 || count > 12
                || crc16(image, 64, 64 + count * 80) != le(head, 2, 2))
            throw new IllegalArgumentException("package directory");
        return new Package(product.toString(), image);
    }
}
