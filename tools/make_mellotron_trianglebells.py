# make_mellotron_trianglebells.py: Triangle Bells (FM), the Triangle frequency-modulated by the Tibetan Bells.
# The carrier is the triangle (make_mellotron_triangle.py: its bar modes, beating pairs and shimmer); the modulator is
# the same key's Tibetan Bells strip (make_mellotron_tibetanbells.py), added to every triangle partial's phase. The
# modulation index starts high and dies away (3.2 -> 0.4), as in an FM bell: bright, clangorous sidebands at the strike
# that mellow back toward the triangle as it rings. 35 strips, G2 (MIDI 43) to F5 (77), 8 s each, dying away before
# the strip runs out.
#   python tools/make_mellotron_trianglebells.py
# Written as 16-bit mono WAV at 44100 Hz to samples/trianglebells/043.wav ... 077.wav.
import os, sys, numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(__file__))
import make_mellotron_triangle as tri
import make_mellotron_tibetanbells as bells

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "trianglebells")

def strip(midi):
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    mod = bells.strip(midi)
    mod /= np.abs(mod).max() + 1e-9
    index = 0.4 + 2.8 * np.exp(-t / 1.2)                         # the FM index: bright at the strike, then mellow
    phase_mod = index * mod
    r = np.random.default_rng(12000 + midi)                        # the same take every build
    f0 = 440.0 * 2 ** ((midi + 24 - 69) / 12)
    p1, p2 = r.uniform(0, 6.28, 2)
    wow = 0.0008 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0003 * np.sin(2 * np.pi * 7.5 * t + p2)
    warp = np.cumsum(1 + wow) / RATE
    y = np.zeros(n)
    for k, ratio in enumerate(tri.RATIOS):
        for d in (0, 1):
            f = f0 * ratio * (1 + r.uniform(-0.004, 0.004)) + d * r.uniform(1.5, 4.5)
            if f > 17000:                                          # (room above for the sidebands)
                continue
            amp = (0.9 ** k) * (1.0 if d == 0 else 0.6) * (1.0 if k < 6 else 0.5)
            tau = 3.4 / (1 + 0.35 * k) ** 1.3 * (1175 / f0) ** 0.3
            y += amp * np.exp(-t / tau) * np.sin(2 * np.pi * f * warp + phase_mod * (1 + 0.15 * k) + r.uniform(0, 6.28))
    c = int(0.004 * RATE)                                          # the beater's tick
    tick = r.standard_normal(c) * np.exp(-np.arange(c) / (0.0008 * RATE))
    y[:c] += 0.6 * np.convolve(tick, [1, -1], "same")
    y *= np.minimum(1, t / 0.0007)
    y *= np.where(t < 4.5, 1.0, 0.5 + 0.5 * np.cos(np.pi * np.clip((t - 4.5) / 3.5, 0, 1)))
    return y

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    strips = {m: strip(m) for m in range(43, 78)}
    peak = max(np.abs(s).max() for s in strips.values())
    for m, s in strips.items():
        sf.write(os.path.join(OUT, f"{m:03d}.wav"), (s / peak * 0.8).astype(np.float32), RATE, subtype="PCM_16")
    print("35 strips ->", os.path.abspath(OUT))
