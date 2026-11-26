# make_mellotron_trumpet.py: a Mellotron-style "tape trumpet", made from scratch (no recordings, so no rights
# questions): one strip per key over the Mellotron's 35 keys, G2 (MIDI 43) to F5 (77), 8 seconds each. Written as
# 16-bit mono WAV at 44100 Hz to samples/trumpet/043.wav ... (the flugelhorn's generator, made a trumpet:)
#
# What makes a trumpet (a narrow, mostly cylindrical brass tube: brighter and bolder than a flugelhorn), here:
#  - the lips: a crisp start, the pitch locking in quickly from below, a short buzz as the lips catch
#  - brass gets brighter as it gets louder, and a trumpet "blares": strong middle harmonics (2nd-5th), a bright
#    bell edge around 1.5-3 kHz
#  - less breath than a flugelhorn, a lighter vibrato that comes in late (about 5.2 Hz, 8-13 cents)
#  - and the Mellotron: tape wow and flutter, a little saturation, the top rolling off, the strip running out at 8 s
import os, sys, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "trumpet")

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
    me = np.random.default_rng(4000 + midi)
    f0 = 440.0 * 2 ** ((midi - 69 + np.clip(me.normal(0, 2.0), -5, 5) / 100) / 12)
    # the lips lock in: up from about 30 cents below in the first 40-60 ms
    lip_c = -(30 + 20 * me.random()) * np.exp(-t / (0.022 + 0.012 * me.random()))   # trumpet: a quicker, firmer lock-in
    # the vibrato: gentle and late, the player's own rate
    vrate = (5.2 + 0.25 * me.normal()) * (1 + 0.05 * smooth_noise(n, 0.5, me))
    vph = 2 * np.pi * np.cumsum(vrate) / RATE + me.uniform(0, 6.28)
    vdepth = (8 + 5 * me.random()) * np.clip((t - 0.8 - 0.4 * me.random()) / 1.0, 0, 1)   # trumpet: lighter, later
    drift_c = 2.5 * smooth_noise(n, 0.7, me)
    p1, p2, p3 = me.uniform(0, 6.28, 3)
    wow = 0.0012 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0005 * np.sin(2 * np.pi * 1.3 * t + p2) \
        + 0.0005 * np.sin(2 * np.pi * 7.0 * t + p3)
    freq = f0 * 2 ** ((lip_c + vdepth * np.sin(vph) + drift_c) / 1200) * (1 + wow)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    # the swell: a soft attack (70-110 ms), then the note opening up a little over the first second, a long sustain
    att = 0.035 + 0.02 * (1 - hi)   # trumpet: a crisp attack
    env = np.clip(t / att, 0, 1) ** 1.8
    swell = 0.82 + 0.18 * np.clip(t / (0.9 + 0.6 * me.random()), 0, 1) ** 0.8 - 0.08 * np.clip((t - 5.0) / 3.0, 0, 1)
    amp = env * swell * (1 + 0.10 * np.clip(vdepth / 15, 0, 1) * np.sin(vph - 0.4)) * (1 + 0.03 * smooth_noise(n, 2.5, me))
    # brightness follows the loudness (brass): the harmonics' slope eases as the note swells
    bright = np.clip(amp, 0, 1) ** 1.3
    tone = np.zeros(n)
    for k in range(1, 16):
        if k * f0 > 12000:
            break
        # a round, dark horn: a strong fundamental; each harmonic dB below it, steeper when soft, low notes darker
        slope = (5.0 + 1.5 * (1 - hi)) - 3.0 * bright          # dB per harmonic step: brighter than the flugelhorn, blaring when loud
        db = -(k - 1) * slope + (3.0 if 2 <= k <= 5 else 0.0)   # the trumpet's strong middle harmonics
        a = 10 ** (db / 20)
        tone += a * np.sin(k * phase + me.uniform(0, 6.28)) * (1 + 0.04 * k * smooth_noise(n, 12 + 3 * k, me))
    tone = tone / (np.abs(tone).max() + 1e-9) * amp
    # breath: a little air around the tone, a puff before the lips lock in, and the buzz as they catch
    air = bandpass(me.standard_normal(n), 900, 4200)
    air /= np.abs(air).max() + 1e-9
    puff = bandpass(me.standard_normal(n), 400, 2500)
    puff /= np.abs(puff).max() + 1e-9
    puff_env = np.clip(t / 0.01, 0, 1) * np.exp(-t / 0.045)
    buzz = bandpass(me.standard_normal(n), f0 * 3, min(f0 * 9, 12000))
    buzz /= np.abs(buzz).max() + 1e-9
    x = tone + 0.022 * air * (0.5 + 0.5 * amp) + 0.10 * puff * puff_env + 0.10 * buzz * np.exp(-t / 0.03) * np.clip(t / 0.005, 0, 1)
    x /= np.abs(x).max() + 1e-9
    # the bell: a warm body around 0.8-1.4 kHz, nothing harsh up top
    x = x + 0.20 * bandpass(x, 900, 1400) + 0.35 * bandpass(x, 1500, 3000)   # the bell's bright edge
    b, a = butter(2, max(f0 * 0.6, 40) / (RATE / 2), btype="high")
    x = lfilter(b, a, x)
    # the Mellotron tape
    x = np.tanh(1.7 * x / (np.abs(x).max() + 1e-9)) / np.tanh(1.7)
    x = lowpass(x, 9500, 2)
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)
    gain = 10 ** ((-2.5 * (1 - hi) + me.normal(0, 0.5)) / 20)
    return x / (np.abs(x).max() + 1e-9) * 0.7 * gain * out_tail

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    keys = range(43, 78) if len(sys.argv) < 2 else [int(a) for a in sys.argv[1:]]
    for midi in keys:
        sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
    print("wrote", len(list(keys)), "strips to", os.path.abspath(OUT))
