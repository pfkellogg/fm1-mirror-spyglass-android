package com.pfkellogg.fm1mirror;

import java.util.Arrays;

/**
 * What's been played, for the spyglass: the last note and how hard, and the keys held now (for
 * chord names), fed from MIDI threads: one Stream per input, each with its own running status.
 */
final class Played {
    private final boolean[] down = new boolean[128];
    private final int[] vel = new int[128];
    private int lastNote = -1, lastVelocity, firstVelocity = -1;
    private boolean velocityVaries;
    private long noteOns;
    /** The sound each input's channel last picked (Program Change), and where the last note came from. */
    private final java.util.Map<String, int[]> programs = new java.util.HashMap<>();
    private int lastChannel;
    private String lastSource;
    /** What the FM-1 says it's playing ("T1 FM6 PIANO 1"), and when it said it (it says it after every hello). */
    private String fm1Sound;
    private long fm1SoundAt;
    private static final long FM1_SOUND_STALE_MS = 3000;

    Stream stream(String source) { return new Stream(source); }

    /** FM-1 Mirror's own two: a keyboard over straight USB (no MIDI service), and the FM-1's own keys (KEY OUT). */
    final Stream keys = new Stream("keys"), fm1 = new Stream("FM-1");

    /** One input's running status; SysEx passes through untouched. */
    final class Stream {
        private final String source;
        private int status, d1, have;
        /** A SysEx coming in, while it's short enough to be the FM-1's sound name (F0 7D 'F' 'S' 02 name F7). */
        private final byte[] sx = new byte[64];
        private int sxLen = -1;

        Stream(String source) {
            this.source = source;
        }

        void feed(byte[] m, int offset, int count) {
            synchronized (Played.this) {
                for (int i = offset; i < offset + count; i++) {
                    int b = m[i] & 0xff;
                    if (b >= 0xf8) continue; // clock and the like
                    if (b == 0xf0) sxLen = 0;
                    if (sxLen >= 0) {
                        if (b == 0xf7) {
                            sysex(sx, sxLen);
                            sxLen = -1;
                        } else if (b >= 0x80 && b != 0xf0) {
                            sxLen = -1; // a status byte ends a SysEx that never closed
                        } else if (sxLen < sx.length) {
                            sx[sxLen++] = (byte) b;
                            continue;
                        } else {
                            sxLen = -2; // too long for a name: one of its screen rows; wait for its end
                            continue;
                        }
                    } else if (sxLen == -2) {
                        if (b == 0xf7) sxLen = -1;
                        if (b < 0x80 || b == 0xf7) continue;
                        sxLen = -1;
                    }
                    if (b >= 0x80) {
                        status = b < 0xf0 ? b : 0;
                        have = 0;
                        continue;
                    }
                    if (status == 0) continue;
                    int type = status & 0xf0;
                    if (type == 0xd0) continue; // nothing to track
                    if (type == 0xc0) { // one data byte: the sound picked
                        int[] pr = programs.get(source);
                        if (pr == null) {
                            pr = new int[16];
                            java.util.Arrays.fill(pr, -1);
                            programs.put(source, pr);
                        }
                        pr[status & 15] = b;
                        continue;
                    }
                    if (have == 0) {
                        d1 = b;
                        have = 1;
                        continue;
                    }
                    have = 0; // running status: the next pair reuses it
                    if (type == 0x90 && b > 0) {
                        lastChannel = status & 15;
                        lastSource = source;
                    }
                    event(type, d1, b);
                }
            }
        }
    }

    /** An FM-1 saying the sound it's playing: F0 7D 'F' 'S' 02 and its name. */
    private void sysex(byte[] s, int n) {
        if (n < 5 || s[0] != (byte) 0xf0 || s[1] != 0x7d || s[2] != 'F' || s[3] != 'S' || s[4] != 2) return;
        StringBuilder b = new StringBuilder();
        for (int i = 5; i < n; i++) if (s[i] >= 0x20 && s[i] < 0x7f) b.append((char) s[i]);
        fm1Sound = b.toString().trim().replaceAll("\\s+", " ");
        fm1SoundAt = System.currentTimeMillis();
    }

    private void event(int type, int note, int value) {
        if (type == 0x90 && value > 0) {
            down[note] = true;
            vel[note] = value;
            lastNote = note;
            lastVelocity = value;
            if (firstVelocity < 0) firstVelocity = value;
            else if (value != firstVelocity) velocityVaries = true;
            noteOns++;
        } else if (type == 0x80 || type == 0x90) {
            down[note] = false;
        } else if (type == 0xb0 && (note == 123 || note == 120)) {
            Arrays.fill(down, false);
        }
    }

    synchronized long noteOns() { return noteOns; }

    /** The sound the FM-1 says it's playing, while it keeps saying it. */
    synchronized String fm1Sound() {
        return fm1Sound != null && !fm1Sound.isEmpty() && System.currentTimeMillis() - fm1SoundAt < FM1_SOUND_STALE_MS ? fm1Sound : null;
    }

    /** The sound the last note's channel picked on its input, if a Program Change said. */
    synchronized String sound() {
        if (lastSource == null) return null;
        int[] pr = programs.get(lastSource);
        return pr != null && pr[lastChannel] >= 0 ? Gm.soundName(lastChannel, pr[lastChannel]) : null;
    }

    synchronized int lastNote() { return lastNote; }

    /** How hard the last key was hit; -1 until the keyboard has sent two different ones (some always send the same). */
    synchronized int velocity() { return velocityVaries ? lastVelocity : -1; }

    /** The keys held now and how hard each was hit (0-1; 100/127 from a keyboard that doesn't send it), as {note, level} pairs. */
    synchronized float[][] held() {
        int n = 0;
        for (boolean d : down) if (d) n++;
        float[][] h = new float[n][];
        n = 0;
        for (int i = 0; i < 128; i++) if (down[i]) h[n++] = new float[] {i, (velocityVaries ? vel[i] : 100) / 127f};
        return h;
    }

    synchronized int[] keysDown() {
        int n = 0;
        for (boolean d : down) if (d) n++;
        int[] k = new int[n];
        n = 0;
        for (int i = 0; i < 128; i++) if (down[i]) k[n++] = i;
        return k;
    }
}
