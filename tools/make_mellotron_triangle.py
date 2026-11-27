# make_mellotron_triangle.py: the Triangle sound, a struck orchestral triangle, made from scratch (no recordings), one
# strip per key over the Mellotron's 35 keys, G2 (MIDI 43) to F5 (77), 8 seconds each, like the other sounds.
# A triangle is a steel bar bent in three: its modes are a free bar's (1, 2.756, 5.404, 8.933 ... times the lowest),
# each split by the bends into two close partials that beat; the highs die quickly, the lowest rings on. On top, a
# shimmer of dense high partials and the beater's tick. Keys are pitched two octaves up (D4 rings at D6, 1175 Hz), so
# low keys sound like a bigger, darker triangle and high keys a small bright one. A light Mellotron tape (wow, flutter,
# dying away before the strip runs out at 8 s); the top is left open, since a triangle is all sparkle.
#   python tools/make_mellotron_triangle.py
# Written as 16-bit mono WAV at 44100 Hz to samples/triangle/043.wav ... 077.wav.
import os, numpy as np, soundfile as sf

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "triangle")
RATIOS = [1, 2.756, 5.404, 8.933, 13.34, 18.64, 24.81, 31.87, 39.8, 48.6]

def strip(midi):
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    r = np.random.default_rng(7000 + midi)                         # the same take every build
    f0 = 440.0 * 2 ** ((midi + 24 - 69) / 12)
    p1, p2 = r.uniform(0, 6.28, 2)
    wow = 0.0008 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0003 * np.sin(2 * np.pi * 7.5 * t + p2)
    warp = np.cumsum(1 + wow) / RATE                               # tape time: the pitch wavers a little
    y = np.zeros(n)
    for k, ratio in enumerate(RATIOS):
        for d in (0, 1):
            f = f0 * ratio * (1 + r.uniform(-0.004, 0.004)) + d * r.uniform(1.5, 4.5)
            if f > 19000:
                continue
            amp = (0.9 ** k) * (1.0 if d == 0 else 0.6) * (1.0 if k < 6 else 0.5)
            tau = 3.4 / (1 + 0.35 * k) ** 1.3 * (1175 / f0) ** 0.3  # big triangles ring longer
            y += amp * np.exp(-t / tau) * np.sin(2 * np.pi * f * warp + r.uniform(0, 6.28))
    for _ in range(40):                                            # the shimmer
        f = r.uniform(max(3000, 2.5 * f0), 15000)
        y += 0.05 * np.exp(-t / r.uniform(0.4, 2.5)) * np.sin(2 * np.pi * f * warp + r.uniform(0, 6.28))
    c = int(0.004 * RATE)                                          # the beater's tick
    tick = r.standard_normal(c) * np.exp(-np.arange(c) / (0.0008 * RATE))
    y[:c] += 0.6 * np.convolve(tick, [1, -1], "same")
    y *= np.minimum(1, t / 0.0007)
    # the strip runs out at 8 s: the ring must be gone by then, so it dies away over the last 3.5 s (no cut)
    y *= np.where(t < 4.5, 1.0, 0.5 + 0.5 * np.cos(np.pi * np.clip((t - 4.5) / 3.5, 0, 1)))
    return y

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    strips = {m: strip(m) for m in range(43, 78)}
    peak = max(np.abs(s).max() for s in strips.values())
    for m, s in strips.items():                                    # one gain for all: the keys keep their balance
        sf.write(os.path.join(OUT, f"{m:03d}.wav"), (s / peak * 0.8).astype(np.float32), RATE, subtype="PCM_16")
    print("35 strips ->", os.path.abspath(OUT))
