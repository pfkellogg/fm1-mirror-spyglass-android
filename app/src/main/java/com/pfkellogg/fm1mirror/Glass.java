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
 * The spyglass (from FM-1 Mirror + Spyglass, after Synth Spyglass for Android): the last note or chord played in a
 * round brass eyepiece, with the notes on a staff, their waveform, and how hard the key was hit. Tap it (on a TV:
 * OK) for the MIDI connections; drag it to move it; pinch it, or long-press it and drag the grip on its rim, to
 * resize it. Where it is and its size are remembered.
 */
final class Glass extends FrameLayout {
    private static final int BRASS = Color.rgb(0xc9, 0x9a, 0x45);
    private static final int BRASS_LIGHT = Color.rgb(0xf3, 0xd6, 0x8a);
    private static final int BRASS_DARK = Color.rgb(0x8a, 0x62, 0x24);
    private static final int BRASS_SHADOW = Color.rgb(0x3e, 0x2a, 0x0e);
    private static final long CHORD_SETTLE_MS = 100, HANDLES_MS = 4000;
    private static final long HOLD_MS = 3000;   // a note or chord stays this long after the last key comes up
    private long releasedAt = -1;

    private Played played = new Played();
    /** The pair on show (MainActivity sets it): its keyboard's name for the header with several pairs (null with
     * one), and whether the FM-1 plays it (then the header and the wave are the FM-1's). */
    private String pairName;
    private boolean fm1Plays;
    private final java.util.function.Supplier<String> fm1Said;
    private final Runnable onSwitch;
    private TextView swap;
    private final SharedPreferences prefs;
    private final Runnable onMenu;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final StaffView staff;
    private TextView close, grip;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    /** The notes' waveform beside the staff (on unless turned off). */
    private boolean wave;
    private final android.graphics.Path waveLine = new android.graphics.Path();
    private static final int WAVE = Color.rgb(80, 200, 255), WAVE_QUIET = Color.rgb(110, 120, 130), WAVE_POINTS = 960;
    /** The live waveform's notes: held, or fading after their key came up (note, level, time up or -1). */
    private final java.util.List<float[]> sounding = new java.util.ArrayList<>();
    private final java.util.List<Long> soundingUp = new java.util.ArrayList<>();
    private long waveStart = -1;
    /** The FM-1's real sound, while it's plugged in and listening is allowed (Scope.java). */
    private Scope scope;
    private long scopeLookedAt;
    private static final long FADE_MS = 600;
    private static final double WINDOW = 0.02; // seconds shown, as MIDI Bridge's spyglass: an octave up shows twice as many waves
    /** What's playing, across the top (null: not known, or turned off). */
    private String sound;
    private boolean soundOn;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG), soundText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int SOUND = Color.rgb(0xf7, 0xdc, 0x92), SOUND_GLASS = Color.argb(225, 16, 12, 6),
            SOUND_SHINE = Color.argb(18, 255, 240, 200);
    /** What it says while nothing has been played. */
    private String idle = "play a note";
    private int size; // diameter, pixels

    // What's on show: one note, or a chord's keys and name.
    private int[] shownNotes;
    private String shownChord;
    private int shownVel = -1;

    // Name a chord once the keys stay put (Synth Spyglass's rule).
    private long seenNoteOns = -1;
    private boolean chordArmed;
    private int[] settlingKeys = new int[0];
    private long settlingSince;

    Glass(Context c, SharedPreferences prefs, Runnable onMenu, Runnable onSwitch, java.util.function.Supplier<String> fm1Said) {
        super(c);
        this.onSwitch = onSwitch;
        this.fm1Said = fm1Said;
        this.prefs = prefs;
        this.onMenu = onMenu;
        density = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        staff = new StaffView(c);
        addView(staff);
        close = handle("≡");
        close.setOnClickListener(v -> onMenu.run());
        grip = handle("↔");
        grip.setRotation(45); // a diagonal two-headed arrow
        grip.setOnTouchListener(resize);
        swap = handle("\u21c4"); // the switch: the next pair (shown with two or more)
        swap.setOnClickListener(v -> onSwitch.run());
        setOnTouchListener(move);
        size = prefs.getInt("glassSize", Math.round(260 * density));
        wave = prefs.getBoolean("wave", true);
        soundOn = prefs.getBoolean("sound", true);
        setLayoutParams(new FrameLayout.LayoutParams(size, size));
        layoutParts();
        showNothing();
    }

    /**
     * Puts it where it was; the first time, in the middle, most of the screen across.
     */
    void place(int parentW, int parentH) {
        if (!prefs.contains("glassX")) {
            size = Math.round(Math.min(parentW, parentH) * 0.8f);
            resizeTo(size, parentW, parentH);
            moveTo((parentW - size) / 2f, (parentH - size) / 2f);
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
        if (scope != null) scope.close();
        scope = null;
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideHandles);
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
        int w = Math.round(size * (wave ? 0.42f : 0.45f)), h = Math.round(w * 1.06f); // the staff is what's read: as big as the glass allows
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        slp.leftMargin = Math.round(size / 2f + (wave ? -size * 0.165f : 0) - w / 2f); // left of the waveform
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

        int sw = Math.round(size * 0.1f);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(sw, sw, Gravity.TOP | Gravity.START);
        wlp.leftMargin = Math.round(size / 2f + size * 0.30f - sw / 2f);
        wlp.topMargin = Math.round(size / 2f - size * 0.20f - sw / 2f);
        swap.setLayoutParams(wlp);
        swap.setBackground(circle(BRASS, BRASS_SHADOW));
        swap.setTextColor(BRASS_SHADOW);
        swap.setTextSize(TypedValue.COMPLEX_UNIT_PX, sw * 0.6f);
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
        private long downAt;

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
                    downAt = e.getEventTime();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) dragged = true;
                    if (dragged) moveTo(getX() + e.getRawX() - lastX, getY() + e.getRawY() - lastY);
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    // A tap: the MIDI connections (as clicking the glass in MIDI Spyglass on a computer); a long
                    // press: the grip, to resize it.
                    if (!dragged && e.getEventTime() - downAt >= ViewConfiguration.getLongPressTimeout()) toggleHandles();
                    else if (!dragged) onMenu.run();
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

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
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
            long now = System.currentTimeMillis();
            listen(now);
            if (wave) {
                follow(played.held(), now);
                if (!sounding.isEmpty() || waveStart >= 0 || (fm1Plays && hearing() && shownNotes != null)) invalidate();
            }
            // The header: the sound the FM-1 says it's playing ("T1 FM6 PIANO 1"), else "FM-1" while its sound is
            // heard, else a Program Change's sound name.
            String said = fm1Said.get(), s = null;
            if (fm1Plays) s = said != null ? said : hearing() ? "FM-1" : null;
            if (s == null) s = played.sound();
            // With several pairs, the header says whose: "Keystation 49 MK3 · T1 FM6 TINE EP".
            if (pairName != null) s = s == null ? pairName : pairName + " \u00b7 " + s;
            if (!soundOn) s = null;
            if (s == null ? sound != null : !s.equals(sound)) {
                sound = s;
                invalidate();
            }
            ui.postDelayed(this, 50);
        }
    };

    /** Shows a pair: its keyboard's notes (`p`), its name for the header (null: don't say), whether the FM-1 plays
     * it, and the switch with two or more pairs. */
    void showPair(Played p, String name, boolean fm1, boolean many) {
        boolean other = p != played;
        played = p != null ? p : new Played();
        pairName = name;
        fm1Plays = fm1;
        swap.setVisibility(many ? View.VISIBLE : View.GONE);
        if (other) {
            seenNoteOns = -1;
            chordArmed = false;
            sounding.clear();
            soundingUp.clear();
            waveStart = -1;
            showNothing();
        }
        invalidate();
    }

    /** The remote (a TV): OK opens the MIDI connections; ◀ ▶ show the other pairs. */
    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                onMenu.run();
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                onSwitch.run();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    boolean wave() { return wave; }

    void setWave(boolean on) {
        wave = on;
        prefs.edit().putBoolean("wave", on).apply();
        layoutParts();
    }

    /**
     * The notes' sound as a scope would show it, made from MIDI alone: a sine at each note's pitch and level
     * (0-1), added up (a chord's beating shape), over WINDOW from zero going up, like a triggered scope's: it
     * stands still. ±1 is everything at full loudness in step; null when nothing sounds.
     */
    static float[] waveform(float[][] notes) {
        if (notes.length == 0) return null;
        float low = 127, total = 0, loudest = 0;
        for (float[] n : notes) {
            low = Math.min(low, n[0]);
            total += n[1];
            loudest = Math.max(loudest, n[1]);
        }
        if (total <= 0.001f) return null;

        float[] w = new float[WAVE_POINTS];
        for (int i = 0; i < WAVE_POINTS; i++) {
            double t = WINDOW * i / (WAVE_POINTS - 1), s = 0;
            for (float[] n : notes) s += n[1] * Math.sin(2 * Math.PI * NoteNames.hz((int) n[0]) * t);
            w[i] = (float) s / total * loudest; // the chord's shape, as tall as its loudest note
        }
        return w;
    }

    /** Keeps the waveform's notes up with the keys: new ones join at their level, released ones fade out. */
    private void follow(float[][] held, long now) {
        for (int i = 0; i < sounding.size(); i++) {
            float[] v = sounding.get(i);
            float[] h = null;
            for (float[] x : held) if (x[0] == v[0]) h = x;
            if (h != null) {
                v[1] = h[1];
                soundingUp.set(i, -1L);
            } else if (soundingUp.get(i) < 0) soundingUp.set(i, now);
        }
        for (float[] h : held) {
            boolean have = false;
            for (float[] v : sounding) if (v[0] == h[0]) have = true;
            if (!have) {
                sounding.add(new float[] {h[0], h[1]});
                soundingUp.add(-1L);
            }
        }
        for (int i = sounding.size() - 1; i >= 0; i--) {
            long up = soundingUp.get(i);
            if (up >= 0 && now - up >= FADE_MS) {
                sounding.remove(i);
                soundingUp.remove(i);
            }
        }
        waveStart = sounding.isEmpty() ? -1 : waveStart < 0 ? now : waveStart;
    }

    /** The FM-1's sound: open it when it's plugged in and allowed (looked for every 2 s). */
    private void listen(long now) {
        if (wave && (scope == null || scope.isDead()) && now - scopeLookedAt > 2000) {
            scopeLookedAt = now;
            scope = Scope.open(getContext());
        } else if (!wave && scope != null) {
            scope.close();
            scope = null;
        }
    }

    private boolean hearing() {
        return scope != null && scope.listening();
    }

    /** The note a real sound's wave is lined up on: the lowest sounding, else the one showing. */
    private double hz() {
        int low = 128;
        for (float[] v : sounding) low = Math.min(low, (int) v[0]);
        if (low == 128 && shownNotes != null) for (int n : shownNotes) low = Math.min(low, n);
        return low == 128 ? 0 : NoteNames.hz(low);
    }

    /** The waveform: the FM-1's real sound while it's heard (a flat line while it's quiet), else drawn from the notes. */
    private float[] waveNow() {
        if (fm1Plays && hearing()) {
            double hz = hz();
            return hz > 0 ? scope.wave(hz) : null;
        }
        return liveWave();
    }

    /** The waveform drawn from the notes now: each sounding note at its level (fading after release). */
    private float[] liveWave() {
        if (waveStart < 0) return null;
        long now = System.currentTimeMillis();
        float[][] notes = new float[sounding.size()][];
        for (int i = 0; i < notes.length; i++) {
            long up = soundingUp.get(i);
            float fade = up < 0 ? 1 : Math.max(0, 1 - (now - up) / (float) FADE_MS);
            notes[i] = new float[] {sounding.get(i)[0], sounding.get(i)[1] * fade};
        }
        return waveform(notes);
    }

    boolean soundOn() { return soundOn; }

    void setSoundOn(boolean on) {
        soundOn = on;
        prefs.edit().putBoolean("sound", on).apply();
        invalidate();
    }

    /** The message over the middle when there's nothing to listen to (null: none). */
    private String alertTitle, alertLine;

    void setAlert(String title, String line) {
        alertTitle = title;
        alertLine = line;
        invalidate();
    }

    void setIdle(String s) {
        if (s.equals(idle)) return;
        idle = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
        lens(c, cx, cy, d);
        if (sound != null && alertTitle == null) soundBand(c, sound, cx, cy - d * 0.325f, d);
        String name, detail;
        if (shownNotes == null) {
            name = "♪";
            detail = idle;
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
            detail = String.format(Locale.US, "%s   %.1f Hz", NoteNames.name(n), NoteNames.hz(n));
            if (shownVel > 0) detail += "   vel " + shownVel;
        }
        if (shownNotes == null) { // nothing played: the empty staves, and the hint where a note's details go
            label(c, detail, cx, cy + d * 0.365f, Math.max(12 * density, d * 0.055f), d * 0.54f, Color.WHITE);
        } else {
            label(c, name, cx, cy - d * 0.215f, shownChord != null ? d * 0.10f : d * 0.115f, d * 0.72f, Color.WHITE);
            label(c, detail, cx, cy + d * 0.365f, Math.max(12 * density, d * 0.055f), d * 0.54f, Color.WHITE);
        }
        if (wave) {
            // The waveform, right of the staff, only while one is playing.
            r.set(cx + d * 0.075f, cy - d * 0.02f, cx + d * 0.355f, cy + d * 0.19f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2 * density, d * 0.006f));
            p.setStrokeJoin(Paint.Join.ROUND);
            float[] w = waveNow();
            waveLine.reset();
            if (w != null) {
                p.setColor(WAVE);
                for (int i = 0; i < w.length; i++) {
                    float x = r.left + r.width() * i / (w.length - 1), y = r.centerY() - w[i] * r.height() / 2;
                    if (i == 0) waveLine.moveTo(x, y);
                    else waveLine.lineTo(x, y);
                }
            } // (nothing playing: no line at all)
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
        if (alertTitle != null) {
            // A dark rounded card with a thin brass edge over the middle: the title in brass, what to do under it.
            float w = d * 0.7f, h = d * 0.24f, top = cy + d * 0.06f - h / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(235, 8, 10, 13));
            r.set(cx - w / 2, top, cx + w / 2, top + h);
            c.drawRoundRect(r, d * 0.04f, d * 0.04f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.25f * density);
            p.setColor(BRASS);
            c.drawRoundRect(r, d * 0.04f, d * 0.04f, p);
            label(c, alertTitle, cx, top + h * 0.36f, d * 0.075f, d * 0.62f, BRASS_LIGHT);
            label(c, alertLine, cx, top + h * 0.72f, Math.max(11 * density, d * 0.045f), d * 0.62f, Color.WHITE);
        }
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
