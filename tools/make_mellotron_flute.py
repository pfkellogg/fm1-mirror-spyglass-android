# make_mellotron_flute.py: the built-in Mellotron's "tape flute" strips, made from scratch (no recordings, so no
# rights questions): one per key over a Mellotron's 35-key range, G2 (MIDI 43) to F5 (77), 8 seconds each, the way a
# Mellotron's tape strips are. A flute-like tone (a strong fundamental, a few soft harmonics, breath noise), a slow
# vibrato that sets in after the attack, tape wow and flutter, and the strip running out at 8 s (BREATH, CHIFF and HISS set how airy).
# Written as 16-bit mono WAV at 44100 Hz to samples/flute/043.wav ... 077.wav; the app has them as FLAC:
#   for f in samples/flute/*.wav; do ffmpeg -i $f -c:a flac app/src/main/assets/mellotron/flute/$(basename $f .wav).flac; done
import os, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
BREATH, CHIFF, HISS = 0.012, 0.10, 0.0      # 2026-11-22: the first set (0.05, 0.35, 0.0015) whooshed
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "flute")
rng = np.random.default_rng(7)

def bandpass(x, lo, hi):
    b, a = butter(2, [lo / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def note(midi):
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    # tape: slow wow (0.6 Hz) and faster flutter (7 Hz), each strip a little different
    wow = 0.0022 * np.sin(2 * np.pi * 0.6 * t + rng.uniform(0, 6.28))
    flutter = 0.0007 * np.sin(2 * np.pi * 7.1 * t + rng.uniform(0, 6.28))
    # the player's vibrato: 5 Hz, fading in after half a second
    vib = 0.004 * np.clip((t - 0.5) / 1.0, 0, 1) * np.sin(2 * np.pi * 5.0 * t)
    phase = 2 * np.pi * np.cumsum(f0 * (1 + wow + flutter + vib)) / RATE
    # a flute: mostly the fundamental, soft 2nd and 3rd, a trace of 4th; brighter as it gets louder
    tone = (np.sin(phase) + 0.22 * np.sin(2 * phase + 0.3) + 0.08 * np.sin(3 * phase + 1.1)
            + 0.025 * np.sin(4 * phase + 2.0))
    # breath: noise around the note, stronger at the attack
    breath = bandpass(rng.standard_normal(n), f0 * 0.8, min(f0 * 6, 9000))
    breath /= np.abs(breath).max() + 1e-9
    attack = np.clip(t / 0.07, 0, 1) ** 1.5                  # the tape head meeting the strip
    chiff = np.exp(-t / 0.05) * CHIFF                          # a short breathy start
    level = attack * (0.92 + 0.08 * np.sin(2 * np.pi * 0.25 * t))
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)            # the strip running out
    x = (tone * level * 0.55 + breath * (BREATH * level + chiff)) * out_tail
    # a soft low-pass for the tape's dullness, and a little hiss
    b, a = butter(1, min(5200 / (RATE / 2), 0.99))
    x = lfilter(b, a, x) + HISS * rng.standard_normal(n)
    return x / (np.abs(x).max() + 1e-9) * 0.7

os.makedirs(OUT, exist_ok=True)
for midi in range(43, 78):
    sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
print("wrote", len(range(43, 78)), "samples to", os.path.abspath(OUT))
