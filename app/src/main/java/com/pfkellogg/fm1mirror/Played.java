package com.pfkellogg.fm1mirror;

import java.util.Arrays;

/**
 * What the keyboard has played, for the spyglass: the last note and how hard, and the keys held
 * now (for chord names). Fed the bytes Keys passes to the FM-1, from a MIDI thread.
 */
final class Played {
    private final boolean[] down = new boolean[128];
    private int status, d1, have;
    private int lastNote = -1, lastVelocity, firstVelocity = -1;
    private boolean velocityVaries;
    private long noteOns;

    synchronized void feed(byte[] m, int count) {
        for (int i = 0; i < count; i++) {
            int b = m[i] & 0xff;
            if (b >= 0xf8) continue; // clock and the like
            if (b >= 0x80) {
                status = b < 0xf0 ? b : 0;
                have = 0;
                continue;
            }
            if (status == 0) continue;
            int type = status & 0xf0;
            if (type == 0xc0 || type == 0xd0) continue; // one data byte, nothing to track
            if (have == 0) {
                d1 = b;
                have = 1;
                continue;
            }
            have = 0; // running status: the next pair reuses it
            if (type == 0x90 && b > 0) {
                down[d1] = true;
                lastNote = d1;
                lastVelocity = b;
                if (firstVelocity < 0) firstVelocity = b;
                else if (b != firstVelocity) velocityVaries = true;
                noteOns++;
            } else if (type == 0x80 || type == 0x90) {
                down[d1] = false;
            } else if (type == 0xb0 && (d1 == 123 || d1 == 120)) {
                Arrays.fill(down, false);
            }
        }
    }

    synchronized long noteOns() { return noteOns; }

    synchronized int lastNote() { return lastNote; }

    /** How hard the last key was hit; -1 until the keyboard has sent two different ones (some always send the same). */
    synchronized int velocity() { return velocityVaries ? lastVelocity : -1; }

    synchronized int[] keysDown() {
        int n = 0;
        for (boolean d : down) if (d) n++;
        int[] k = new int[n];
        n = 0;
        for (int i = 0; i < 128; i++) if (down[i]) k[n++] = i;
        return k;
    }
}
