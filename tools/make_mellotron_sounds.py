# make_mellotron_sounds.py: the built-in Mellotron's "strings" and "choir" strips, made from scratch (no recordings, so
# no rights questions), like the flute (make_mellotron_flute.py). One strip per key over a Mellotron's 35 keys, G2 (MIDI
# 43) to F5 (77), 8 seconds each, running out at the end the way a tape strip does.
#   strings: a small violin section, three players a little out of tune with each other, each with a vibrato of their
#            own; bowed tone (all harmonics, falling off), through a violin body's resonances; a slow bow attack.
#   choir:   a few voices singing "aah": harmonics shaped by the vowel's formants, every singer slightly out of tune,
#            each with their own vibrato, a little breath; a slow swell.
# Both get the tape machine's wow and flutter, and its dullness (a soft low-pass).
# Written as 16-bit mono WAV, 44.1 kHz, to samples/SET/043.wav ... 077.wav; the app has them as FLAC:
#   for s in strings choir; do for f in samples/$s/*.wav; do
#     ffmpeg -i $f -c:a flac app/src/main/assets/mellotron/$s/$(basename $f .wav).flac; done; done
import os, sys, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
N = int(RATE * SECONDS)
t = np.arange(N) / RATE
OUT = os.path.join(os.path.dirname(__file__), "..", "samples")
rng = np.random.default_rng(11)


def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)


def bandpass(x, lo, hi):
    b, a = butter(2, [lo / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)


def tape():
    """The tape machine: slow wow and quick flutter, a little different on every strip."""
    return (0.0020 * np.sin(2 * np.pi * 0.6 * t + rng.uniform(0, 6.28))
            + 0.0006 * np.sin(2 * np.pi * 7.1 * t + rng.uniform(0, 6.28)))


def vibrato(rate, depth, delay):
    """A player's vibrato: fades in after `delay` seconds, its speed drifting a little."""
    drift = 1 + 0.06 * np.sin(2 * np.pi * 0.23 * t + rng.uniform(0, 6.28))
    phase = 2 * np.pi * np.cumsum(rate * drift) / RATE + rng.uniform(0, 6.28)
    return depth * np.clip((t - delay) / 0.8, 0, 1) * np.sin(phase)


def resonances(freqs, peaks):
    """How loud a harmonic at each frequency comes out of resonances (center Hz, width Hz, gain)."""
    g = np.zeros_like(freqs, dtype=float)
    for c, w, a in peaks:
        g += a / (1 + ((freqs - c) / w) ** 2)
    return g


def additive(f0, detune, wobble, weight):
    """One player: every harmonic below 9 kHz, each weighted by weight(frequency)."""
    inst = f0 * (1 + detune) * (1 + wobble)
    phase = 2 * np.pi * np.cumsum(inst) / RATE
    x = np.zeros(N)
    k = 1
    while k * f0 < 9000:
        x += weight(k * f0) * np.sin(k * phase + rng.uniform(0, 6.28))
        k += 1
    return x


def envelope(attack, swell=0.0):
    a = np.clip(t / attack, 0, 1) ** 1.3
    if swell:
        a *= 1 + swell * np.clip((t - attack) / 2.5, 0, 1)
    tail = np.clip((SECONDS - t) / 0.35, 0, 1)                         # the strip running out
    return a * tail


VIOLIN_BODY = [(280, 90, 0.9), (460, 120, 1.0), (1100, 300, 0.55), (2600, 700, 0.7), (4200, 900, 0.3)]


def strings(midi):
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    w = tape()
    def weight(f):
        k = f / f0
        return (1.0 / k ** 0.95) * (0.35 + resonances(np.array(f), VIOLIN_BODY))
    x = np.zeros(N)
    for cents in (-7, 0, 6):                                              # three players
        x += additive(f0, cents / 1200, w + vibrato(5.6 + rng.uniform(-0.4, 0.4), 0.0035, 0.35), weight)
    bow = bandpass(rng.standard_normal(N), min(f0 * 2, 6000), min(f0 * 10, 12000))  # bow hair on the string
    bow /= np.abs(bow).max() + 1e-9
    x = x / (np.abs(x).max() + 1e-9) + 0.015 * bow
    x *= envelope(0.16, swell=0.08)
    x = lowpass(x, 6500, 2)
    return x / (np.abs(x).max() + 1e-9) * 0.7


# "aah": formants (center, width, gain) for a mixed choir
AAH = [(730, 90, 1.0), (1090, 110, 0.55), (2440, 160, 0.35), (3400, 250, 0.15)]


def choir(midi):
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    w = tape()
    def weight(f):
        k = f / f0
        return (1.0 / k ** 0.6) * (0.04 + resonances(np.array(f), AAH))
    x = np.zeros(N)
    for cents in (-11, -5, 0, 4, 9, 13):                                 # six singers
        x += additive(f0, cents / 1200, w + vibrato(5.0 + rng.uniform(-0.5, 0.5), 0.004, 0.5), weight)
    breath = sum(a * bandpass(rng.standard_normal(N), c - w2, c + w2) for c, w2, a in AAH[:3])
    breath /= np.abs(breath).max() + 1e-9
    x = x / (np.abs(x).max() + 1e-9) + 0.03 * breath
    x *= envelope(0.30, swell=0.12)
    x = lowpass(x, 5200, 2)
    return x / (np.abs(x).max() + 1e-9) * 0.7


SETS = {"strings": strings, "choir": choir}

if __name__ == "__main__":
    for name in (sys.argv[1:] or SETS):
        out = os.path.join(OUT, name)
        os.makedirs(out, exist_ok=True)
        for midi in range(43, 78):
            sf.write(os.path.join(out, f"{midi:03d}.wav"), SETS[name](midi).astype(np.float32), RATE, subtype="PCM_16")
        print("wrote 35", name, "strips to", os.path.abspath(out))
