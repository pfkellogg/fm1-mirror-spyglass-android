package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Arrays;

/**
 * The notes being played, written on a grand staff (treble + bass clef) as
 * whole notes, with ledger lines and sharps. Middle C and up go on the treble
 * staff, B3 and down on the bass staff. Used in the spyglass (from Synth Spyglass for Android).
 *
 * Positions are worked out in staff spaces (the gap between two lines) and
 * scaled to fit the view; the symbols come from StaffGlyphs.
 */
class StaffView extends View {

    // Staff steps: one per white key, from MIDI note 0 (C-1) = 0. Lines are every other step.
    private static final int TREBLE_BOTTOM = 37;  // E4
    private static final int BASS_TOP = 33;       // A3
    private static final float GAP = 4;           // spaces between the two staves
    private static final float WIDTH = 11.2f;     // whole picture, in spaces
    private static final float NOTE_X = 7.4f;     // note column's left edge
    private static final int[] STEP = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
    private static final boolean[] SHARP = {false, true, false, true, false, false, true, false, true, false, true, false};

    private int[] notes = new int[0];
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    // Spaces -> pixels, set per onDraw. Everything is drawn in real pixels, never on a
    // scaled canvas: a hardware canvas renders a path at its unscaled size (a few pixels
    // in staff spaces) and stretches it, which made the clefs and notes a blur.
    private float ox, oy, sp;

    StaffView(Context ctx) {
        super(ctx);
        ink.setColor(Color.WHITE);
        line.setColor(Color.rgb(170, 170, 170));
        line.setStrokeCap(Paint.Cap.BUTT);
        path.setFillType(Path.FillType.EVEN_ODD);  // the clefs' loops are holes
    }

    /** One note, or a chord's keys. */
    void setNotes(int... notes) {
        int[] n = notes.clone();
        Arrays.sort(n);
        if (Arrays.equals(n, this.notes)) return;
        this.notes = n;
        invalidate();
    }

    private static int step(int note) {
        return 7 * (note / 12) + STEP[note % 12];
    }

    /** Where a note sits, in spaces below the treble staff's bottom line. */
    private static float y(int note) {
        int s = step(note);
        return note >= 60 ? (TREBLE_BOTTOM - s) / 2f : GAP + (BASS_TOP - s) / 2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        // Room for both staves plus two ledger lines each way; more if the notes go further.
        float top = -6.5f, bottom = GAP + 6.5f;
        for (int n : notes) {
            top = Math.min(top, y(n) - 1);
            bottom = Math.max(bottom, y(n) + 1);
        }
        sp = Math.min(getHeight() / (bottom - top), getWidth() / WIDTH);
        ox = (getWidth() - WIDTH * sp) / 2;
        oy = (getHeight() - (bottom - top) * sp) / 2 - top * sp;

        line.setStrokeWidth(Math.max(1, 0.12f * sp));
        for (int i = 0; i < 5; i++) {
            line(c, 0.2f, -i, WIDTH - 0.2f, -i, line);
            line(c, 0.2f, GAP + i, WIDTH - 0.2f, GAP + i, line);
        }
        line.setStrokeWidth(Math.max(1, 0.16f * sp));
        line(c, 0.2f, -4, 0.2f, GAP + 4, line);  // the brace line joining the staves
        glyph(c, StaffGlyphs.G_CLEF, 0.7f, 0);
        glyph(c, StaffGlyphs.F_CLEF, 0.7f, GAP + 4);

        float head = StaffGlyphs.WHOLE_WIDTH;
        // A second (two neighbouring steps) can't share a column: the upper head moves right.
        boolean[] right = new boolean[notes.length];
        for (int i = 1; i < notes.length; i++) {
            right[i] = (notes[i] >= 60) == (notes[i - 1] >= 60)
                    && step(notes[i]) - step(notes[i - 1]) == 1 && !right[i - 1];
        }
        boolean anyRight = false;
        for (boolean r : right) anyRight |= r;

        float ledgerFrom = NOTE_X - 0.45f, ledgerTo = NOTE_X + head * (anyRight ? 2 : 1) + 0.45f;
        line.setStrokeWidth(Math.max(1, 0.16f * sp));
        for (int n : notes) {
            float ny = y(n);
            float from = n >= 60 ? -4 : GAP;      // staff's top line
            float to = n >= 60 ? 0 : GAP + 4;     // staff's bottom line
            for (float l = from - 1; l >= ny - 0.01f; l--) line(c, ledgerFrom, l, ledgerTo, l, line);
            for (float l = to + 1; l <= ny + 0.01f; l++) line(c, ledgerFrom, l, ledgerTo, l, line);
        }

        // Sharps, from the top down; one closer to its note than another would clash moves further left.
        float[] colY = new float[notes.length];
        int[] colOf = new int[notes.length];
        int placed = 0;
        for (int i = notes.length - 1; i >= 0; i--) {
            if (!SHARP[notes[i] % 12]) continue;
            float ny = y(notes[i]);
            int col = 0;
            for (boolean clash = true; clash; ) {
                clash = false;
                for (int j = 0; j < placed; j++) {
                    if (colOf[j] == col && Math.abs(colY[j] - ny) < 3) {
                        clash = true;
                        col++;
                        break;
                    }
                }
            }
            colY[placed] = ny;
            colOf[placed++] = col;
            glyph(c, StaffGlyphs.SHARP, NOTE_X - 0.25f - StaffGlyphs.SHARP_WIDTH / 2 - col * 1.0f, ny);
        }

        for (int i = 0; i < notes.length; i++) {
            glyph(c, StaffGlyphs.WHOLE, NOTE_X + head / 2 + (right[i] ? head : 0), y(notes[i]));
        }
    }

    private void line(Canvas c, float x0, float y0, float x1, float y1, Paint p) {
        c.drawLine(ox + x0 * sp, oy + y0 * sp, ox + x1 * sp, oy + y1 * sp, p);
    }

    private void glyph(Canvas c, float[][] contours, float x, float y) {
        path.rewind();
        for (float[] pts : contours) {
            path.moveTo(ox + (x + pts[0]) * sp, oy + (y + pts[1]) * sp);
            for (int i = 2; i < pts.length; i += 2) path.lineTo(ox + (x + pts[i]) * sp, oy + (y + pts[i + 1]) * sp);
            path.close();
        }
        c.drawPath(path, ink);
    }
}
