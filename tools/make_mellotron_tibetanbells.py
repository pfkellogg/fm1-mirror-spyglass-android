# make_mellotron_tibetanbells.py: the Tibetan Bells sound (tingsha: two small bell-bronze cymbals struck together),
# made from scratch (no recordings), one strip per key over the Mellotron's 35 keys, G2 (MIDI 43) to F5 (77), 8 s each.
# Each cymbal rings in inharmonic modes (1, 1.56, 2.31, 2.94 ... times the lowest), each mode a close pair; the two
# cymbals are a few tenths of a percent apart, so the ring wobbles slowly ("wah-wah"), the tingsha's sound. A bright
# clash at the start, the highs dying first. D4 rings at 2050 Hz (the usual tingsha), the other keys move from there.
# A light Mellotron tape (wow, flutter, dying away before the strip runs out at 8 s).
#   python tools/make_mellotron_tibetanbells.py
# Written as 16-bit mono WAV at 44100 Hz to samples/tibetanbells/043.wav ... 077.wav.
import os, numpy as np, soundfile as sf

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "tibetanbells")
RATIOS = [1, 1.56, 2.31, 2.94, 3.63, 4.48, 5.71, 6.9]

def strip(midi):
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    r = np.random.default_rng(9100 + midi)                         # the same take every build
    f0 = 2050.0 * 2 ** ((midi - 62) / 12)
    p1, p2 = r.uniform(0, 6.28, 2)
    wow = 0.0008 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0003 * np.sin(2 * np.pi * 7.5 * t + p2)
    warp = np.cumsum(1 + wow) / RATE
    y = np.zeros(n)
    for cym, detune in ((0, 1.0), (1, 1.0 + r.uniform(0.0025, 0.004))):
        for k, ratio in enumerate(RATIOS):
            for d in (0, 1):
                f = f0 * ratio * detune * (1 + r.uniform(-0.003, 0.003)) + d * r.uniform(0.4, 1.2)
                if f > 19000:
                    continue
                amp = (0.78 ** k) * (1.0 if d == 0 else 0.7) * (1.0 if cym == 0 else 0.85)
                tau = 3.6 / (1 + 0.6 * k) ** 1.4 * (2050 / f0) ** 0.3   # bigger bells ring longer
                y += amp * np.exp(-t / tau) * np.sin(2 * np.pi * f * warp + r.uniform(0, 6.28))
    for _ in range(30):                                            # the clash's shimmer
        f = r.uniform(max(6000, 3 * f0), 16000)
        y += 0.06 * np.exp(-t / r.uniform(0.05, 0.6)) * np.sin(2 * np.pi * f * warp + r.uniform(0, 6.28))
    c = int(0.006 * RATE)                                          # metal on metal
    clash = r.standard_normal(c) * np.exp(-np.arange(c) / (0.0012 * RATE))
    y[:c] += 0.9 * np.convolve(clash, [1, -1], "same")
    y *= np.minimum(1, t / 0.0005)
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
