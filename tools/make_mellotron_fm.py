# make_mellotron_fm.py: five frequency-modulation (FM) sounds, 35 strips each, G2 (MIDI 43) to F5 (77), 8 s, like the
# Mellotron sounds. Two are FM from scratch, sine operators as in the 1980s FM synths:
#   fmepiano    FM Electric Piano: a 1:1 pair (the warm body, index 2.2 dying to 0.5) and a 1:14 pair (the "tine",
#               a quick metallic tick at the strike); lows ring longer than highs.
#   fmtubular   FM Tubular Bells: carrier 1, modulator 3.5 (inharmonic: a bell), index 5 dying away, plus a quiet 1:1.4
#               pair for the hum; a long ring.
# Three are an existing sound frequency-modulated by another: the carrier strip is read through a delay that the
# modulator wobbles at audio rate (phase modulation: each partial f swings by index * f / 1000 radians), so the
# carrier keeps its own voice and gains sidebands:
#   glasschoir     Glass Choir: the Choir modulated by the Tibetan Bells (index 0.8 dying to 0.15): a glassy halo.
#   crystalstrings Crystal Strings: the Strings modulated by the Vibraphone (1.0 at the attack, to 0.1): a sparkling edge.
#   glassharmonica Glass Harmonica: the Flute modulated lightly by the Vibraphone (0.35, steady): wet glass.
# The carriers and modulators are the published strips (fm1-sounds/sounds/<id>/043.flac ...), so these follow them.
#   python tools/make_mellotron_fm.py [SOUNDS_DIR] [names...]   (SOUNDS_DIR: fm1-sounds/sounds, the default beside this repo)
# Written as 16-bit mono WAV at 44100 Hz to samples/<name>/043.wav ... 077.wav.
import os, sys, numpy as np, soundfile as sf

RATE, SECONDS = 44100, 8.0
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "samples")
SOUNDS = os.path.join(HERE, "..", "..", "fm1-sounds", "sounds")
N = int(RATE * SECONDS)
T = np.arange(N) / RATE
END = np.where(T < 4.5, 1.0, 0.5 + 0.5 * np.cos(np.pi * np.clip((T - 4.5) / 3.5, 0, 1)))   # gone before 8 s

def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)

def tape(seed):
    r = np.random.default_rng(seed)
    p1, p2 = r.uniform(0, 6.28, 2)
    wow = 0.0008 * np.sin(2 * np.pi * 0.55 * T + p1) + 0.0003 * np.sin(2 * np.pi * 7.5 * T + p2)
    return np.cumsum(1 + wow) / RATE

def op(f, warp, mod=0.0):
    return np.sin(2 * np.pi * f * warp + mod)

def fmepiano(midi):
    f, w = hz(midi), tape(500 + midi)
    hi = (midi - 43) / 34
    amp = np.exp(-T / (3.2 - 1.8 * hi))
    body = op(f, w, (0.5 + 1.7 * np.exp(-T / 0.9)) * op(f, w))
    tine = op(f, w, 1.6 * np.exp(-T / 0.06) * op(14 * f, w))
    y = amp * (0.8 * body + 0.35 * np.exp(-T / 0.4) * tine) + 0.08 * np.exp(-T / 1.5) * op(2 * f, w)
    return y * np.minimum(1, T / 0.002) * END

def fmtubular(midi):
    f, w = hz(midi), tape(600 + midi)
    bell = op(f, w, 5.0 * np.exp(-T / 1.6) * op(3.5 * f, w))
    hum = op(0.5 * f, w, 0.8 * np.exp(-T / 3) * op(0.7 * f, w))
    y = np.exp(-T / 3.5) * bell + 0.4 * np.exp(-T / 5) * hum
    return y * np.minimum(1, T / 0.001) * END

def read(sound, midi):
    x, sr = sf.read(os.path.join(SOUNDS, sound, f"{midi:03d}.flac"), always_2d=True)
    x = x.mean(1)
    assert sr == RATE, (sound, sr)
    return np.pad(x, (0, max(0, N - len(x))))[:N]

def modulated(carrier, modulator, index, midi):
    c, m = read(carrier, midi), read(modulator, midi)
    m = m / (np.abs(m).max() + 1e-9)
    delay = index * m / (2 * np.pi * 1000.0)                      # seconds: partial f swings by index * f / 1000 rad
    return np.interp(T - delay - 0.002, T, c, left=0.0)            # (a 2 ms base delay; held like its carrier, no fade)

RECIPES = {
    "fmepiano": ("FM Electric Piano", fmepiano),
    "fmtubular": ("FM Tubular Bells", fmtubular),
    "glasschoir": ("Glass Choir", lambda m: modulated("choir", "tibetanbells", 0.15 + 0.65 * np.exp(-T / 1.5), m)),
    "crystalstrings": ("Crystal Strings", lambda m: modulated("strings", "vibraphone", 0.1 + 0.9 * np.exp(-T / 0.5), m)),
    "glassharmonica": ("Glass Harmonica", lambda m: modulated("flute", "vibraphone", 0.35, m)),
}

if __name__ == "__main__":
    args = sys.argv[1:]
    if args and os.path.isdir(args[0]):
        SOUNDS = args.pop(0)
    for name in args or list(RECIPES):
        out = os.path.join(ROOT, name)
        os.makedirs(out, exist_ok=True)
        strips = {m: RECIPES[name][1](m) for m in range(43, 78)}
        peak = max(np.abs(s).max() for s in strips.values())
        for m, s in strips.items():                                # one gain for all: the keys keep their balance
            sf.write(os.path.join(out, f"{m:03d}.wav"), (s / peak * 0.8).astype(np.float32), RATE, subtype="PCM_16")
        print(name, "->", os.path.abspath(out))
