# make_mellotron_shakuhachi.py: a Mellotron-style "tape shakuhachi", made from scratch (no recordings, so no rights
# questions): one strip per key over the Mellotron's 35 keys, G2 (MIDI 43) to F5 (77), 8 seconds each, like the flute,
# strings and choir. Written as 16-bit mono WAV at 44100 Hz to samples/shakuhachi/043.wav ... 077.wav.
#
# What makes a shakuhachi (the end-blown bamboo flute of Japan), and how each note here does it:
#  - muraiki: the breath bursts into the bamboo before the tone settles (a loud, rough puff of air at the start)
#  - atari: the note is reached from below, a scoop of most of a semitone over the first fifth of a second
#  - yuri: a slow, deep vibrato that comes in late (the player nods the head), 4-5.5 Hz, 15-30 cents
#  - the tone: airy, the breath audible all the time, the harmonics rough (each one breathing on its own);
#    low notes dark and woody with a buzzing "sawari" edge, high notes clear and singing (kari)
#  - the bamboo: a hollow body with soft resonances, a long bore's slight delay of the upper harmonics
#  - and the Mellotron: tape wow and flutter, a little saturation, the top rolling off, the strip running out at 8 s
import os, sys, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "shakuhachi")
rng = np.random.default_rng(11)

def bandpass(x, lo, hi, order=2):
    b, a = butter(order, [max(lo, 20) / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)

def smooth_noise(n, hz, r):
    """slow random wobble, about hz wide, from the generator r (-1 .. 1-ish)"""
    x = lowpass(r.standard_normal(n), hz, 2)
    return x / (np.abs(x).max() + 1e-9)

def note(midi):
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    hi = np.clip((midi - 50) / 27, 0, 1)                 # 0 at the bottom of the range, 1 at the top
    me = np.random.default_rng(2000 + midi)              # this strip's own take, the same every build
    f0 = 440.0 * 2 ** ((midi - 69 + np.clip(me.normal(0, 2.5), -6, 6) / 100) / 12)
    # atari: reach the note from below (most of a semitone), a little overshoot, settle
    scoop_c = -(55 + 35 * me.random()) * np.exp(-t / (0.07 + 0.05 * me.random()))
    scoop_c += 6 * np.exp(-((t - 0.22) / 0.08) ** 2)
    # yuri: late, slow and deep; its rate wanders like a player's
    yrate = (4.4 + 1.0 * me.random()) * (1 + 0.08 * smooth_noise(n, 0.6, me))
    yph = 2 * np.pi * np.cumsum(yrate) / RATE + me.uniform(0, 6.28)
    ydepth = (16 + 14 * hi) * (1 + 0.2 * me.normal()) * np.clip((t - 0.7 - 0.5 * me.random()) / 1.2, 0, 1)
    yuri_c = ydepth * np.sin(yph)
    # breath pressure drifts: tiny pitch drift too (a shakuhachi's pitch follows the breath)
    drift_c = 4 * smooth_noise(n, 0.8, me)
    # the tape: wow and flutter, as the Mellotron flute
    p1, p2, p3 = me.uniform(0, 6.28, 3)
    wow = 0.0012 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0005 * np.sin(2 * np.pi * 1.3 * t + p2) \
        + 0.0005 * np.sin(2 * np.pi * 7.0 * t + p3)
    cents = scoop_c + yuri_c + drift_c
    freq = f0 * 2 ** (cents / 1200) * (1 + wow)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    # the loudness: breath burst, the tone blooming, the yuri pulsing the loudness with it, a long phrase's swell
    bloom = 1 - np.exp(-t / (0.10 + 0.08 * (1 - hi)))
    swell = 0.85 + 0.15 * np.clip(t / (1.5 + me.random()), 0, 1) - 0.12 * np.clip((t - 4.5) / 3.0, 0, 1)
    pulse = 1 + 0.22 * np.clip(ydepth / 25, 0, 1) * np.sin(yph - 0.5)
    level = bloom * swell * pulse * (1 + 0.05 * smooth_noise(n, 3.0, me))
    # the tone: harmonics, each breathing on its own (rough), fewer and softer up high; the low end buzzes (sawari)
    tone = np.zeros(n)
    nh = 14
    for k in range(1, nh + 1):
        if k * f0 > 12000:
            break
        a = (1.0 / k ** (1.05 + 0.55 * hi))                       # brighter low down, purer up high
        if k == 2:
            a *= 0.55 + 0.25 * (1 - hi)                            # the open-ended bore: a softer octave
        if k == 3:
            a *= 0.9
        a *= 1 + 0.6 * (1 - hi) * (k >= 4) * (k <= 9)             # sawari: a buzzing band low down
        rough = 1 + (0.25 + 0.03 * k) * smooth_noise(n, 25 + 6 * k, me)   # each harmonic flickers with the breath
        delay = np.clip((t - 0.012 * k * (1 - hi)) / 0.08, 0, 1)   # upper harmonics come in a touch later
        tone += a * rough * delay * np.sin(k * phase + me.uniform(0, 6.28))
    tone /= np.abs(tone).max() + 1e-9
    # the breath: always there, in bands around the first harmonics (pitched air) and a wide airy hiss
    pitched_air = sum(g * bandpass(me.standard_normal(n), k * f0 * 0.9, min(k * f0 * 1.1, 15000))
                      for k, g in ((1, 1.0), (2, 0.7), (3, 0.5), (4, 0.35), (5, 0.25)) if k * f0 < 13000)
    pitched_air /= np.abs(pitched_air).max() + 1e-9
    hiss = bandpass(me.standard_normal(n), 1200, 7000)
    hiss /= np.abs(hiss).max() + 1e-9
    air_amt = (0.34 + 0.12 * (1 - hi)) * (1 + 0.15 * me.normal())
    # muraiki: the burst of breath at the start, rough and loud, gone in a quarter second
    burst = bandpass(me.standard_normal(n), 500, 6000)
    burst /= np.abs(burst).max() + 1e-9
    burst_env = np.clip(t / 0.015, 0, 1) * np.exp(-t / (0.09 + 0.06 * (1 - hi))) * (0.9 + 0.4 * me.random())
    air_level = bloom * swell * (1 + 0.35 * smooth_noise(n, 2.0, me)) * (1 + 0.15 * np.sin(yph))
    x = tone * level + air_amt * (0.7 * pitched_air + 0.45 * hiss) * air_level + 0.55 * burst * burst_env
    x /= np.abs(x).max() + 1e-9
    # the bamboo: hollow resonances (a soft body around 700 Hz and an edge around 2.5 kHz), no boom
    x = x + 0.35 * bandpass(x, 550, 950) + 0.22 * bandpass(x, 2000, 3200)
    b, a = butter(2, max(f0 * 0.55, 40) / (RATE / 2), btype="high")
    x = lfilter(b, a, x)
    # the Mellotron tape: a little saturation, the top rolling off
    x = np.tanh(1.5 * x / (np.abs(x).max() + 1e-9)) / np.tanh(1.5)
    x = lowpass(x, 8500, 2)
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)            # the strip running out
    gain = 10 ** ((-3.5 * (1 - hi) + me.normal(0, 0.6)) / 20)  # low notes a little softer, each its own
    return x / (np.abs(x).max() + 1e-9) * 0.7 * gain * out_tail

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    keys = range(43, 78) if len(sys.argv) < 2 else [int(a) for a in sys.argv[1:]]
    for midi in keys:
        sf.write(os.path.join(OUT, f"{midi:03d}.wav"), note(midi).astype(np.float32), RATE, subtype="PCM_16")
    print("wrote", len(list(keys)), "strips to", os.path.abspath(OUT))
