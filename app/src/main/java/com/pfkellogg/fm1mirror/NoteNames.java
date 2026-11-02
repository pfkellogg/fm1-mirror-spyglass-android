package com.pfkellogg.fm1mirror;

/** Note names and pitches, for the note names and the staff. */
final class NoteNames {

    private NoteNames() {
    }

    private static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /** Equal-tempered pitch, A4 (note 69) = 440 Hz. */
    static double hz(int note) {
        return 440.0 * Math.pow(2, (note - 69) / 12.0);
    }

    /** "C#" */
    static String pitchClass(int note) {
        return NAMES[note % 12];
    }

    /** "C#4" */
    static String name(int note) {
        return NAMES[note % 12] + (note / 12 - 1);
    }
}
