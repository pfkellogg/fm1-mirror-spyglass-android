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

    // ---- the screen

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

        private static int bright(int v) { return (int) Math.min(255f, v * 1.6f); }

        /**
         * Brings any colour that isn't the black background up to at least 80% brightness, keeping
         * its hue: a dark purple on black becomes a light lilac that can be read.
         */
        private static int lift(int r, int g, int b) {
            float l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f, want = 0.8f;
            if (l > 0.03f && l < want) {
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
        return (n.contains("fm1") || n.contains("fm-1") || n.contains("usb composite") || n.contains("usb-midi") || n.contains("usb midi") || isUpdaterName(n))
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
