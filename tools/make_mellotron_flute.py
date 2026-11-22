# make_mellotron_flute.py: the built-in Mellotron's "tape flute" strips, made from scratch (no recordings, so no
# rights questions): one per key over a Mellotron's 35-key range, G2 (MIDI 43) to F5 (77), 8 seconds each, the way a
# Mellotron's tape strips are. A flute-like tone (a strong fundamental, a few soft harmonics, breath noise), a slow
# vibrato that sets in after the attack, and the tape machine (see note()); the strip runs out at 8 s.
# Written as 16-bit mono WAV at 44100 Hz to samples/flute/043.wav ... 077.wav; the app has them as FLAC:
#   for f in samples/flute/*.wav; do ffmpeg -i $f -c:a flac app/src/main/assets/mellotron/flute/$(basename $f .wav).flac; done
import os, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
BREATH, CHIFF = 0.16, 0.18        # breath around the note (the real one's air is ~15 dB under the tone), chiff at the start
VIB_CENTS, TREMOLO = 10.0, 0.20   # the player's vibrato: pitch depth in cents, and how much it pulses the loudness
WOW, ATTACK = 0.0012, 0.15        # slow tape wow (share of pitch), and how long a note takes to come in (s)
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "flute")

rng = np.random.default_rng(7)

def bandpass(x, lo, hi):
    b, a = butter(2, [lo / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)

def note(midi):
    """2026-11-22 v6: woodier (a hollow body resonance, a darker top), and the breath steady and inside the tone (v5's
    pulsed and sounded like a freight train). v5, matched by measurement to a real Mellotron flute (listened to and measured, not copied): its
    wobble is mostly the flute player's vibrato, about 5.3 Hz and +-7 cents, pulsing the loudness as much as the pitch
    (a breath vibrato), with only a little slow tape wow; it's bright (a strong 2nd and 3rd harmonic), very breathy
    (the air about 15 dB under the tone, close around the note), and it starts softly (about 0.17 s)."""
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    f0 = 440.0 * 2 ** ((midi - 69) / 12)
    p1, p2, p3, p4, p5 = rng.uniform(0, 6.28, 5)
    # the player's vibrato: about 5.3 Hz, never quite even, in from the start and fuller after half a second
    vrate = 5.3 * (1 + 0.07 * np.sin(2 * np.pi * 0.29 * t + p5))
    vphase = 2 * np.pi * np.cumsum(vrate) / RATE + rng.uniform(0, 6.28)
    vdepth = 0.4 + 0.6 * np.clip((t - 0.15) / 0.5, 0, 1)
    vib = VIB_CENTS / 1731.0 * vdepth * np.sin(vphase)             # cents -> share of the pitch (1200/ln 2)
    # the tape: a little slow wow and quick flutter, and coming up to speed at the start
    scoop = -0.006 * np.exp(-t / 0.06)
    wow = WOW * np.sin(2 * np.pi * 0.55 * t + p1) + WOW * 0.4 * np.sin(2 * np.pi * 1.3 * t + p2)
    flutter = 0.0006 * np.sin(2 * np.pi * 7.0 * t + p3) + 0.0003 * np.sin(2 * np.pi * 11.3 * t + p4)
    phase = 2 * np.pi * np.cumsum(f0 * (1 + scoop + wow + flutter + vib)) / RATE
    # a bright flute
    hi = np.clip((midi - 55) / 22, 0, 1)
    # (low notes: 2nd and 3rd as strong as the fundamental or more, as the real one's; high notes: nearly pure)
    amps = [1.0, 1.1 - 0.7 * hi, 1.3 - 1.15 * hi, 0.6 - 0.45 * hi, 0.5 - 0.45 * hi, 0.18 - 0.12 * hi, 0.06, 0.04]
    tone = sum(a * np.sin((k + 1) * phase + rng.uniform(0, 6.28)) for k, a in enumerate(amps) if (k + 1) * f0 < 12000)
    # breath: air inside the tone (narrow bands on the first three harmonics) rather than a hiss beside it, steady
    # (v5's breath pulsed with the vibrato and chugged like a freight train), a trace of open air, a chiff at the start
    breath = sum(g * bandpass(rng.standard_normal(n), k * f0 * 0.93, min(k * f0 * 1.07, 15000))
                 for k, g in ((1, 1.0), (2, 0.6), (3, 0.35)) if k * f0 < 14000)
    breath = breath / (np.abs(breath).max() + 1e-9)
    air = bandpass(rng.standard_normal(n), 800, 3500)
    air = air / (np.abs(air).max() + 1e-9)
    chiff = bandpass(rng.standard_normal(n), min(f0 * 2, 8000), min(f0 * 6, 12000))
    chiff /= np.abs(chiff).max() + 1e-9
    attack = np.clip(t / ATTACK, 0, 1) ** 1.6                   # starts softly
    pulse = 1 + TREMOLO * vdepth * np.sin(vphase - 0.6)         # the breath vibrato: louder and softer with the pitch
    level = attack * pulse * (1 + 0.02 * np.sin(2 * np.pi * 7.0 * t + p3))
    x = tone / sum(amps) * level + (BREATH * breath + 0.12 * BREATH * air) * attack \
        + CHIFF * chiff * np.exp(-t / 0.04) * np.clip(t / 0.01, 0, 1)
    x /= np.abs(x).max() + 1e-9
    # the tape machine: a little saturation, the top rolling off above about 6 kHz; nothing under the note (the user
    # heard a thud: v5's head bump lifted the breath's rumble 5 dB over the real one's)
    # wood: a soft hollow resonance (a wooden flute's body), the top going earlier
    x = x + 0.35 * bandpass(x, 600, 1200)
    x = np.tanh(1.6 * x / (np.abs(x).max() + 1e-9)) / np.tanh(1.6)
    x = lowpass(x, 4500, 2)
    # last: the saturation makes an offset that pumps with the vibrato and thuds when a key is let go
    b, a = butter(2, max(f0 * 0.6, 40) / (RATE / 2), btype="high")
    x = lfilter(b, a, x)
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)            # the strip running out
    return x / (np.abs(x).max() + 1e-9) * 0.7 * out_tail

os.makedirs(OUT, exist_ok=True)
for midi in range(43, 78):
    sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
print("wrote", len(range(43, 78)), "samples to", os.path.abspath(OUT))
