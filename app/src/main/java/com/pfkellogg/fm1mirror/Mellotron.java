package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A Mellotron built in (the "Mellotron" button): the FM-1's own keys play tape strips out of this device's own
 * sound (headphone jack, speaker, or a TV's HDMI), never back over USB to the FM-1 (some tablets' USB audio clicks).
 * Like the real one: one recording per key over 35 keys, G2 (43) to F5 (77), each 8 seconds long, which simply runs
 * out if held longer; no velocity; a key let go stops its strip quickly. Notes outside the 35 keys are moved by
 * octaves into them. On top, the tape machine's own slow wow and fast flutter, a little different for each strip.
 * The sounds are in assets/mellotron/SET/043.flac ... 077.flac (mono, 44.1 kHz), all made from scratch (no recordings,
 * so nothing anyone else owns): "flute" by tools/make_mellotron_flute.py, "strings" and "choir" by
 * tools/make_mellotron_sounds.py.
 */
final class Mellotron {
    private static final int RATE = 44100, VOICES = 16, BLOCK = 256, LOW = 43, HIGH = 77;
    private static final String TAG = "FM1Mirror";

    /** The Mellotron playing now (null: off), for the spyglass: its name and its sound's last moment. */
    static volatile Mellotron active;
    final String sound;
    /** The last moment of what it played (oldest first from `head`), for the spyglass's waveform. */
    private static final int KEEP = 8192;
    private final float[] ring = new float[KEEP];
    private int head;
    private long heardAt;
    private volatile int ringRate = RATE;
    static boolean DIAG;
    private long diagMix, diagWrite, diagAt;
    private int diagVoices;

    Mellotron(String sound) {
        this.sound = sound;
    }

    private final short[][] strips = new short[HIGH + 1][];
    private volatile boolean loaded, stop;
    private final List<Voice> voices = new ArrayList<>();
    private Thread player;
    private final java.util.Random random = new java.util.Random();
    /** How much the tape machine wobbles, as a share of the pitch (0.0015 = about 2.6 cents). */
    // (1.6.8-1.6.9 added a tape wow and flutter of their own here, on top of the strips' recorded wobble: together,
    // seasick on fast notes. The strips carry it now.)

    private static final class Voice {
        final int key;
        final short[] data;
        final double wowPhase, flutterPhase;
        double pos, t;
        float env, target = 1f;
        boolean released;

        Voice(int key, short[] data, double wowPhase, double flutterPhase) {
            this.key = key;
            this.data = data;
            this.wowPhase = wowPhase;
            this.flutterPhase = flutterPhase;
        }
    }

    /** Loads the strips and starts the sound (each on a thread of its own). */
    void start(Context ctx) {
        String set = sound;
        if (player != null) return;
        active = this;
        Context app = ctx.getApplicationContext();
        Thread load = new Thread(() -> load(app, set), "Mellotron strips");
        load.setDaemon(true);
        load.start();
        player = new Thread(() -> play(app), "Mellotron");
        player.setDaemon(true);
        player.start();
    }

    void stop() {
        stop = true;
        if (active == this) active = null;
    }

    /** Sounding now (something louder than silence in the last quarter second). */
    synchronized boolean sounding() {
        return android.os.SystemClock.uptimeMillis() - heardAt < 250;
    }

    /**
     * The last 20 ms of its sound, from where it crosses zero going up (within one period at `hz`), so it stands still
     * like an oscilloscope's; the loudest point ±1; null while it's quiet.
     */
    synchronized float[] wave(double hz) {
        if (!sounding()) return null;
        int span = Math.round(0.02f * ringRate), period = (int) (ringRate / Math.max(30.0, hz));
        int last = KEEP - span, first = Math.max(1, last - period - 1), start = last;
        for (int i = last; i >= first; i--)
            if (ring[(head + i - 1) % KEEP] < 0 && ring[(head + i) % KEEP] >= 0) {
                start = i;
                break;
            }
        float[] w = new float[span];
        float peak = 0;
        for (int i = 0; i < span; i++) {
            w[i] = ring[(head + start + i) % KEEP];
            peak = Math.max(peak, Math.abs(w[i]));
        }
        if (peak < 0.002f) return null;
        for (int i = 0; i < span; i++) w[i] /= peak;
        return w;
    }

    // ---- the strips

    /** Sounds loaded before, kept while there's memory to spare (Android drops them when it needs it). */
    private static final java.util.Map<String, java.lang.ref.SoftReference<short[][]>> CACHE = new java.util.HashMap<>();

    /** The 35 strips, from the cache, else decoded four at a time (one by one took 20 s on a small tablet). */
    private void load(Context ctx, String set) {
        long t0 = android.os.SystemClock.uptimeMillis();
        short[][] got;
        synchronized (CACHE) {
            java.lang.ref.SoftReference<short[][]> r = CACHE.get(set);
            got = r == null ? null : r.get();
        }
        if (got == null) {
            short[][] all = new short[HIGH + 1][];
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            for (int key = LOW; key <= HIGH; key++) {
                final int k = key;
                pool.execute(() -> all[k] = strip(ctx, set, k));
            }
            pool.shutdown();
            try {
                pool.awaitTermination(2, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }
            got = all;
            synchronized (CACHE) {
                CACHE.put(set, new java.lang.ref.SoftReference<>(all));
            }
        }
        int n = 0;
        for (int key = LOW; key <= HIGH; key++) {
            if (got[key] != null) n++;
            strips[key] = got[key];
        }
        loaded = true;
        Log.i(TAG, "mellotron: " + n + " strips of '" + set + "' loaded in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
    }

    /**
     * One strip: from the app's cache folder when it was decoded before (a fraction of a second), else decoded from
     * the assets and kept there (a small tablet decodes FLAC slowly: a whole sound took half a minute).
     */
    private static short[] strip(Context ctx, String set, int key) {
        java.io.File f = new java.io.File(ctx.getCacheDir(), String.format(java.util.Locale.US, "mellotron/%s/%03d.pcm", set, key));
        if (f.length() > 0) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                ByteBuffer b = ByteBuffer.allocate((int) f.length()).order(ByteOrder.LITTLE_ENDIAN);
                in.getChannel().read(b);
                b.flip();
                short[] out = new short[b.remaining() / 2];
                b.asShortBuffer().get(out);
                return out;
            } catch (java.io.IOException e) {
                Log.w(TAG, "mellotron: cache " + f, e);
            }
        }
        short[] s = decode(ctx, String.format(java.util.Locale.US, "mellotron/%s/%03d.flac", set, key));
        if (s != null) {
            f.getParentFile().mkdirs();
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                ByteBuffer b = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                b.asShortBuffer().put(s);
                out.getChannel().write(b);
                out.close();
                if (!tmp.renameTo(f)) tmp.delete();
            } catch (java.io.IOException e) {
                tmp.delete();
            }
        }
        return s;
    }

    /** A sound file in the assets, as mono 16-bit samples (the strips are mono, 44.1 kHz). */
    private static short[] decode(Context ctx, String asset) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(asset);
            ex.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            ex.selectTrack(0);
            MediaFormat f = ex.getTrackFormat(0);
            int channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            codec.configure(f, null, null, 0);
            codec.start();
            ShortBuffer out = ShortBuffer.allocate(RATE * 9);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            // Feed every free input buffer without waiting, then take what's decoded (waiting up to 10 ms for each
            // piece, as before, made 35 strips take 20 to 40 s on a small tablet).
            while (!outDone) {
                int i;
                while (!inDone && (i = codec.dequeueInputBuffer(0)) >= 0) {
                    ByteBuffer b = codec.getInputBuffer(i);
                    int got = ex.readSampleData(b, 0);
                    if (got < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        codec.queueInputBuffer(i, 0, got, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 2000);
                if (o >= 0) {
                    ShortBuffer pcm = codec.getOutputBuffer(o).order(ByteOrder.nativeOrder()).asShortBuffer();
                    while (pcm.remaining() >= channels && out.hasRemaining()) {
                        int sum = 0;
                        for (int c = 0; c < channels; c++) sum += pcm.get();
                        out.put((short) (sum / channels));
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            short[] s = new short[out.position()];
            out.flip();
            out.get(s);
            return s;
        } catch (Exception e) {
            Log.w(TAG, "mellotron: " + asset, e);
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
            ex.release();
        }
    }

    // ---- MIDI in (the FM-1's keys)

    /** One source's running status (each source has its own: bytes of two sources mixed would make up notes). */
    private static final class Parser {
        int status, d1 = -1;
    }

    private final Parser fm1 = new Parser(), demo = new Parser();

    /** Whole or partial MIDI messages, from the FM-1's thread; SysEx (its screen) is skipped. */
    void midi(byte[] m, int offset, int count) {
        parse(fm1, m, offset, count);
    }

    /** Notes from the demo (debug builds), kept apart from the FM-1's stream: a demo note landing in the middle of one
     *  of the FM-1's screen messages turned the rest of it into stray notes and note-offs (cut-outs). */
    void demo(byte[] m) {
        parse(demo, m, 0, m.length);
    }

    private synchronized void parse(Parser p, byte[] m, int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            int b = m[i] & 0xff;
            if (b >= 0xf8) continue;
            if (b >= 0x80) {
                p.status = b < 0xf0 ? b : 0;
                p.d1 = -1;
                continue;
            }
            if (p.status == 0) continue;
            int type = p.status & 0xf0;
            if (type == 0xc0 || type == 0xd0) continue;
            if (p.d1 < 0) {
                p.d1 = b;
                continue;
            }
            if (type == 0x90 && b > 0) noteOn(p.d1);
            else if (type == 0x80 || type == 0x90) noteOff(p.d1);
            else if (type == 0xb0 && (p.d1 == 120 || p.d1 == 123)) allOff();
            p.d1 = -1;
        }
    }

    private static int fold(int note) {
        while (note < LOW) note += 12;
        while (note > HIGH) note -= 12;
        return note;
    }

    private void noteOn(int note) {
        if (!loaded) return;
        int key = fold(note);
        short[] s = strips[key];
        if (s == null) return;
        for (Voice v : voices) if (v.key == key) v.released = true;      // the same key again: its strip starts over
        if (voices.size() >= VOICES) voices.remove(0);
        voices.add(new Voice(key, s, random.nextDouble() * 2 * Math.PI, random.nextDouble() * 2 * Math.PI));
    }

    private void noteOff(int note) {
        int key = fold(note);
        for (Voice v : voices) if (v.key == key) v.released = true;
    }

    synchronized void allOff() {
        for (Voice v : voices) v.released = true;
    }

    // ---- out

    /** This device's own sound, best first: headphones or line out, then HDMI (a TV), then the speaker; never USB. */
    static AudioDeviceInfo ownOutput(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo best = null;
        int rank = 0;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType(), r = 0;
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_LINE_ANALOG) r = 4;
            else if (t == AudioDeviceInfo.TYPE_HDMI || t == AudioDeviceInfo.TYPE_HDMI_ARC) r = 3;
            else if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) r = 2;
            if (r > rank) {
                rank = r;
                best = d;
            }
        }
        return best;
    }

    /** This device's own output rate (48 kHz on most): played at it, the sound takes Android's quick path. Playing
     *  44.1 kHz on a 48 kHz device made Android convert it, on its slow path, and keys answered late. */
    static int outRate(Context ctx) {
        try {
            String r = ((AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE)).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            int n = r == null ? 0 : Integer.parseInt(r);
            return n >= 22050 && n <= 192000 ? n : RATE;
        } catch (RuntimeException e) {
            return RATE;
        }
    }

    private AudioTrack track(Context ctx, int out) {
        int min = AudioTrack.getMinBufferSize(out, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // four times the smallest: chords of 6 to 8 voices on a small tablet ran it dry while the screen drew (gaps)
        int size = Math.max(4 * min, BLOCK * 16);
        AudioTrack t;
        if (Build.VERSION.SDK_INT >= 26) {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(out)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        } else {
            t = new AudioTrack(AudioManager.STREAM_MUSIC, out, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        }
        AudioDeviceInfo own = ownOutput(ctx);
        if (own != null) t.setPreferredDevice(own);
        return t;
    }

    private void play(Context ctx) {
        // audio priority, as Android means for music: at normal priority strings and choir chords cut out
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int out_ = outRate(ctx);
        ringRate = out_;
        double step = (double) RATE / out_;                                  // the strips are 44.1 kHz
        AudioTrack track = track(ctx, out_);
        Log.i(TAG, "mellotron: playing at " + out_ + " Hz");
        track.play();
        AudioDeviceInfo was = ownOutput(ctx);
        long nextCheck = 0;
        float[] mix = new float[BLOCK];
        short[] out = new short[BLOCK];
        float attack = 1f / (0.004f * out_);                                 // the tape meets the head: 4 ms
        float fade = (float) Math.pow(0.001, 1.0 / (0.12 * out_));          // let go: the head lifts, gone in 0.12 s
        while (!stop) {
            // headphones plugged in or out: follow them (the system would send us to the FM-1's USB instead)
            long now = android.os.SystemClock.uptimeMillis();
            if (now > nextCheck) {
                nextCheck = now + 1000;
                AudioDeviceInfo own = ownOutput(ctx);
                if (own != null && (was == null || own.getId() != was.getId())) {
                    track.setPreferredDevice(own);
                    was = own;
                }
            }
            long w0 = System.nanoTime();
            java.util.Arrays.fill(mix, 0f);
            synchronized (this) {
                // lean on purpose: locals, no doubles per sample (the first version took 20 ms or more for a 5 ms
                // block with six voices on a small tablet, and the sound ran dry 25 times a second)
                for (int i = voices.size() - 1; i >= 0; i--) {
                    Voice v = voices.get(i);
                    final short[] d = v.data;
                    final int last = d.length - 1;
                    double pos = v.pos;
                    float env = v.env, target = v.target;
                    final boolean rel = v.released;
                    boolean done = false;
                    for (int k = 0; k < BLOCK; k++) {
                        int p = (int) pos;
                        if (p >= last) {
                            done = true;
                            break;
                        }
                        if (rel) {
                            env *= fade;
                            if (env < 0.0005f) {
                                done = true;
                                break;
                            }
                        } else if (env < target) {
                            env = Math.min(target, env + attack);
                        }
                        float f = (float) (pos - p), a = d[p];
                        mix[k] += (a + (d[p + 1] - a) * f) * env;
                        pos += step;
                    }
                    v.pos = pos;
                    v.env = env;
                    if (done) voices.remove(i);
                }
            }
            float loud = 0;
            for (int k = 0; k < BLOCK; k++) {
                // Headroom for chords, squeezed only near the top: before, every chord was squeezed (x / (1 + |x|)),
                // about 10% distortion with three notes, which buzzed like a lawn mower.
                float x = mix[k] * 0.3f / 32768f, a = Math.abs(x);
                if (a > 0.6f) x = Math.signum(x) * (0.6f + 0.4f * (float) Math.tanh((a - 0.6f) / 0.4f));
                out[k] = (short) (x * 32767);
                loud = Math.max(loud, Math.abs(x));
            }
            synchronized (this) {
                for (int k = 0; k < BLOCK; k++) {
                    ring[head] = out[k] / 32768f;
                    head = (head + 1) % KEEP;
                }
                if (loud > 0.002f) heardAt = android.os.SystemClock.uptimeMillis();
            }
            long w1 = System.nanoTime();
            track.write(out, 0, BLOCK);
            long w2 = System.nanoTime();
            // (diagnostics, debug builds: blocks that took long to mix, waits on write, underruns, once a second)
            if (DIAG) {
                diagMix = Math.max(diagMix, w1 - w0); diagWrite = Math.max(diagWrite, w2 - w1); diagVoices = Math.max(diagVoices, voices.size());
                if (now >= diagAt) {
                    int u = Build.VERSION.SDK_INT >= 24 ? track.getUnderrunCount() : -1;
                    Log.i(TAG, "mellotron diag: mix max " + diagMix / 1000 + " us, write max " + diagWrite / 1000 + " us, voices " + diagVoices + ", underruns " + u + ", buffer " + (Build.VERSION.SDK_INT >= 24 ? track.getBufferSizeInFrames() : -1));
                    diagAt = now + 1000; diagMix = diagWrite = 0; diagVoices = 0;
                }
            }
        }
        track.stop();
        track.release();
    }
}
