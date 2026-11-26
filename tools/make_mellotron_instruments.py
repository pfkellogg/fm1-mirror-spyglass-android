# make_mellotron_instruments.py: more Mellotron-style tape sounds, made from scratch (no recordings, so no rights
# questions), one strip per key over the Mellotron's 35 keys, G2 (MIDI 43) to F5 (77), 8 seconds each, like the flute,
# strings, choir, shakuhachi, flugelhorn and trumpet. Each instrument is a recipe (RECIPES below): how its harmonics
# stand (by pitch and by loudness), how it starts and holds, its vibrato, its noise (breath, bow, reed), its body
# (resonances), and anything only it has (two reeds beating, a vibraphone's bar partials and motor). The Mellotron
# tape (wow, flutter, a little saturation, the top rolling off, the strip running out at 8 s) is the same for all.
#   python tools/make_mellotron_instruments.py              every instrument
#   python tools/make_mellotron_instruments.py violin cello the ones named
# Written as 16-bit mono WAV at 44100 Hz to samples/<instrument>/043.wav ... 077.wav.
import os, sys, numpy as np, soundfile as sf
from scipy.signal import butter, lfilter

RATE, SECONDS = 44100, 8.0
ROOT = os.path.join(os.path.dirname(__file__), "..", "samples")

def bandpass(x, lo, hi, order=2):
    b, a = butter(order, [max(lo, 20) / (RATE / 2), min(hi / (RATE / 2), 0.99)], btype="band")
    return lfilter(b, a, x)

def lowpass(x, hz, order=1):
    b, a = butter(order, min(hz / (RATE / 2), 0.99))
    return lfilter(b, a, x)

def smooth_noise(n, hz, r):
    x = lowpass(r.standard_normal(n), hz, 2)
    return x / (np.abs(x).max() + 1e-9)

# ---------------------------------------------------------------------------------------------------- the recipes ---
# amps(k, hi, bright): harmonic k's amplitude (k = 1 the fundamental); hi 0 bottom .. 1 top of the range; bright 0 .. 1
# follows the loudness (brass and reeds open up as they swell). attack/release in seconds, vib = (rate Hz, cents, starts
# at s), noise = (amount, low Hz, high Hz, burst at the start), body = [(low Hz, high Hz, gain)], special = extra.
def violin_amps(k, hi, b):
    return (1 / k ** (0.95 - 0.15 * b)) * (0.85 if k % 2 == 0 else 1.0)          # a bowed string: a bright sawtooth
def cello_amps(k, hi, b):
    return (1 / k ** (1.0 - 0.15 * b)) * (0.9 if k % 2 == 0 else 1.0)
def horn_amps(k, hi, b):
    return 10 ** (-(k - 1) * (9.5 + 3 * (1 - hi) - 3.5 * b) / 20)                # a hand in the bell: dark and round
def harmonica_amps(k, hi, b):
    return (1 / k ** (0.75 - 0.2 * b)) * (1.15 if k in (2, 3, 4) else 1.0)       # a free reed: bright, buzzy
def sax_amps(k, hi, b):
    return (1 / k ** (1.05 - 0.35 * b)) * (1.25 if k in (2, 3) else 1.0)        # a conical reed: warm, full, opens up
def clarinet_amps(k, hi, b):
    odd = 1.0 if k % 2 else (0.12 + 0.5 * hi)                                     # low down only odd harmonics: hollow
    return odd / k ** (1.1 - 0.25 * b)
def oboe_amps(k, hi, b):
    return (1 / k ** 0.55) * (0.45 if k == 1 else 1.0) * np.exp(-(k - 5) ** 2 / 40)   # a double reed: nasal, the middle strong
def accordion_amps(k, hi, b):
    return (1 / k ** 0.8) * (0.9 if k % 2 == 0 else 1.0)
def brass_amps(k, hi, b):
    return 10 ** (-(k - 1) * (6.0 + 2.0 * (1 - hi) - 3.5 * b) / 20) * (1.3 if 2 <= k <= 5 else 1.0)   # a section: bright when loud
def vibes_amps(k, hi, b):
    return 0.0                                                                     # (the vibraphone has bar partials)

RECIPES = {
    "violin":    dict(amps=violin_amps, attack=0.12, vib=(6.0, 22, 0.35), noise=(0.06, 2500, 9000, 0.10), tone_top=9000,
                      body=[(260, 320, 0.25), (430, 520, 0.3), (2300, 3300, 0.45)], tremble=0.03, level=-2.0, bright_follow=0.6),
    "cello":     dict(amps=cello_amps, attack=0.14, vib=(5.3, 20, 0.4), noise=(0.05, 1500, 6000, 0.10), tone_top=7000,
                      body=[(90, 140, 0.35), (180, 260, 0.3), (950, 1400, 0.35)], tremble=0.03, level=-1.0, bright_follow=0.6),
    "frenchhorn": dict(amps=horn_amps, attack=0.10, vib=(5.0, 5, 0.8), noise=(0.012, 500, 2500, 0.08), tone_top=5000,
                      body=[(350, 800, 0.35)], tremble=0.015, level=-1.0, bright_follow=1.0, lip=-25),
    "harmonica": dict(amps=harmonica_amps, attack=0.04, vib=(5.8, 6, 0.6), noise=(0.05, 1500, 7000, 0.12), tone_top=10000,
                      body=[(800, 1500, 0.25), (2000, 3500, 0.2)], tremble=0.02, level=0.0, bright_follow=0.8,
                      hand_tremolo=(5.5, 0.22, 0.9)),
    "altosax":   dict(amps=sax_amps, attack=0.06, vib=(5.0, 18, 0.5), noise=(0.06, 1000, 6000, 0.10), tone_top=9000,
                      body=[(500, 800, 0.3), (1300, 1900, 0.3)], tremble=0.025, level=0.0, bright_follow=1.0, lip=-18),
    "clarinet":  dict(amps=clarinet_amps, attack=0.05, vib=(5.0, 2, 1.0), noise=(0.025, 1200, 5000, 0.08), tone_top=7000,
                      body=[(1200, 2000, 0.15)], tremble=0.012, level=0.0, bright_follow=0.5),
    "oboe":      dict(amps=oboe_amps, attack=0.05, vib=(5.6, 14, 0.4), noise=(0.03, 2000, 7000, 0.08), tone_top=9000,
                      body=[(1000, 1500, 0.4), (2700, 3400, 0.3)], tremble=0.02, level=-1.0, bright_follow=0.5),
    "accordion": dict(amps=accordion_amps, attack=0.05, vib=(0, 0, 9), noise=(0.012, 1500, 5000, 0.05), tone_top=9000,
                      body=[(700, 1300, 0.25)], tremble=0.01, level=0.0, bright_follow=0.4, reeds=(0.0, 13.0, -2.5)),
    "brass":     dict(amps=brass_amps, attack=0.05, vib=(5.0, 4, 1.2), noise=(0.02, 800, 4000, 0.12), tone_top=10000,
                      body=[(500, 900, 0.25), (1400, 2800, 0.35)], tremble=0.03, level=0.0, bright_follow=1.0, lip=-30,
                      reeds=(0.0, -9.0, 7.5), octave_below=0.45, spread=0.025),
    "sitar":     dict(amps=vibes_amps, attack=0.002, vib=(0, 0, 9), noise=(0.0, 1000, 2000, 0.0), tone_top=12000,
                      body=[], tremble=0.0, level=0.0, bright_follow=0.0, sitar=True),
    "vibraphone": dict(amps=vibes_amps, attack=0.003, vib=(0, 0, 9), noise=(0.0, 1000, 2000, 0.0), tone_top=9000,
                      body=[], tremble=0.0, level=0.0, bright_follow=0.0, vibes=True),
}
NAMES = {"frenchhorn": "French Horn", "altosax": "Alto Sax", "brass": "Brass", "sitar": "Sitar"}

def note(name, midi):
    R = RECIPES[name]
    n = int(RATE * SECONDS)
    t = np.arange(n) / RATE
    hi = np.clip((midi - 50) / 27, 0, 1)
    me = np.random.default_rng(sum(map(ord, name)) * 100 + midi)   # the same take every build
    f0 = 440.0 * 2 ** ((midi - 69 + np.clip(me.normal(0, 2.0), -5, 5) / 100) / 12)
    p1, p2, p3 = me.uniform(0, 6.28, 3)
    wow = 0.0012 * np.sin(2 * np.pi * 0.55 * t + p1) + 0.0005 * np.sin(2 * np.pi * 1.3 * t + p2) \
        + 0.0005 * np.sin(2 * np.pi * 7.0 * t + p3)
    if R.get("piano"):   # (not used: the Grand Piano comes from real recordings, tools/make_grand_piano.py)
        # struck strings: 2-3 strings a note a hair apart (they beat), the partials stretched sharp by the strings'
        # stiffness (inharmonicity, more up high), each partial dying faster the higher it is, a quick first drop
        # then a long ring (two-stage decay), a felt hammer's thump, the soundboard's warmth
        Bc = 0.00012 * 2 ** ((midi - 43) / 14)                    # inharmonicity, grows up the keyboard
        strings = [0.0] if midi < 46 else ([0.0, 1.4] if midi < 53 else [-1.1, 0.0, 1.3])
        x = np.zeros(n)
        for k in range(1, 36):
            fk = k * f0 * np.sqrt(1 + Bc * k * k)
            if fk > 11000:
                break
            a = (1 / k ** (1.15 + 0.5 * hi)) * (1.0 if k > 1 else 0.85) * np.exp(-((k - 1) * f0 / 3500) ** 1.2)
            t1 = (0.35 + 0.6 * (1 - hi)) / (1 + 0.15 * k)           # the quick drop
            t2 = (5.5 - 3.5 * hi) / (1 + 0.05 * k)                  # the long ring
            dec = 0.55 * np.exp(-t / t1) + 0.45 * np.exp(-t / t2)
            for c in strings:
                x += a * dec * np.sin(2 * np.pi * fk * 2 ** (c / 1200) * t * (1 + wow) + me.uniform(0, 6.28)) / len(strings)
        hammer = bandpass(me.standard_normal(n), 300, 2500 + 3000 * hi) * np.exp(-t / 0.010) * (0.25 + 0.15 * hi)
        x = x / (np.abs(x).max() + 1e-9) + hammer
        x = x + 0.25 * bandpass(x, 90, 300) + 0.12 * bandpass(x, 2000, 4500)   # the soundboard
    elif R.get("sitar"):
        # a plucked string over the sitar's flat bridge (jawari): the string grazes the bridge, so a bright band of
        # harmonics buzzes and sweeps down as the note rings ("yowng"); the pitch sags a touch as it decays; the
        # sympathetic strings (taraf) hum along a moment later; a sharp plectrum (mizrab) click to start
        ring = 3.2 - 1.2 * hi
        sag = 1 - 0.0025 * (1 - np.exp(-t / 0.8))
        ph = 2 * np.pi * np.cumsum(f0 * sag * (1 + wow)) / RATE
        peak = 2.5 + 16 * np.exp(-t / (0.35 + 0.25 * (1 - hi)))       # where the buzz is, in harmonics
        x = np.zeros(n)
        for k in range(1, 48):
            if k * f0 > 11000:
                break
            base = 1 / k ** 0.9
            buzz = 1.8 * np.exp(-((k - peak) / (2.2 + 0.08 * k)) ** 2)
            dec = np.exp(-t / (ring / (1 + 0.06 * k)))
            x += base * (0.35 + buzz) * dec * np.sin(k * ph + me.uniform(0, 6.28))
        taraf = sum(g * np.sin(2 * np.pi * f0 * r * t + me.uniform(0, 6.28)) for r, g in ((1, 0.10), (2, 0.06), (1.5, 0.05), (3, 0.03)))
        taraf = taraf * np.clip((t - 0.05) / 0.4, 0, 1) * np.exp(-t / (ring * 1.6))
        click = bandpass(me.standard_normal(n), 2000, 9000) * np.exp(-t / 0.004) * 0.6
        x = x / (np.abs(x).max() + 1e-9) + taraf + click
        x = x + 0.25 * bandpass(x, 250, 600) + 0.2 * bandpass(x, 2200, 4000)   # the gourd and the bridge
    elif R.get("vibes"):
        # bars: a strong fundamental and the bar's own partials (4x and ~10x), the higher ones dying fast; the
        # motor's tremolo pulses the loudness; a soft mallet thump; long ring
        ring = 4.5 - 2.0 * hi
        x = (np.sin(2 * np.pi * f0 * t) * np.exp(-t / ring)
             + 0.30 * np.sin(2 * np.pi * f0 * 3.98 * t + 1.0) * np.exp(-t / (ring * 0.18))
             + 0.10 * np.sin(2 * np.pi * f0 * 9.9 * t + 2.0) * np.exp(-t / 0.08)) * (1 + wow * 0)
        motor = 1 - 0.35 * (0.5 + 0.5 * np.sin(2 * np.pi * 5.6 * t + p3))
        thump = bandpass(me.standard_normal(n), 200, 1500) * np.exp(-t / 0.012) * 0.15
        x = x * motor * np.clip(t / 0.003, 0, 1) + thump
    else:
        bright_follow = R["bright_follow"]
        att = R["attack"] * (1 + 0.4 * (1 - hi)) * (0.85 + 0.3 * me.random())
        env = np.clip(t / att, 0, 1) ** 1.6
        swell = 0.85 + 0.15 * np.clip(t / (0.8 + 0.6 * me.random()), 0, 1) - 0.06 * np.clip((t - 5.0) / 3.0, 0, 1)
        vr, vc, vs = R["vib"]
        vph = 2 * np.pi * np.cumsum((vr + 0.2 * me.normal()) * (1 + 0.05 * smooth_noise(n, 0.5, me))) / RATE + p1
        vdepth = vc * (1 + 0.15 * me.normal()) * np.clip((t - vs - 0.3 * me.random()) / 0.8, 0, 1)
        lip = R.get("lip", -8)
        start_c = lip * (1 + 0.3 * me.random()) * np.exp(-t / (0.03 + 0.02 * me.random()))
        drift = 2.0 * smooth_noise(n, 0.7, me)
        cents = start_c + vdepth * np.sin(vph) + drift
        amp = env * swell * (1 + R["tremble"] * smooth_noise(n, 4.0, me)) * (1 + 0.15 * np.clip(vdepth / 20, 0, 1) * np.sin(vph - 0.5))
        if "hand_tremolo" in R:                     # the harmonica player's cupped hand opening and closing
            hr, hd, hs = R["hand_tremolo"]
            amp = amp * (1 - hd * np.clip((t - hs) / 0.6, 0, 1) * (0.5 + 0.5 * np.sin(2 * np.pi * hr * t + p2)))
        bright = np.clip(amp, 0, 1) ** 1.3 * bright_follow
        reeds = R.get("reeds", (0.0,))
        x = np.zeros(n)
        players = [(rc, 1.0) for rc in reeds] + ([(-1200.0 + me.normal(0, 4), R["octave_below"])] if R.get("octave_below") else [])
        for ri, (rc, pg) in enumerate(players):      # the accordion's reeds / a brass section's players, a few cents apart
            late = R.get("spread", 0.0) * ri * (0.5 + me.random())
            pl_env = np.clip((t - late) / max(att, 1e-3), 0, 1) ** 1.6 if late else 1.0
            phase = 2 * np.pi * np.cumsum(f0 * 2 ** ((cents + rc) / 1200) * (1 + wow)) / RATE
            for k in range(1, 40):
                if k * f0 > R["tone_top"]:
                    break
                a = float(np.mean(R["amps"](k, hi, 0.5))) if bright_follow == 0 else None
                ak = R["amps"](k, hi, bright) if a is None else a
                x += pg * pl_env * ak * np.sin(k * phase + me.uniform(0, 6.28)) * (1 + 0.03 * k * smooth_noise(n, 10 + 3 * k, me)) / len(players)
        x = x / (np.abs(x).max() + 1e-9) * amp
        na, nlo, nhi, burst = R["noise"]
        if na > 0:
            nz = bandpass(me.standard_normal(n), nlo, nhi)
            nz /= np.abs(nz).max() + 1e-9
            x = x + na * nz * (0.4 + 0.6 * amp) + burst * nz * np.exp(-t / 0.04) * np.clip(t / 0.005, 0, 1)
        x /= np.abs(x).max() + 1e-9
        for lo, hi_hz, g in R["body"]:
            x = x + g * bandpass(x, lo, hi_hz)
        b, a = butter(2, max(f0 * (0.3 if R.get("octave_below") else 0.55), 35) / (RATE / 2), btype="high")   # (a section keeps its trombone)
        x = lfilter(b, a, x)
    # the Mellotron tape
    x = np.tanh(1.5 * x / (np.abs(x).max() + 1e-9)) / np.tanh(1.5)
    x = lowpass(x, 9000, 2)
    out_tail = np.clip((SECONDS - t) / 0.35, 0, 1)
    gain = 10 ** ((R["level"] - 2.5 * (1 - hi) + me.normal(0, 0.5)) / 20)
    return x / (np.abs(x).max() + 1e-9) * 0.7 * gain * out_tail

if __name__ == "__main__":
    names = sys.argv[1:] or list(RECIPES)
    for name in names:
        out = os.path.join(ROOT, name)
        os.makedirs(out, exist_ok=True)
        for midi in range(43, 78):
            sf.write(os.path.join(out, f"{midi:03d}.wav"), note(name, midi).astype(np.float32), RATE, subtype="PCM_16")
        print("wrote", name)
