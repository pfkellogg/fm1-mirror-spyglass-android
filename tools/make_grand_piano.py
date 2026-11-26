# make_grand_piano.py: the Grand Piano sound, 35 strips (G2..F5, MIDI 43-77, 8 s each) like the Mellotron sounds,
# made from the Salamander Grand Piano (a Yamaha C5 recorded by Alexander Holm, CC-BY 3.0:
# https://github.com/sfzinstruments/SalamanderGrandPiano). One loudness layer (v12 of 16: strong, not hard); each key
# from its nearest recorded note (recorded every 3 semitones), re-pitched at most 1.5 semitones; mono; a gentle fade at
# the end of 8 s; the recordings' own balance kept (one gain for all). No tape effect: a grand should sound like one.
#   python tools/make_grand_piano.py SAMPLES_DIR      (SAMPLES_DIR holds A2v12.flac, C3v12.flac, D#3v12.flac, ...)
# Written as 16-bit mono WAV at 44100 Hz to samples/piano/043.wav ... 077.wav.
import os, sys, numpy as np, soundfile as sf
from scipy.signal import resample_poly

RATE, SECONDS = 44100, 8.0
OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "piano")
NOTE = {"C": 0, "D#": 3, "F#": 6, "A": 9}

def roots(d):
    r = {}
    for f in os.listdir(d):
        if f.endswith("v12.flac"):
            name = f[:-len("v12.flac")]
            pc, octv = name[:-1], int(name[-1])
            r[12 * (octv + 1) + NOTE[pc]] = os.path.join(d, f)
    return r

def strip(src_path, shift):
    x, sr = sf.read(src_path, always_2d=True)
    x = x.mean(1)
    # re-pitch: play it back faster or slower (like a sampler), then to 44.1 kHz
    ratio = 2 ** (shift / 12)
    n_out = int(len(x) / ratio)
    t = np.arange(n_out) * ratio
    x = np.interp(t, np.arange(len(x)), x)
    x = resample_poly(x, RATE, sr) if sr != RATE else x
    n = int(RATE * SECONDS)
    x = np.pad(x, (0, max(0, n - len(x))))[:n]
    tt = np.arange(n) / RATE
    x *= np.clip((SECONDS - tt) / 0.6, 0, 1)                # a gentle fade where the strip ends
    return x

if __name__ == "__main__":
    src = sys.argv[1]
    R = roots(src)
    os.makedirs(OUT, exist_ok=True)
    strips = {}
    for midi in range(43, 78):
        root = min(R, key=lambda r: (abs(r - midi), r))     # the nearest recorded note
        strips[midi] = strip(R[root], midi - root)
    peak = max(np.abs(s).max() for s in strips.values())
    for midi, s in strips.items():
        sf.write(os.path.join(OUT, f"{midi:03d}.wav"), (s / peak * 0.85).astype(np.float32), RATE, subtype="PCM_16")
    print("wrote 35 grand piano strips from", len(R), "recorded notes to", os.path.abspath(OUT))
