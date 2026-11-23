package com.pfkellogg.fm1mirror;

/**
 * Sweeten: what a good stereo does to a sound, for the Mellotron: a little warmth (+2.5 dB under 200 Hz) and air
 * (+2 dB over 6 kHz), soft tape saturation, a slow stereo chorus for width, and a small room. The same recipe as the
 * desktop app's (src/sweeten.rs) and the Flute Lab's (aikellogg.com). Mono in, stereo out; on and off crossfade.
 * Lean on purpose (floats, no allocation, no modulo per sample): it runs in the Mellotron's audio loop.
 */
final class Sweeten {
    /** On or off (remembered by the app). */
    static volatile boolean on;

    private final float rate;
    // the two shelves (RBJ), direct form II transposed
    private final float[] lb = new float[3], la = new float[2], ab = new float[3], aa = new float[2];
    private float lz0, lz1, az0, az1;
    private final float[] delay;
    private int w;
    private final float[] phase = {0f, 0.5f};
    private final float[][] comb = new float[8][], all = new float[4][];
    private final int[] ci = new int[8], ai = new int[4];
    private final float[] store = new float[8];
    private float mix;

    private static final float SAT = 1.4f, SAT_NORM = (float) Math.tanh(SAT);
    private static final float[][] CHORUS = {{0.012f, 0.0015f, 0.33f}, {0.015f, 0.0018f, 0.27f}};
    private static final float CHORUS_LEVEL = 0.38f, BODY = 0.82f, ROOM_LEVEL = 0.05f, WET = 0.9f, FEEDBACK = 0.74f, DAMP = 0.42f;

    Sweeten(float rate) {
        this.rate = rate;
        shelf(rate, 200, 2.5f, false, lb, la);
        shelf(rate, 6000, 2f, true, ab, aa);
        delay = new float[(int) (rate * 0.05f)];
        float s = rate / 44100f;
        int[] combs = {1116, 1188, 1277, 1356}, alls = {556, 441};
        for (int side = 0; side < 2; side++) {
            int spread = side * 23;
            for (int k = 0; k < 4; k++) comb[side * 4 + k] = new float[Math.max(1, (int) ((combs[k] + spread) * s))];
            for (int k = 0; k < 2; k++) all[side * 2 + k] = new float[Math.max(1, (int) ((alls[k] + spread) * s))];
        }
        mix = on ? 1f : 0f;
    }

    private static void shelf(float rate, float hz, float db, boolean high, float[] b, float[] a) {
        double A = Math.pow(10, db / 40.0), w = 2 * Math.PI * hz / rate, cw = Math.cos(w), sw = Math.sin(w);
        double alpha = sw / 2 * Math.sqrt(2), s = 2 * Math.sqrt(A) * alpha;
        double b0, b1, b2, a0, a1, a2;
        if (high) {
            b0 = A * ((A + 1) + (A - 1) * cw + s); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - s);
            a0 = (A + 1) - (A - 1) * cw + s; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - s;
        } else {
            b0 = A * ((A + 1) - (A - 1) * cw + s); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - s);
            a0 = (A + 1) + (A - 1) * cw + s; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - s;
        }
        b[0] = (float) (b0 / a0); b[1] = (float) (b1 / a0); b[2] = (float) (b2 / a0);
        a[0] = (float) (a1 / a0); a[1] = (float) (a2 / a0);
    }

    /** tanh, close enough for saturation, without Math.tanh's cost. */
    private static float tanh(float x) {
        if (x > 3f) return 1f;
        if (x < -3f) return -1f;
        float x2 = x * x;
        return x * (27 + x2) / (27 + 9 * x2);
    }

    /** sin(2 pi x) for x in [0, 1), a parabola (enough for a slow chorus), without Math.sin's cost. */
    private static float sin1(float x) {
        float y = x < 0.5f ? x * 4 - 1 : 3 - x * 4;          // a triangle, -1..1
        return y * (1.5f - 0.5f * y * y) * -1f;              // rounded into a sine's shape (phase doesn't matter)
    }

    private float tap(float seconds) {
        int n = delay.length;
        float d = seconds * rate;
        int i = (int) d;
        float f = d - i;
        int p0 = w - i, p1 = p0 - 1;
        if (p0 < 0) p0 += n;
        if (p1 < 0) p1 += n;
        return delay[p0] * (1 - f) + delay[p1] * f;
    }

    /** A block: mono in, left and right out (interleaved, 2 x n). */
    void run(float[] in, float[] lr, int n) {
        float target = on ? 1f : 0f, step = 1f / (0.03f * rate);
        if (mix < 1e-4f && target == 0f) {
            for (int k = 0; k < n; k++) lr[2 * k] = lr[2 * k + 1] = in[k];   // off: straight through
            mix = 0f;
            return;
        }
        for (int k = 0; k < n; k++) {
            float x = in[k];
            mix += (target - mix) * step;
            // tone
            float y = lb[0] * x + lz0;
            lz0 = lb[1] * x - la[0] * y + lz1;
            lz1 = lb[2] * x - la[1] * y;
            float t = ab[0] * y + az0;
            az0 = ab[1] * y - aa[0] * t + az1;
            az1 = ab[2] * y - aa[1] * t;
            float s = tanh(SAT * t) / SAT_NORM;
            if (++w == delay.length) w = 0;
            delay[w] = s;
            for (int side = 0; side < 2; side++) {
                float[] c = CHORUS[side];
                float ph = phase[side] + c[2] / rate;
                if (ph >= 1f) ph -= 1f;
                phase[side] = ph;
                float chorus = tap(c[0] + c[1] * sin1(ph));
                float room = 0f;
                for (int j = side * 4; j < side * 4 + 4; j++) {
                    float[] b = comb[j];
                    float o = b[ci[j]];
                    store[j] = o * (1 - DAMP) + store[j] * DAMP;
                    b[ci[j]] = s * 0.5f + store[j] * FEEDBACK;
                    if (++ci[j] == b.length) ci[j] = 0;
                    room += o;
                }
                for (int j = side * 2; j < side * 2 + 2; j++) {
                    float[] b = all[j];
                    float o = b[ai[j]];
                    b[ai[j]] = room + o * 0.5f;
                    if (++ai[j] == b.length) ai[j] = 0;
                    room = o - room;
                }
                float wet = (BODY * s + CHORUS_LEVEL * chorus + ROOM_LEVEL * room) * WET;
                lr[2 * k + side] = x * (1 - mix) + wet * mix;
            }
        }
    }
}
