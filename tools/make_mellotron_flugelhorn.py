# make_mellotron_flugelhorn.py: a Mellotron-style "tape flugelhorn", made from scratch (no recordings, so no rights
# questions), in the warm, round, breathy way Chuck Mangione played it: one strip per key over the Mellotron's 35 keys,
# G2 (MIDI 43) to F5 (77), 8 seconds each. Written as 16-bit mono WAV at 44100 Hz to samples/flugelhorn/043.wav ...
#
# What makes a flugelhorn (a conical brass horn: darker and softer than a trumpet), and how each note here does it:
#  - the lips: a soft start, the pitch settling up from a little below as the lips lock in, a puff of air before
#  - brass gets brighter as it gets louder: each note blooms, its upper harmonics growing with the swell
#  - the conical bore and the big bell: a strong, round fundamental, the harmonics falling away fast, a warm body
#    around 0.8-1.4 kHz, almost no edge above 4 kHz
#  - Mangione's sound: a soft, airy tone with breath in it, a gentle vibrato (about 5.3 Hz, 12-18 cents) that comes
#    in after the note has settled, and a long singing sustain
#  - and the Mellotron: tape wow and flutter, a little saturation, the top rolling off, the strip running out at 8 s
import os, sys, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "flugelhorn")

def bandpass(x, lo, hi, order=2):
    b, a = butter(order, [max(lo, 20) / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)

def smooth_noise(n, hz, r):
    x = lowpass(r.standard_normal(n), hz, 2)
    return x / (np.abs(x).max() + 1e-9)

def note(midi):
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    hi = np.clip((midi - 50) / 27, 0, 1)
    me = np.random.default_rng(3000 + midi)
    f0 = 440.0 * 2 ** ((midi - 69 + np.clip(me.normal(0, 2.0), -5, 5) / 100) / 12)
    # the lips lock in: up from about 30 cents below in the first 40-60 ms
    lip_c = -(22 + 16 * me.random()) * np.exp(-t / (0.035 + 0.02 * me.random()))
    # the vibrato: gentle and late, the player's own rate
    vrate = (5.2 + 0.25 * me.normal()) * (1 + 0.05 * smooth_noise(n, 0.5, me))
    vph = 2 * np.pi * np.cumsum(vrate) / RATE + me.uniform(0, 6.28)
    vdepth = (13 + 5 * me.random()) * np.clip((t - 0.55 - 0.3 * me.random()) / 0.9, 0, 1)
    drift_c = 2.5 * smooth_noise(n, 0.7, me)
    p1, p2, p3 = me.uniform(0, 6.28, 3)
    wow = 0.0012 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0005 * np.sin(2 * np.pi * 1.3 * t + p2) \
        + 0.0005 * np.sin(2 * np.pi * 7.0 * t + p3)
    freq = f0 * 2 ** ((lip_c + vdepth * np.sin(vph) + drift_c) / 1200) * (1 + wow)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    # the swell: a soft attack (70-110 ms), then the note opening up a little over the first second, a long sustain
    att = 0.07 + 0.04 * (1 - hi)
    env = np.clip(t / att, 0, 1) ** 1.8
    swell = 0.82 + 0.18 * np.clip(t / (0.9 + 0.6 * me.random()), 0, 1) ** 0.8 - 0.08 * np.clip((t - 5.0) / 3.0, 0, 1)
    amp = env * swell * (1 + 0.10 * np.clip(vdepth / 15, 0, 1) * np.sin(vph - 0.4)) * (1 + 0.03 * smooth_noise(n, 2.5, me))
    # brightness follows the loudness (brass): the harmonics' slope eases as the note swells
    bright = np.clip(amp, 0, 1) ** 1.3
    tone = np.zeros(n)
    for k in range(1, 16):
        if k * f0 > 9000:
            break
        # a round, dark horn: a strong fundamental; each harmonic dB below it, steeper when soft, low notes darker
        slope = (7.5 + 3.0 * (1 - hi)) - 3.2 * bright          # dB per harmonic step
        db = -(k - 1) * slope - (2.0 if k == 2 else 0.0)
        a = 10 ** (db / 20)
        tone += a * np.sin(k * phase + me.uniform(0, 6.28)) * (1 + 0.04 * k * smooth_noise(n, 12 + 3 * k, me))
    tone = tone / (np.abs(tone).max() + 1e-9) * amp
    # breath: a soft air around the tone (Mangione's airy sound) and a little puff before the lips lock in
    air = bandpass(me.standard_normal(n), 900, 4200)
    air /= np.abs(air).max() + 1e-9
    puff = bandpass(me.standard_normal(n), 400, 2500)
    puff /= np.abs(puff).max() + 1e-9
    puff_env = np.clip(t / 0.01, 0, 1) * np.exp(-t / 0.045)
    x = tone + 0.045 * air * (0.5 + 0.5 * amp) + 0.18 * puff * puff_env
    x /= np.abs(x).max() + 1e-9
    # the bell: a warm body around 0.8-1.4 kHz, nothing harsh up top
    x = x + 0.32 * bandpass(x, 750, 1450) - 0.15 * bandpass(x, 3500, 7000)
    b, a = butter(2, max(f0 * 0.6, 40) / (RATE / 2), btype="high")
    x = lfilter(b, a, x)
    # the Mellotron tape
    x = np.tanh(1.4 * x / (np.abs(x).max() + 1e-9)) / np.tanh(1.4)
    x = lowpass(x, 7500, 2)
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)
    gain = 10 ** ((-2.5 * (1 - hi) + me.normal(0, 0.5)) / 20)
    return x / (np.abs(x).max() + 1e-9) * 0.7 * gain * out_tail

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    keys = range(43, 78) if len(sys.argv) < 2 else [int(a) for a in sys.argv[1:]]
    for midi in keys:
        sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
    print("wrote", len(list(keys)), "strips to", os.path.abspath(OUT))
