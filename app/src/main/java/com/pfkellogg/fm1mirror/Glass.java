package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/**
 * The spyglass (Synth Spyglass for Android's, in this app's own window): the last note or chord
 * played on the keyboard in a round brass eyepiece beside the FM-1's screen, with the notes on a
 * staff and how hard the key was hit. Drag it to move it; pinch it, or tap it and drag the grip on
 * its rim, to resize it; tap it and ✕ to put it away. Where it is and its size are remembered.
 */
final class Glass extends FrameLayout {
    private static final int BRASS = Color.rgb(0xc9, 0x9a, 0x45);
    private static final int BRASS_LIGHT = Color.rgb(0xf3, 0xd6, 0x8a);
    private static final int BRASS_DARK = Color.rgb(0x8a, 0x62, 0x24);
    private static final int BRASS_SHADOW = Color.rgb(0x3e, 0x2a, 0x0e);
    private static final long CHORD_SETTLE_MS = 100, HANDLES_MS = 4000;
    private static final long HOLD_MS = 3000;   // a note or chord stays this long after the last key comes up
    private long releasedAt = -1;

    private final Played played;
    private final SharedPreferences prefs;
    private final Runnable onClose;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final StaffView staff;
    private TextView close, grip;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private static final int ACCENT = Color.rgb(80, 200, 255), DIM = Color.rgb(110, 120, 130);
    // The FM-1's sound, while it's plugged in and listening is allowed (Scope.java).
    private Scope scope;
    private long scopeLookedAt;
    private float[] wave;
    private boolean staffBeside;
    private final android.graphics.Path waveLine = new android.graphics.Path();
    private int size; // diameter, pixels

    // What's on show: one note, or a chord's keys and name.
    private int[] shownNotes;
    private String shownChord;
    private int shownVel = -1;
    /** The sound playing, when the FM-1 says it: across the top of the glass. */
    private String sound;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG), soundText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int SOUND = Color.rgb(0xf7, 0xdc, 0x92), SOUND_GLASS = Color.argb(225, 16, 12, 6),
            SOUND_SHINE = Color.argb(18, 255, 240, 200);

    // Name a chord once the keys stay put (Synth Spyglass's rule).
    private long seenNoteOns = -1;
    private boolean chordArmed;
    private int[] settlingKeys = new int[0];
    private long settlingSince;

    Glass(Context c, Played played, SharedPreferences prefs, Runnable onClose) {
        super(c);
        this.played = played;
        this.prefs = prefs;
        this.onClose = onClose;
        density = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        staff = new StaffView(c);
        addView(staff);
        close = handle("✕");
        close.setOnClickListener(v -> onClose.run());
        grip = handle("↔");
        grip.setRotation(45); // a diagonal two-headed arrow
        grip.setOnTouchListener(resize);
        setOnTouchListener(move);
        size = prefs.getInt("glassSize", Math.round(260 * density));
        setLayoutParams(new FrameLayout.LayoutParams(size, size));
        layoutParts();
        showNothing();
    }

    /**
     * Puts it where it was; the first time, in the room right of the FM-1's (square, centred)
     * screen if there's enough, else top right over it.
     */
    void place(int parentW, int parentH) {
        if (!prefs.contains("glassX")) {
            float side = (parentW - Math.min(parentW, parentH)) / 2f - 16 * density;
            if (side >= 160 * density) size = Math.round(Math.min(side, 300 * density));
            float x = parentW - size - (side >= 160 * density ? (side + 16 * density - size) / 2 : 16 * density);
            resizeTo(size, parentW, parentH);
            moveTo(x, (parentH - size) / 2f);
            return;
        }
        resizeTo(size, parentW, parentH);
        moveTo(prefs.getFloat("glassX", 0), prefs.getFloat("glassY", 0));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ui.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideHandles);
        if (scope != null) scope.close();
        scope = null;
    }

    private TextView handle(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setVisibility(View.GONE);
        addView(t);
        return t;
    }

    /** Sizes the staff and the handles to the diameter. */
    private void layoutParts() {
        int w = Math.round(size * (staffBeside ? 0.42f : 0.45f)), h = Math.round(w * 1.06f); // the staff is what's read: as big as the glass allows
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        slp.leftMargin = Math.round(size / 2f + (staffBeside ? -size * 0.165f : 0) - w / 2f); // left of the waveform
        slp.topMargin = Math.round(size / 2f + size * 0.087f - h / 2f);
        staff.setLayoutParams(slp);

        int c = Math.round(size * 0.12f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(c, c, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = Math.round(size * 0.035f);
        close.setLayoutParams(clp);
        close.setBackground(circle(Color.rgb(28, 28, 30), Color.rgb(90, 90, 90)));
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, c * 0.5f);

        int g = Math.round(size * 0.14f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(g, g, Gravity.BOTTOM | Gravity.END);
        glp.rightMargin = glp.bottomMargin = Math.round(size * 0.08f);
        grip.setLayoutParams(glp);
        grip.setBackground(circle(BRASS, BRASS_SHADOW));
        grip.setTextColor(BRASS_SHADOW);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_PX, g * 0.6f);
        invalidate();
    }

    private GradientDrawable circle(int fill, int ring) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        d.setStroke(Math.round(2 * density), ring);
        return d;
    }

    private void resizeTo(int px, int parentW, int parentH) {
        int max = Math.min(parentW, parentH);
        px = Math.max(Math.round(120 * density), Math.min(max > 0 ? max : px, px));
        if (px == getLayoutParams().width) return;
        size = px;
        getLayoutParams().width = getLayoutParams().height = px;
        layoutParts();
        requestLayout();
    }

    private void resizeTo(int px) {
        View parent = (View) getParent();
        if (parent != null) resizeTo(px, parent.getWidth(), parent.getHeight());
        moveTo(getX(), getY());
    }

    /** Keeps it inside the parent. */
    private void moveTo(float x, float y) {
        View parent = (View) getParent();
        if (parent != null && parent.getWidth() > 0) {
            x = Math.max(0, Math.min(parent.getWidth() - size, x));
            y = Math.max(0, Math.min(parent.getHeight() - size, y));
        }
        setX(x);
        setY(y);
    }

    private void save() {
        prefs.edit().putInt("glassSize", size).putFloat("glassX", getX()).putFloat("glassY", getY()).apply();
    }

    // ---- touch: drag to move, pinch to resize, tap for the handles

    private ScaleGestureDetector pinch;

    private final View.OnTouchListener move = new View.OnTouchListener() {
        private float lastX, lastY, downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (pinch == null) {
                pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        resizeTo(Math.round(size * d.getScaleFactor()));
                        return true;
                    }
                });
            }
            pinch.onTouchEvent(e);
            if (e.getPointerCount() > 1) {
                dragged = true; // a pinch is never a tap
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    dragged = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) dragged = true;
                    if (dragged) moveTo(getX() + e.getRawX() - lastX, getY() + e.getRawY() - lastY);
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    if (!dragged) toggleHandles();
                    save();
                    break;
            }
            return true;
        }
    };

    private final View.OnTouchListener resize = new View.OnTouchListener() {
        private float lastX, lastY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ui.removeCallbacks(hideHandles);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // It grows from its top-left corner, so the grip follows the finger.
                    float d = ((e.getRawX() - lastX) + (e.getRawY() - lastY)) / 2f;
                    resizeTo(Math.round(size + d / 0.9f));
                    break;
                case MotionEvent.ACTION_UP:
                    save();
                    ui.postDelayed(hideHandles, HANDLES_MS);
                    break;
            }
            lastX = e.getRawX();
            lastY = e.getRawY();
            return true;
        }
    };

    private void toggleHandles() {
        boolean show = close.getVisibility() != View.VISIBLE;
        close.setVisibility(show ? View.VISIBLE : View.GONE);
        grip.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideHandles);
        if (show) ui.postDelayed(hideHandles, HANDLES_MS);
    }

    private final Runnable hideHandles = () -> {
        close.setVisibility(View.GONE);
        grip.setVisibility(View.GONE);
    };

    // ---- showing

    private void showNothing() {
        shownNotes = null;
        shownChord = null;
        shownVel = -1;
        staff.setVisibility(View.VISIBLE);   // the staves stay, empty, over "play a note"
        staff.setNotes();
        invalidate();
    }

    private void showNote(int note) {
        shownNotes = new int[] {note};
        shownChord = null;
        shownVel = played.velocity();
        staff.setVisibility(View.VISIBLE);
        staff.setNotes(note);
        invalidate();
    }

    private void showChord(int[] keys, String chord) {
        shownNotes = keys;
        shownChord = chord;
        shownVel = -1;
        staff.setVisibility(View.VISIBLE);
        staff.setNotes(keys);
        invalidate();
    }

    /** The FM-1's sound: open it when it's plugged in (looked for every 2 s), and keep the wave fresh. */
    private void listen() {
        long now = System.currentTimeMillis();
        if ((scope == null || scope.isDead()) && now - scopeLookedAt > 2000) {
            scopeLookedAt = now;
            scope = Scope.open(getContext());
        }
        boolean hearing = scope != null && scope.listening(), before = staffBeside;
        float[] w = hearing && shownNotes != null ? scope.wave(NoteNames.hz(shownNotes[0])) : null;
        staffBeside = hearing;
        if (staffBeside != before && size > 0) layoutParts();
        if (w != null || wave != null || staffBeside != before) {
            wave = w;
            invalidate();
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            listen();
            long ons = played.noteOns();
            if (ons != seenNoteOns) {
                seenNoteOns = ons;
                if (played.lastNote() >= 0) {
                    showNote(played.lastNote());
                    chordArmed = true;
                }
            }
            if (chordArmed) {
                int[] keys = played.keysDown();
                long now = System.currentTimeMillis();
                if (!Arrays.equals(keys, settlingKeys)) {
                    settlingKeys = keys;
                    settlingSince = now;
                } else if (now - settlingSince >= CHORD_SETTLE_MS + (Chord.isPair(keys) ? Chord.PAIR_EXTRA_MS : 0)) {
                    chordArmed = false;
                    String chord = Chord.name(keys);
                    if (chord != null) showChord(keys, chord);
                }
            }
            if (shownNotes == null || played.keysDown().length > 0) {
                releasedAt = -1;
            } else {
                long now = System.currentTimeMillis();
                if (releasedAt < 0) releasedAt = now;
                else if (now - releasedAt >= HOLD_MS) {
                    releasedAt = -1;
                    showNothing();
                }
            }
            ui.postDelayed(this, 50);
        }
    };

    void setSound(String s) {
        sound = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
        lens(c, cx, cy, d);
        if (sound != null) soundBand(c, sound, cx, cy - d * 0.325f, d);
        String name, detail;
        if (shownNotes == null) {
            name = "♪";
            detail = "play a note";
        } else if (shownChord != null) {
            name = shownChord;
            StringBuilder sb = new StringBuilder();
            for (int n : shownNotes) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(NoteNames.name(n));
            }
            String interval = Chord.interval(shownNotes);
            if (interval != null) sb.append("   ·   ").append(interval);
            detail = sb.toString();
        } else {
            int n = shownNotes[0];
            name = NoteNames.pitchClass(n);
            detail = String.format(Locale.US, "%s   %.0f Hz", NoteNames.name(n), NoteNames.hz(n));
            if (shownVel > 0) detail += "   vel " + shownVel;
        }
        if (shownNotes == null) { // nothing played: the empty staves, and the hint where a note's details go
            label(c, detail, cx, cy + d * 0.365f, Math.max(12 * density, d * 0.055f), d * 0.54f, Color.WHITE);
        } else {
            label(c, name, cx, cy - d * 0.215f, shownChord != null ? d * 0.10f : d * 0.115f, d * 0.72f, Color.WHITE);
            label(c, detail, cx, cy + d * 0.365f, Math.max(12 * density, d * 0.055f), d * 0.54f, Color.WHITE);
        }
        if (staffBeside) {
            // The FM-1's real waveform, right of the staff; a flat grey line while it's quiet.
            r.set(cx + d * 0.075f, cy - d * 0.02f, cx + d * 0.355f, cy + d * 0.19f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2 * density, d * 0.006f));
            p.setStrokeJoin(Paint.Join.ROUND);
            float[] w = wave;
            waveLine.reset();
            if (w != null) {
                p.setColor(ACCENT);
                for (int i = 0; i < w.length; i++) {
                    float x = r.left + r.width() * i / (w.length - 1), y = r.centerY() - w[i] * r.height() / 2;
                    if (i == 0) waveLine.moveTo(x, y);
                    else waveLine.lineTo(x, y);
                }
            } else {
                p.setColor(DIM);
                waveLine.moveTo(r.left, r.centerY());
                waveLine.lineTo(r.right, r.centerY());
            }
            c.drawPath(waveLine, p);
        }
        if (shownChord == null && shownVel > 0) {
            // How hard the key was hit: a short brass bar under it, full at 127.
            float w = d * 0.26f, h = Math.max(3 * density, d * 0.012f), top = cy + d * 0.405f - h / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(BRASS_SHADOW);
            r.set(cx - w / 2, top, cx + w / 2, top + h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            p.setColor(BRASS_LIGHT);
            r.set(cx - w / 2, top, cx - w / 2 + w * shownVel / 127f, top + h);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }
    }

    /** The sound's name: warm gold letters, a little spaced, on a rounded band of dark smoked glass with a thin
     *  brass edge (the rim's) and a soft shine on its upper half (as FM-1 Mirror on a computer). */
    private void soundBand(Canvas c, String s, float x, float y, float d) {
        String t = "\u266a  " + s;
        float size = Math.max(12 * density, d * 0.06f), room = d * 0.5f;
        soundText.setLetterSpacing(0.06f);
        soundText.setTextSize(size);
        float w = soundText.measureText(t);
        if (w > room) {
            size *= room / w;
            soundText.setTextSize(size);
            w = soundText.measureText(t);
        }
        Paint.FontMetrics m = soundText.getFontMetrics();
        float h = (m.descent - m.ascent) + size * 0.5f, bw = w + size * 1.4f;
        r.set(x - bw / 2, y - h / 2, x + bw / 2, y + h / 2);
        float rr = h / 2;
        band.setStyle(Paint.Style.FILL);
        band.setColor(Color.argb(90, 0, 0, 0));                      // its shadow
        r.offset(0, 2 * density);
        c.drawRoundRect(r, rr, rr, band);
        r.offset(0, -2 * density);
        band.setColor(SOUND_GLASS);
        c.drawRoundRect(r, rr, rr, band);
        band.setColor(SOUND_SHINE);
        RectF up = new RectF(r.left + 2 * density, r.top + 2 * density, r.right - 2 * density, r.centerY());
        c.drawRoundRect(up, up.height() / 2, up.height() / 2, band);
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeWidth(1.25f * density);
        band.setColor(BRASS_LIGHT);
        c.drawRoundRect(r, rr, rr, band);
        float base = y - (m.ascent + m.descent) / 2;
        soundText.setTextAlign(Paint.Align.CENTER);
        soundText.setColor(Color.argb(200, 0, 0, 0));
        c.drawText(t, x, base + density, soundText);                      // its shadow
        soundText.setColor(SOUND);
        c.drawText(t, x + 0.45f * density, base, soundText);              // (twice, a hair apart: a touch bolder)
        c.drawText(t, x, base, soundText);
    }

    /** Text centred on (x, y), smaller if it wouldn't fit in `room`. */
    private void label(Canvas c, String s, float x, float y, float size, float room, int color) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > room) text.setTextSize(size * room / w);
        text.setColor(color);
        Paint.FontMetrics m = text.getFontMetrics();
        c.drawText(s, x, y - (m.ascent + m.descent) / 2, text);
    }

    /** A spyglass's eyepiece: dark glass and a knurled brass rim lit from the top left. */
    private void lens(Canvas c, float cx, float cy, float d) {
        float rad = d / 2, rim = Math.max(7 * density, d * 0.05f), gr = rad - rim;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, gr, new int[] {Color.argb(200, 0x0e, 0x14, 0x1a), Color.argb(200, 0x0e, 0x14, 0x1a), Color.argb(235, 4, 6, 9)},
                new float[] {0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, gr, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        // The brass rim: bands from dark to light and back, so it looks rounded.
        int[] bands = {BRASS_SHADOW, BRASS_DARK, BRASS, BRASS_LIGHT, BRASS, BRASS_DARK};
        float bw = rim / bands.length;
        p.setStrokeWidth(bw + 0.6f);
        for (int i = 0; i < bands.length; i++) {
            p.setColor(bands[i]);
            c.drawCircle(cx, cy, gr + bw * (i + 0.5f), p);
        }
        // Light from the top left, shade at the bottom right.
        p.setStrokeWidth(rim * 0.35f);
        p.setColor(Color.argb(70, 255, 240, 200));
        ring(cx, cy, rad - rim * 0.45f);
        c.drawArc(r, 198, 90, false, p);
        p.setStrokeWidth(rim * 0.5f);
        p.setColor(Color.argb(90, 40, 25, 5));
        ring(cx, cy, rad - rim * 0.5f);
        c.drawArc(r, 18, 90, false, p);
        // Knurling on the outer edge.
        int ticks = (int) ((rad * 2 * Math.PI) / Math.max(5 * density, d * 0.025f));
        p.setStrokeWidth(Math.max(1, d * 0.004f));
        p.setColor(Color.argb(130, 40, 25, 5));
        for (int k = 0; k < ticks; k++) {
            double a = 2 * Math.PI * k / ticks;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * (rad - rim * 0.42f), cy + dy * (rad - rim * 0.42f), cx + dx * (rad - 0.5f), cy + dy * (rad - 0.5f), p);
        }
        // A fine dark line where the glass meets the brass, and the outer edge.
        p.setColor(BRASS_SHADOW);
        p.setStrokeWidth(Math.max(1, d * 0.004f));
        c.drawCircle(cx, cy, gr, p);
        p.setStrokeWidth(1);
        c.drawCircle(cx, cy, rad - 0.5f, p);
    }

    private void ring(float cx, float cy, float radius) {
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }
}
