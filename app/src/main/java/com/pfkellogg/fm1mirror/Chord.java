package com.pfkellogg.fm1mirror;

/**
 * Names the chord made by a set of held keys, e.g. C4 E4 G4 → "C major",
 * E3 G3 C4 → "C major / E", A3 C4 E4 G4 → "Am7". Works on pitch classes, so
 * any voicing or octave doubling names the same; the lowest key decides the
 * slash bass. Two different notes aren't a chord but an interval: E3 G3 →
 * "E + G", with {@link #interval} "minor 3rd" for the line under it.
 * Returns null for anything else, and the screen shows the single note as usual.
 */
final class Chord {

    private Chord() {
    }

    // Intervals above the root (semitones), and the suffix printed after the root.
    // Order matters only for ties, which these exact pitch-class sets don't have
    // except the symmetrical ones (aug, dim7), where the bass picks the root.
    private static final int[][] SHAPES = {
            {0, 4, 7}, {0, 3, 7}, {0, 3, 6}, {0, 4, 8}, {0, 2, 7}, {0, 5, 7},
            {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}, {0, 3, 6, 10}, {0, 3, 6, 9},
            {0, 3, 7, 11}, {0, 4, 7, 9}, {0, 3, 7, 9}, {0, 5, 7, 10}, {0, 2, 4, 7}, {0, 2, 3, 7},
            {0, 2, 4, 7, 10}, {0, 2, 4, 7, 11}, {0, 2, 3, 7, 10},
            {0, 4, 10}, {0, 4, 11}, {0, 3, 10},  // sevenths with the fifth left out
    };
    private static final String[] SUFFIX = {
            " major", " minor", "dim", "aug", "sus2", "sus4",
            "7", "maj7", "m7", "m7b5", "dim7",
            "m(maj7)", "6", "m6", "7sus4", "add9", "m(add9)",
            "9", "maj9", "m9",
            "7", "maj7", "m7",
    };

    /**
     * Two keys down is often just legato playing (the next key pressed a moment
     * before the last one is let go), so an interval must stay held this much
     * longer than a chord before it's named.
     */
    static final long PAIR_EXTRA_MS = 200;

    // Semitones from the lower note up to the higher, within an octave.
    private static final String[] INTERVALS = {
            "octave", "minor 2nd", "major 2nd", "minor 3rd", "major 3rd", "4th",
            "tritone", "5th", "minor 6th", "major 6th", "minor 7th", "major 7th",
    };

    /**
     * Chord name for these MIDI notes ("E + G" for an interval), or null if
     * they aren't a chord it knows.
     */
    static String name(int[] notes) {
        String pair = pairBass(notes) < 0 ? null : pairName(notes);
        if (pair != null || notes.length < 3) return pair;
        int bass = lowest(notes) % 12;
        int mask = mask(notes);
        if (Integer.bitCount(mask) < 3) return null;

        String best = null;
        for (int root = 0; root < 12; root++) {
            if ((mask & (1 << root)) == 0) continue;
            for (int s = 0; s < SHAPES.length; s++) {
                if (shapeMask(SHAPES[s], root) != mask) continue;
                String name = NoteNames.pitchClass(root) + SUFFIX[s];
                if (root == bass) return name;          // root position wins outright
                if (best == null) best = name + " / " + NoteNames.pitchClass(bass);
            }
        }
        return best;
    }

    /** True if these keys are an interval (two notes, or one note in octaves). */
    static boolean isPair(int[] notes) {
        return pairBass(notes) >= 0;
    }

    /** "minor 3rd" for E3 G3, "5th" for C3 G3 C4, "octave" for C3 C4; else null. */
    static String interval(int[] notes) {
        int bass = pairBass(notes);
        if (bass < 0) return null;
        int mask = mask(notes) & ~(1 << bass);
        int other = mask == 0 ? bass : Integer.numberOfTrailingZeros(mask);
        return INTERVALS[(other - bass + 12) % 12];
    }

    /** The lower note's pitch class if these keys are an interval, else -1. */
    private static int pairBass(int[] notes) {
        if (notes.length < 2) return -1;
        int pcs = Integer.bitCount(mask(notes));
        return pcs == 2 || pcs == 1 ? lowest(notes) % 12 : -1;
    }

    /** "E + G": the lower note first. */
    private static String pairName(int[] notes) {
        int bass = pairBass(notes);
        int mask = mask(notes) & ~(1 << bass);
        int other = mask == 0 ? bass : Integer.numberOfTrailingZeros(mask);
        return NoteNames.pitchClass(bass) + " + " + NoteNames.pitchClass(other);
    }

    private static int mask(int[] notes) {
        int m = 0;
        for (int n : notes) m |= 1 << (n % 12);
        return m;
    }

    private static int shapeMask(int[] shape, int root) {
        int m = 0;
        for (int i : shape) m |= 1 << ((root + i) % 12);
        return m;
    }

    private static int lowest(int[] notes) {
        int lo = notes[0];
        for (int n : notes) lo = Math.min(lo, n);
        return lo;
    }
}
