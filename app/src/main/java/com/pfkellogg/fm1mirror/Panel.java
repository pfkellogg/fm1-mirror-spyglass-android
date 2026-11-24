package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Big Knobs and Key Lights (the same as the Mac app's src/knobs.rs), for players who can't see the FM-1's knobs and
 * lights well: fingers cover the lit keys.
 * <ul>
 * <li>Big Screen builds from aikellogg.com: to the FM-1 {@code F0 7D 'F' 'S' 04 e n F7} turns encoder e (0 SELECT,
 *     1 ALGORITHM, 2..5 KNOB 1..4, 6 PRESETS) n clicks (7-bit signed), {@code 06 lo hi} sets MASTER (0..1023) until
 *     its real knob moves, {@code 07 id down} presses or lets go of button id (0..13) or key 14 + k; from the FM-1
 *     {@code 05 k0..k3 b0 b1 [g0..g3 h0 h1]}: the lit keys and buttons, then the glowing ones.</li>
 * <li>Charles Vestal's fm1-base firmwares with their mirror on: his own input reports and remote (Fm1Base).</li>
 * </ul>
 * The view: the buttons and 27 keys (F3 .. G5) above, lit amber, glowing soft amber, played blue; touch them and the
 * real ones are pressed (holds and several fingers too); the eight knobs below, dragged to turn.
 */
final class Panel {
    private Panel() {}

    static final String[] NAMES = {"SELECT", "ALGORITHM", "KNOB 1", "KNOB 2", "KNOB 3", "KNOB 4", "PRESETS", "MASTER"};
    static final String[] BUTTONS = {"OCT-", "OCT+", "FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC"};
    private static final int[] BUTTON_ROW = {0, 1, 8, 6, 3, 4, 5, 2, 7, 10, 11, 9, 13, 12};

    /** The lights: lit and glowing, keys (27 bits) and buttons (14 bits). */
    static final class Lights {
        final int keys, btns, glowKeys, glowBtns;

        Lights(int keys, int btns, int glowKeys, int glowBtns) {
            this.keys = keys;
            this.btns = btns;
            this.glowKeys = glowKeys;
            this.glowBtns = glowBtns;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Lights)) return false;
            Lights l = (Lights) o;
            return keys == l.keys && btns == l.btns && glowKeys == l.glowKeys && glowBtns == l.glowBtns;
        }

        @Override
        public int hashCode() {
            return keys * 31 + btns * 17 + glowKeys * 7 + glowBtns;
        }
    }

    static byte[] turn(int e, int clicks) {
        int n = Math.max(-63, Math.min(63, clicks));
        return new byte[] {(byte) 0xF0, 0x7D, 'F', 'S', 4, (byte) (e & 7), (byte) (n & 127), (byte) 0xF7};
    }

    static byte[] master(int v) {
        v = Math.max(0, Math.min(1023, v));
        return new byte[] {(byte) 0xF0, 0x7D, 'F', 'S', 6, (byte) (v & 127), (byte) (v >> 7), (byte) 0xF7};
    }

    static byte[] press(int id, boolean down) {
        return new byte[] {(byte) 0xF0, 0x7D, 'F', 'S', 7, (byte) Math.min(40, id), (byte) (down ? 1 : 0), (byte) 0xF7};
    }

    /** Key Lights from a Big Screen build, or null. */
    static Lights lights(byte[] m) {
        int n = m.length;
        if ((n != 12 && n != 18) || m[0] != (byte) 0xF0 || m[1] != 0x7D || m[2] != 'F' || m[3] != 'S' || m[4] != 5
                || m[n - 1] != (byte) 0xF7) return null;
        int k = bits27(m, 5), b = bits14(m, 9);
        return n == 18 ? new Lights(k, b, bits27(m, 11), bits14(m, 15)) : new Lights(k, b, 0, 0);
    }

    private static int bits27(byte[] m, int at) {
        return ((m[at] & 127) | (m[at + 1] & 127) << 7 | (m[at + 2] & 127) << 14 | (m[at + 3] & 127) << 21) & ((1 << 27) - 1);
    }

    private static int bits14(byte[] m, int at) {
        return ((m[at] & 127) | (m[at + 1] & 127) << 7) & 0x3FFF;
    }

    /** The 27 keys the notes being played fall on, folded in by octaves (F3 = 53 .. G5 = 79). */
    static int playedKeys(int[] notes) {
        int m = 0;
        for (int n : notes) {
            while (n < 53) n += 12;
            while (n > 79) n -= 12;
            m |= 1 << (n - 53);
        }
        return m;
    }

    /** Where the view's touches go: a message for the FM-1 (Big Screen build's own; MainActivity turns them into Charles's). */
    interface Out {
        void send(byte[] m);
    }

    static class PanelView extends View {
        boolean showLights = true, showKnobs = true;
        /** Knobs over the FM-1's screen's empty sides (landscape: 4 down each side; portrait: a row below it), never
         *  taking room from the screen; touches elsewhere go through to the screen. */
        boolean overlay;
        private Lights lights;
        private int played;
        private final Out out;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float dp;
        // touches: pointer id -> what it holds ("b" + id / "k" + id: a button or key; "n" + i: a knob, with its last y)
        private final SparseArray<int[]> touch = new SparseArray<>(); // {kind 0 press / 1 knob, id, lastX, lastY}
        private final float[] left = new float[8], angle = new float[8];
        private float masterV = 512;
        private long masterAt;
        private int held; // keys and buttons held (bit id: 0..13 buttons, 14..40 keys)
        private long heldHi;

        // A TV remote (no touch): a knob or a button / key chosen with the arrows (a blue ring), left / right turn a knob,
        // OK presses (held: held)
        private int knobSel, rowSel /* 0 buttons, 1 keys */, btnSel, keySel = 7 /* C4 */, okHeld = -1;

        PanelView(Context c, Out out) {
            super(c);
            this.out = out;
            dp = c.getResources().getDisplayMetrics().density;
            setFocusable(true);
            setOnFocusChangeListener((v, has) -> invalidate());
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (overlay) {
                if (!knobsFit() || !showKnobs) return super.onKeyDown(code, e);
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: knob(knobSel, -12f); invalidate(); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: knob(knobSel, 12f); invalidate(); return true;
                    case KeyEvent.KEYCODE_DPAD_UP: if (knobSel == 0) return false; knobSel--; invalidate(); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: if (knobSel == 7) return false; knobSel++; invalidate(); return true;
                    default: return super.onKeyDown(code, e);
                }
            }
            if (!showLights) return super.onKeyDown(code, e);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (rowSel == 0) btnSel = Math.max(0, btnSel - 1); else keySel = Math.max(0, keySel - 1);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (rowSel == 0) btnSel = Math.min(13, btnSel + 1); else keySel = Math.min(26, keySel + 1);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (rowSel == 0) return false;
                    rowSel = 0;
                    btnSel = Math.round(keyRect(keySel).centerX() / (getWidth() / 14f) - 0.5f);
                    btnSel = Math.max(0, Math.min(13, btnSel));
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (rowSel == 1) return false;
                    rowSel = 1;
                    float x = buttonRect(btnSel).centerX();
                    int best = 0;
                    for (int k = 1; k < 27; k++)
                        if (Math.abs(keyRect(k).centerX() - x) < Math.abs(keyRect(best).centerX() - x)) best = k;
                    keySel = best;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) {
                        okHeld = rowSel == 0 ? BUTTON_ROW[btnSel] : 14 + keySel;
                        setHeld(okHeld, true);
                        invalidate();
                    }
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if (okHeld >= 0 && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                setHeld(okHeld, false);
                okHeld = -1;
                invalidate();
                return true;
            }
            return super.onKeyUp(code, e);
        }

        /** The knobs over the screen's sides, see-through, or the strip under it. */
        void asOverlay() {
            overlay = true;
            showLights = false;
            setBackgroundColor(0);
        }

        void asStrip() {
            showKnobs = false;
            setBackgroundColor(0xff121212);
        }

        boolean hasLights() {
            return lights != null;
        }

        void setLights(Lights l) {
            lights = l;
            postInvalidateOnAnimation();
        }

        void setPlayed(int p) {
            if (p != played) {
                played = p;
                postInvalidateOnAnimation();
            }
        }

        /** The height it wants: the strip and the knobs, each when shown. */
        int wanted() {
            return (int) ((showLights ? 92 : 0) * dp + (!overlay && showKnobs ? 110 : 0) * dp);
        }

        private float lightsH() {
            return showLights ? 92 * dp : 0;
        }

        // ---- layout
        private RectF buttonRect(int j) {
            float w = getWidth() / 14f, h = 24 * dp;
            return new RectF(w * j + 2 * dp, 3 * dp, w * (j + 1) - 2 * dp, 3 * dp + h);
        }

        private float keysTop() {
            return 30 * dp;
        }

        private float keysH() {
            return lightsH() - keysTop() - 4 * dp;
        }

        private final int[] whiteOf = new int[27];
        private final boolean[] isBlack = new boolean[27];
        {
            int w = 0;
            for (int k = 0; k < 27; k++) {
                int pc = (5 + k) % 12;
                isBlack[k] = pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
                whiteOf[k] = w;
                if (!isBlack[k]) w++;
            }
        }

        private RectF keyRect(int k) {
            float ww = getWidth() / 16f, top = keysTop(), h = keysH();
            if (isBlack[k]) return new RectF(ww * whiteOf[k] - ww * 0.32f, top, ww * whiteOf[k] + ww * 0.32f, top + h * 0.6f);
            return new RectF(ww * whiteOf[k] + 1.5f * dp, top, ww * (whiteOf[k] + 1) - 1.5f * dp, top + h);
        }

        private float knobCell() {
            if (overlay) {
                float w = getWidth(), h = getHeight();
                if (w > h) return Math.min((w - h) / 2f, Math.min(h / 4f, 130 * dp));      // a side column
                return Math.min(w / 8f, Math.min((h - w) / 2f, 110 * dp));                 // a row below
            }
            return Math.min(getWidth() / 8f, 110 * dp);
        }

        private float[] knobCentre(int i) {
            float cell = knobCell();
            if (overlay) {
                float w = getWidth(), h = getHeight();
                if (w > h) {                               // 4 down the left, 4 down the right of the square screen
                    float side = (w - h) / 2f, y0 = h / 2f - cell * 2;
                    float x = i < 4 ? side / 2f : w - side / 2f;
                    return new float[] {x, y0 + cell * (i % 4 + 0.45f)};
                }
                float x0 = w / 2f - cell * 4, y = h - (h - w) / 4f;
                return new float[] {x0 + cell * (i + 0.5f), y};
            }
            float x0 = getWidth() / 2f - cell * 4;
            return new float[] {x0 + cell * (i + 0.5f), lightsH() + 44 * dp};
        }

        /** Room beside (or below) the screen for knobs a finger can turn. */
        boolean knobsFit() {
            return knobCell() >= 56 * dp;
        }

        /** What's under (x, y): {0, id} a button or key, {1, i} a knob, or null. */
        private int[] at(float x, float y) {
            if (showLights && y < lightsH()) {
                for (int j = 0; j < 14; j++)
                    if (buttonRect(j).contains(x, y)) return new int[] {0, BUTTON_ROW[j]};
                for (int pass = 0; pass < 2; pass++) // black keys first
                    for (int k = 0; k < 27; k++)
                        if (isBlack[k] == (pass == 0) && keyRect(k).contains(x, y)) return new int[] {0, 14 + k};
                return null;
            }
            if (showKnobs && (overlay || y >= lightsH())) {
                float rr = knobCell() * 0.5f;
                for (int i = 0; i < 8; i++) {
                    float[] ce = knobCentre(i);
                    if (Math.hypot(x - ce[0], y - ce[1]) <= rr) return new int[] {1, i};
                }
            }
            return null;
        }

        private boolean isHeld(int id) {
            return id < 32 ? (held >> id & 1) == 1 : (heldHi >> (id - 32) & 1) == 1;
        }

        private void setHeld(int id, boolean on) {
            if (isHeld(id) == on) return;
            if (id < 32) held = on ? held | 1 << id : held & ~(1 << id);
            else heldHi = on ? heldHi | 1L << (id - 32) : heldHi & ~(1L << (id - 32));
            out.send(press(id, on));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex(), pid = e.getPointerId(i);
                int[] w = at(e.getX(i), e.getY(i));
                if (w == null && overlay && touch.size() == 0 && a == MotionEvent.ACTION_DOWN)
                    return false;                          // not a knob: the screen's (a tap for full screen)
                if (w != null) {
                    touch.put(pid, new int[] {w[0], w[1], (int) e.getX(i), (int) e.getY(i)});
                    if (w[0] == 0) setHeld(w[1], true);
                }
            } else if (a == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int[] t = touch.get(e.getPointerId(i));
                    if (t == null || t[0] != 1) continue;
                    float d = (e.getX(i) - t[2]) - (e.getY(i) - t[3]);
                    t[2] = (int) e.getX(i);
                    t[3] = (int) e.getY(i);
                    knob(t[1], d / dp);
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL) {
                int from = a == MotionEvent.ACTION_CANCEL ? 0 : e.getActionIndex();
                int to = a == MotionEvent.ACTION_CANCEL ? e.getPointerCount() : from + 1;
                for (int i = from; i < to; i++) {
                    int pid = e.getPointerId(i);
                    int[] t = touch.get(pid);
                    if (t != null && t[0] == 0) setHeld(t[1], false);
                    touch.remove(pid);
                }
            }
            invalidate();
            return true;
        }

        /** Knob i dragged d points (up or right: clockwise). */
        private void knob(int i, float d) {
            if (i == 7) {
                masterV = Math.max(0, Math.min(1023, masterV + d * 2.5f));
                long now = System.currentTimeMillis();
                if (now - masterAt >= 30) {
                    masterAt = now;
                    out.send(master(Math.round(masterV)));
                }
                angle[7] = (masterV / 1023f - 0.5f) * 270f;
                return;
            }
            left[i] += d;
            int clicks = (int) (left[i] / 12f);
            if (clicks != 0) {
                left[i] -= clicks * 12f;
                out.send(turn(i, clicks));
                angle[i] += clicks * 18f;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            Lights l = lights != null ? lights : new Lights(0, 0, 0, 0);
            if (showLights) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(12 * dp);
                for (int j = 0; j < 14; j++) {
                    int id = BUTTON_ROW[j];
                    boolean on = (l.btns >> id & 1) == 1 || isHeld(id), dim = (l.glowBtns >> id & 1) == 1;
                    RectF b = buttonRect(j);
                    paint.setColor(on ? 0xffffd678 : dim ? 0xff69552d : 0xff2d2d2d);
                    c.drawRoundRect(b, 6 * dp, 6 * dp, paint);
                    paint.setColor(on ? 0xff000000 : 0xffaaaaaa);
                    c.drawText(BUTTONS[id], b.centerX(), b.centerY() + 4 * dp, paint);
                }
                for (int pass = 0; pass < 2; pass++) // white keys, then black over them
                    for (int k = 0; k < 27; k++) {
                        if (isBlack[k] != (pass == 1)) continue;
                        boolean on = (l.keys >> k & 1) == 1 || isHeld(14 + k), play = (played >> k & 1) == 1,
                                dim = (l.glowKeys >> k & 1) == 1;
                        int col = isBlack[k] ? (on ? 0xffffaa28 : play ? 0xff3c82f0 : dim ? 0xff78551e : 0xff191919)
                                : (on ? 0xffffd678 : play ? 0xff6eafff : dim ? 0xfff5deaa : 0xffc8c8c8);
                        paint.setColor(col);
                        c.drawRoundRect(keyRect(k), 4 * dp, 4 * dp, paint);
                    }
                if (isFocused()) {                         // the remote's choice
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(3 * dp);
                    paint.setColor(0xff4da3ff);
                    c.drawRoundRect(rowSel == 0 ? buttonRect(btnSel) : keyRect(keySel), 6 * dp, 6 * dp, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
            if (showKnobs && (!overlay || knobsFit())) {
                float rr = Math.max(14 * dp, knobCell() * (overlay ? 0.32f : 0.3f));
                paint.setTextAlign(Paint.Align.CENTER);
                for (int i = 0; i < 8; i++) {
                    float[] ce = knobCentre(i);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xff343434);
                    c.drawCircle(ce[0], ce[1], rr, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * dp);
                    paint.setColor(0xff6e6e6e);
                    c.drawCircle(ce[0], ce[1], rr, paint);
                    double t = Math.toRadians(angle[i] - 90);
                    paint.setStrokeWidth(Math.max(3 * dp, rr * 0.12f));
                    paint.setColor(0xffffc85a);
                    c.drawLine(ce[0] + (float) Math.cos(t) * rr * 0.25f, ce[1] + (float) Math.sin(t) * rr * 0.25f,
                            ce[0] + (float) Math.cos(t) * rr * 0.9f, ce[1] + (float) Math.sin(t) * rr * 0.9f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xffc8c8c8);
                    paint.setTextSize(11 * dp);
                    c.drawText(NAMES[i], ce[0], ce[1] + rr + 13 * dp, paint);
                    if (isFocused() && i == knobSel) {     // the remote's knob
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(4 * dp);
                        paint.setColor(0xff4da3ff);
                        c.drawCircle(ce[0], ce[1], rr + 6 * dp, paint);
                        paint.setStyle(Paint.Style.FILL);
                    }
                }
            }
        }
    }
}
