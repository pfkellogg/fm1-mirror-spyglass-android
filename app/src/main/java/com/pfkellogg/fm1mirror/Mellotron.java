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
 * The sounds are in assets/mellotron/SET/043.flac ... 077.flac (mono, 44.1 kHz): "flute" is made from scratch by
 * tools/make_mellotron_flute.py (no recordings, so nothing anyone else owns).
 */
final class Mellotron {
    private static final int RATE = 44100, VOICES = 16, BLOCK = 256, LOW = 43, HIGH = 77;
    private static final String TAG = "FM1Mirror";

    private final short[][] strips = new short[HIGH + 1][];
    private volatile boolean loaded, stop;
    private final List<Voice> voices = new ArrayList<>();
    private Thread player;
    private int status, d1 = -1;
    private final java.util.Random random = new java.util.Random();
    /** How much the tape machine wobbles, as a share of the pitch (0.0015 = about 2.6 cents). */
    private static final double WOW = 0.0015, FLUTTER = 0.0004;

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
    void start(Context ctx, String set) {
        if (player != null) return;
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
    }

    // ---- the strips

    private void load(Context ctx, String set) {
        int n = 0;
        for (int key = LOW; key <= HIGH; key++) {
            short[] s = decode(ctx, String.format(java.util.Locale.US, "mellotron/%s/%03d.flac", set, key));
            if (s != null) {
                strips[key] = s;
                n++;
            }
        }
        loaded = true;
        Log.i(TAG, "mellotron: " + n + " strips of '" + set + "' loaded");
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
            while (!outDone) {
                if (!inDone) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
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
                }
                int o = codec.dequeueOutputBuffer(info, 10000);
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

    /** Whole or partial MIDI messages, from the FM-1's thread; SysEx (its screen) is skipped. */
    synchronized void midi(byte[] m, int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            int b = m[i] & 0xff;
            if (b >= 0xf8) continue;
            if (b >= 0x80) {
                status = b < 0xf0 ? b : 0;
                d1 = -1;
                continue;
            }
            if (status == 0) continue;
            int type = status & 0xf0;
            if (type == 0xc0 || type == 0xd0) continue;
            if (d1 < 0) {
                d1 = b;
                continue;
            }
            if (type == 0x90 && b > 0) noteOn(d1);
            else if (type == 0x80 || type == 0x90) noteOff(d1);
            else if (type == 0xb0 && (d1 == 120 || d1 == 123)) allOff();
            d1 = -1;
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

    private AudioTrack track(Context ctx) {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, BLOCK * 4);
        AudioTrack t;
        if (Build.VERSION.SDK_INT >= 26) {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        } else {
            t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        }
        AudioDeviceInfo own = ownOutput(ctx);
        if (own != null) t.setPreferredDevice(own);
        return t;
    }

    private void play(Context ctx) {
        AudioTrack track = track(ctx);
        track.play();
        AudioDeviceInfo was = ownOutput(ctx);
        long nextCheck = 0;
        float[] mix = new float[BLOCK];
        short[] out = new short[BLOCK];
        float attack = 1f / (0.004f * RATE);                                 // the tape meets the head: 4 ms
        float fade = (float) Math.pow(0.001, 1.0 / (0.12 * RATE));          // let go: the head lifts, gone in 0.12 s
        double dt = 1.0 / RATE;
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
            java.util.Arrays.fill(mix, 0f);
            synchronized (this) {
                for (int i = voices.size() - 1; i >= 0; i--) {
                    Voice v = voices.get(i);
                    boolean done = false;
                    for (int k = 0; k < BLOCK; k++) {
                        int p = (int) v.pos;
                        if (p + 1 >= v.data.length) {
                            done = true;
                            break;
                        }
                        if (v.released) {
                            v.env *= fade;
                            if (v.env < 0.0005f) {
                                done = true;
                                break;
                            }
                        } else if (v.env < v.target) {
                            v.env = Math.min(v.target, v.env + attack);
                        }
                        float f = (float) (v.pos - p);
                        mix[k] += (v.data[p] * (1 - f) + v.data[p + 1] * f) * v.env;
                        double wobble = WOW * Math.sin(2 * Math.PI * 0.55 * v.t + v.wowPhase)
                                + FLUTTER * Math.sin(2 * Math.PI * 6.5 * v.t + v.flutterPhase);
                        v.pos += 1 + wobble;
                        v.t += dt;
                    }
                    if (done) voices.remove(i);
                }
            }
            for (int k = 0; k < BLOCK; k++) {
                float x = mix[k] * 0.45f / 32768f;
                x = x / (1 + Math.abs(x));                              // soft, never clips
                out[k] = (short) (x * 32767);
            }
            track.write(out, 0, BLOCK);
        }
        track.stop();
        track.release();
    }
}
