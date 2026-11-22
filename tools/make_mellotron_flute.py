# make_mellotron_flute.py: the built-in Mellotron's "tape flute" strips, made from scratch (no recordings, so no
# rights questions): one per key over a Mellotron's 35-key range, G2 (MIDI 43) to F5 (77), 8 seconds each, the way a
# Mellotron's tape strips are. A flute-like tone (a strong fundamental, a few soft harmonics, breath noise), a slow
# vibrato that sets in after the attack, and the tape machine (see note()); the strip runs out at 8 s.
# Written as 16-bit mono WAV at 44100 Hz to samples/flute/043.wav ... 077.wav; the app has them as FLAC:
#   for f in samples/flute/*.wav; do ffmpeg -i $f -c:a flac app/src/main/assets/mellotron/flute/$(basename $f .wav).flac; done
import os, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
BREATH, CHIFF = 0.045, 0.35                # breath near the note, chiff at the start (v1's 0.05 broad + hiss whooshed)
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "flute")

rng = np.random.default_rng(7)

def bandpass(x, lo, hi):
    b, a = butter(2, [lo / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)

def note(midi):
    """2026-11-22 v4, the 1960s Mellotron flute as heard on the records: darker, hollow (odd harmonics), breathier,
    a stronger uneven vibrato and more wow (doubled again: the user asked for more wobble). v3, "like a Mellotron": what makes the sound is mostly the 1960s tape machine, so on top of the flute:
    a scoop (the strip starts a little flat and comes up to speed), more wow and flutter, in loudness too, tape
    saturation, a midrange honk and a head bump, and a steep loss of top. The breath stays close to the note
    (BREATH), with no steady hiss: the first set's broad breath and hiss came out as a whoosh."""
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    p1, p2, p3, p4 = rng.uniform(0, 6.28, 4)
    # the tape: coming up to speed (about 15 cents flat for the first tens of ms), slow wow, quick flutter
    scoop = -0.009 * np.exp(-t / 0.07)
    wow = 0.0070 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0025 * np.sin(2 * np.pi * 1.3 * t + p2)
    flutter = 0.0018 * np.sin(2 * np.pi * 7.0 * t + p3) + 0.0004 * np.sin(2 * np.pi * 11.3 * t + p4)
    # the player's vibrato: about 5.4 Hz, fading in after a third of a second
    vrate = 5.8 * (1 + 0.08 * np.sin(2 * np.pi * 0.31 * t + rng.uniform(0, 6.28)))   # a player's vibrato is never even
    vib = 0.0085 * np.clip((t - 0.25) / 0.6, 0, 1) * np.sin(2 * np.pi * np.cumsum(vrate) / RATE)
    phase = 2 * np.pi * np.cumsum(f0 * (1 + scoop + wow + flutter + vib)) / RATE
    # the flute, a little reedy as the Mellotron's is
    tone = (np.sin(phase) + 0.16 * np.sin(2 * phase + 0.3) + 0.20 * np.sin(3 * phase + 1.1)   # hollow: odd harmonics
            + 0.03 * np.sin(4 * phase + 2.0) + 0.06 * np.sin(5 * phase + 0.7))
    # breath close around the note, and a chiff at the start, higher up
    breath = bandpass(rng.standard_normal(n), f0 * 0.9, min(f0 * 3, 9000))
    breath /= np.abs(breath).max() + 1e-9
    chiff = bandpass(rng.standard_normal(n), min(f0 * 2, 8000), min(f0 * 8, 12000))
    chiff /= np.abs(chiff).max() + 1e-9
    attack = np.clip(t / 0.05, 0, 1) ** 1.5
    level = attack * (1 + 0.05 * np.sin(2 * np.pi * 7.0 * t + p3) + 0.06 * np.sin(2 * np.pi * 0.55 * t + p1))
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)            # the strip running out
    x = tone * level * 0.55 + breath * BREATH * level + chiff * CHIFF * np.exp(-t / 0.04)
    x /= np.abs(x).max() + 1e-9
    # the tape machine: a midrange honk, a head bump, saturation, and the top going early
    x = x + 0.22 * bandpass(x, 700, 1800) + 0.30 * lowpass(x, 180)
    x = np.tanh(2.2 * x / (np.abs(x).max() + 1e-9)) / np.tanh(2.2)
    x = lowpass(x, 3200, 3)
    return x / (np.abs(x).max() + 1e-9) * 0.7 * out_tail

os.makedirs(OUT, exist_ok=True)
for midi in range(43, 78):
    sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
print("wrote", len(range(43, 78)), "samples to", os.path.abspath(OUT))
