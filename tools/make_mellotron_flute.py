# make_mellotron_flute.py: the built-in Mellotron's "tape flute" strips, made from scratch (no recordings, so no
# rights questions): one per key over a Mellotron's 35-key range, G2 (MIDI 43) to F5 (77), 8 seconds each, the way a
# Mellotron's tape strips are. A flute-like tone (a strong fundamental, a few soft harmonics, breath noise), a slow
# vibrato that sets in after the attack, and the tape machine (see note()); the strip runs out at 8 s.
# Written as 16-bit mono WAV at 44100 Hz to samples/flute/043.wav ... 077.wav; the app has them as FLAC:
#   for f in samples/flute/*.wav; do ffmpeg -i $f -c:a flac app/src/main/assets/mellotron/flute/$(basename $f .wav).flac; done
import os, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
BREATH, CHIFF = 0.16, 0.18        # breath around the note (the real one's air is ~15 dB under the tone low down), chiff
BREATH_CURVE = (2.9, 1.35, 1.35)  # breath low / middle / high (v6 was 1, 1, 1: measured 6.6 / 2.6 / 2.9 dB short)
VIB_LOW, VIB_HIGH = 8.5, 12.8     # vibrato depth in cents, bottom to top (the real one's deepens with pitch)
TREMOLO_LOW, TREMOLO_HIGH = 0.36, 0.20   # how much the breath vibrato pulses the loudness (real: 0.18 low, 0.10 high)
H2_CURVE = (1.45, 0.45, 0.16)     # 2nd harmonic low / middle / high (real: -3, -10, -19 dB)
H3_CURVE = (1.65, 0.55, 0.30)     # 3rd harmonic (real: -3, -14, -16 dB)
HIGH_AIR = 0.007                  # 6-12 kHz air (real: -44..-50 dB; v6 had none)
TOP_HZ = 8000                     # the tape's top (v6: 4500, too dark: centroid 1100-1700 vs the real 1700-2300)
SWELL, SWELL_DEPTH = 3.0, 0.45    # low notes: speak at 70 %, swell to full over ~1-2 s
LEVEL_SLOPE_DB = 4.0              # low notes this much softer than high ones (real: ~4 dB)
TUNE_SD = 2.3                     # each note's own tuning, cents
WOW, ATTACK = 0.0012, 0.12        # slow tape wow (share of pitch), and how long a note takes to speak (s)
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "flute")

rng = np.random.default_rng(7)

def bandpass(x, lo, hi):
    b, a = butter(2, [lo / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)

def note(midi):
    """2026-11-23 v7, "note by note": every one of the 35 notes measured against the real Mellotron flute's strips
    (aikellogg_videos/flute-study/NOTES.md: numbers only, no audio copied) and given its own character, as each real
    strip is its own take. Low notes: breathier, a stronger breath vibrato, softer, speaking quickly then swelling (a
    player's crescendo); high notes: purer, a quick start, louder. Brighter overall (the real one's centroid is 1700-2300
    Hz), a touch of high air. Each note a little out of tune its own way (about +-2.5 cents), its own vibrato rate and
    depth, breath and level. Kept in tune overall (the real one runs ~5 cents sharp: it would clash with the FM-1).
    v6 before it: woodier, the breath steady inside the tone (v5's pulsed like a freight train), no thud."""
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    hi = np.clip((midi - 50) / 27, 0, 1)                          # 0 at the bottom of the range, 1 at the top
    me = np.random.default_rng(1000 + midi)                       # this strip's own character, the same every build
    f0 = 440.0 * 2 ** ((midi - 69 + np.clip(me.normal(0, TUNE_SD), -5, 5) / 100) / 12)
    p1, p2, p3, p4, p5 = rng.uniform(0, 6.28, 5)
    # the player's vibrato: about 5.3 Hz (this note's own), never quite even, deeper up high
    vrate = (5.25 + me.normal(0, 0.12)) * (1 + 0.07 * np.sin(2 * np.pi * 0.29 * t + p5))
    vphase = 2 * np.pi * np.cumsum(vrate) / RATE + rng.uniform(0, 6.28)
    vdepth = 0.4 + 0.6 * np.clip((t - 0.15) / 0.5, 0, 1)
    vib_cents = (VIB_LOW + (VIB_HIGH - VIB_LOW) * hi) * (1 + me.normal(0, 0.12))
    vib = vib_cents / 1731.0 * vdepth * np.sin(vphase)             # cents -> share of the pitch (1200/ln 2)
    # the tape: a little slow wow and quick flutter, and coming up to speed at the start
    scoop = -0.006 * np.exp(-t / 0.06)
    wow = WOW * np.sin(2 * np.pi * 0.55 * t + p1) + WOW * 0.4 * np.sin(2 * np.pi * 1.3 * t + p2)
    flutter = 0.0006 * np.sin(2 * np.pi * 7.0 * t + p3) + 0.0003 * np.sin(2 * np.pi * 11.3 * t + p4)
    phase = 2 * np.pi * np.cumsum(f0 * (1 + scoop + wow + flutter + vib)) / RATE
    # the tone: reedy low (2nd and 3rd as strong as the fundamental), purer up high, a 3rd kept up there
    h2 = np.interp(hi, [0, 0.5, 1], H2_CURVE); h3 = np.interp(hi, [0, 0.5, 1], H3_CURVE)
    # (upper harmonics strong low down too: the real low notes are bright, centroid ~1700 Hz, not dark)
    amps = [1.0, h2, h3, 0.85 - 0.7 * hi, 0.75 - 0.68 * hi, 0.5 - 0.44 * hi, 0.32 - 0.27 * hi, 0.2 - 0.16 * hi]
    tone = sum(a * np.sin((k + 1) * phase + rng.uniform(0, 6.28)) for k, a in enumerate(amps) if (k + 1) * f0 < 14000)
    # breath: inside the tone (narrow bands on the first harmonics), steady; more down low; a little high air
    breath = sum(g * bandpass(rng.standard_normal(n), k * f0 * 0.93, min(k * f0 * 1.07, 15000))
                 for k, g in ((1, 1.0), (2, 0.6), (3, 0.35), (4, 0.3 * (1 - hi)), (5, 0.25 * (1 - hi))) if k * f0 < 14000)
    breath = breath / (np.abs(breath).max() + 1e-9)
    air = bandpass(rng.standard_normal(n), 800, 3500)
    air = air / (np.abs(air).max() + 1e-9)
    high_air = bandpass(rng.standard_normal(n), 6000, 12000)
    high_air = high_air / (np.abs(high_air).max() + 1e-9)
    chiff = bandpass(rng.standard_normal(n), min(f0 * 2, 8000), min(f0 * 6, 12000))
    chiff /= np.abs(chiff).max() + 1e-9
    breath_amt = BREATH * np.interp(hi, [0, 0.5, 1], BREATH_CURVE) * (1 + me.normal(0, 0.12))
    # speaks quickly, then (low notes) swells like a player's crescendo
    swell = SWELL * (1 - hi) * me.uniform(0.5, 1.4)
    attack = np.clip(t / ATTACK, 0, 1) ** 1.6 * (1 - SWELL_DEPTH * (1 - hi) * (1 - np.clip(t / max(swell, 1e-3), 0, 1) ** 0.7))
    trem = TREMOLO_LOW + (TREMOLO_HIGH - TREMOLO_LOW) * hi
    pulse = 1 + trem * vdepth * np.sin(vphase - 0.6)              # the breath vibrato: louder and softer with the pitch
    level = attack * pulse * (1 + 0.02 * np.sin(2 * np.pi * 7.0 * t + p3))
    x = tone / sum(amps) * level + (breath_amt * breath + 0.12 * breath_amt * air) * attack \
        + HIGH_AIR * (1.2 - 0.8 * hi) * high_air * attack + CHIFF * chiff * np.exp(-t / 0.04) * np.clip(t / 0.01, 0, 1)
    x /= np.abs(x).max() + 1e-9
    # wood: a soft hollow resonance; the tape machine: a little saturation, the top rolling off (higher than v6)
    x = x + 0.30 * bandpass(x, 600, 1200)
    x = np.tanh(1.6 * x / (np.abs(x).max() + 1e-9)) / np.tanh(1.6)
    x = lowpass(x, TOP_HZ, 2)
    # last: the saturation makes an offset that pumps with the vibrato and thuds when a key is let go
    b, a = butter(2, max(f0 * 0.6, 40) / (RATE / 2), btype="high")
    x = lfilter(b, a, x)
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)            # the strip running out
    gain = 10 ** ((-LEVEL_SLOPE_DB * (1 - hi) + me.normal(0, 0.6)) / 20)   # low notes softer, each a little its own
    return x / (np.abs(x).max() + 1e-9) * 0.7 * gain * out_tail

os.makedirs(OUT, exist_ok=True)
for midi in range(43, 78):
    sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
print("wrote", len(range(43, 78)), "samples to", os.path.abspath(OUT))
