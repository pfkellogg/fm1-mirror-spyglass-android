package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A piano built in ("Piano (built in)" under TO): for a keyboard to play when there's no synth to send to, out of
 * this device's speakers (on a Fire TV, the TV's). The Salamander Grand Piano (Alexander Holm, CC BY 3.0), 30 notes
 * three semitones apart from A0 to C8 (assets/piano, the first 6 s, mono): each note played is the nearest one,
 * pitched up or down, as loud as it was hit; the sustain pedal holds, a key let go fades out. Up to 32 at once.
 */
final class Synth {
    private static final int RATE = 44100, VOICES = 32, BLOCK = 256;
    private static final String[] NAMES = {"C", "Ds", "Fs", "A"};

    /** A sample, by the note it is (MIDI number). Filled in the background: until then notes are silent. */
    private final short[][] samples = new short[128][];
    private volatile boolean loaded;
    private final List<Voice> voices = new ArrayList<>();
    private boolean sustain;
    private volatile boolean stop;
    private Thread player;
    private int status;   // running status, for messages that come in pieces

    private static final class Voice {
        final int note;
        final short[] data;
        final double step;
        final float gain;
        double pos;
        float env = 1f;
        boolean held = true, released;

        Voice(int note, short[] data, double step, float gain) {
            this.note = note;
            this.data = data;
            this.step = step;
            this.gain = gain;
        }
    }

    /** Loads the samples and starts playing (both on threads of their own). */
    void start(Context ctx) {
        if (player != null) return;
        Context app = ctx.getApplicationContext();
        Thread load = new Thread(() -> load(app), "Piano samples");
        load.setDaemon(true);
        load.start();
        player = new Thread(this::play, "Piano");
        player.setDaemon(true);
        player.start();
    }

    void stop() {
        stop = true;
    }

    // ---- the samples

    private void load(Context ctx) {
        for (int octave = 0; octave <= 8; octave++) {
            for (int k = 0; k < NAMES.length; k++) {
                int note = 12 * (octave + 1) + k * 3;      // C1 = 24, Ds1 = 27, Fs1 = 30, A0 = 21
                if (note < 21 || note > 108) continue;
                String file = "piano/" + NAMES[k] + octave + ".mp3";
                short[] s = decode(ctx, file);
                if (s != null) samples[note] = s;
            }
        }
        loaded = true;
        Log.i("FM1Mirror", "piano: samples loaded");
    }

    /** An mp3 in the assets, as mono 16-bit samples at RATE (the files are mono, 44.1 kHz). */
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
            ShortBuffer out = ShortBuffer.allocate(RATE * 7);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (!inDone) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer b = codec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
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
            Log.w("FM1Mirror", "piano: " + asset, e);
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

    // ---- MIDI in

    /** Whole or partial MIDI messages, from a keyboard's thread. */
    synchronized void midi(byte[] m) {
        int d1 = -1;
        for (byte x : m) {
            int b = x & 0xff;
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
            event(type, d1, b);
            d1 = -1;
        }
    }

    private void event(int type, int a, int b) {
        if (type == 0x90 && b > 0) noteOn(a, b);
        else if (type == 0x80 || type == 0x90) noteOff(a);
        else if (type == 0xb0 && a == 64) {
            sustain = b >= 64;
            if (!sustain) for (Voice v : voices) if (!v.held) v.released = true;
        } else if (type == 0xb0 && (a == 123 || a == 120)) {
            for (Voice v : voices) v.released = true;
        }
    }

    private void noteOn(int note, int vel) {
        if (!loaded) return;
        int root = -1;
        for (int d = 0; d < 128 && root < 0; d++) {
            if (note - d >= 0 && samples[note - d] != null) root = note - d;
            else if (note + d < 128 && samples[note + d] != null) root = note + d;
        }
        if (root < 0) return;
        for (Voice v : voices) if (v.note == note) v.released = true;    // the same key again: the old one fades
        if (voices.size() >= VOICES) voices.remove(0);                    // the oldest goes
        float gain = (float) Math.pow(vel / 127.0, 1.6) * 0.55f;
        voices.add(new Voice(note, samples[root], Math.pow(2, (note - root) / 12.0), gain));
    }

    private void noteOff(int note) {
        for (Voice v : voices) {
            if (v.note != note || !v.held) continue;
            v.held = false;
            if (!sustain) v.released = true;
        }
    }

    // ---- out

    private void play() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, BLOCK * 4), AudioTrack.MODE_STREAM);
        track.play();
        float[] mix = new float[BLOCK];
        short[] out = new short[BLOCK];
        float fade = (float) Math.pow(0.001, 1.0 / (0.35 * RATE));   // a let-go key: gone in about a third of a second
        while (!stop) {
            java.util.Arrays.fill(mix, 0f);
            synchronized (this) {
                for (int i = voices.size() - 1; i >= 0; i--) {
                    Voice v = voices.get(i);
                    for (int k = 0; k < BLOCK; k++) {
                        int p = (int) v.pos;
                        if (p + 1 >= v.data.length || v.env < 0.0005f) {
                            v.env = 0;
                            break;
                        }
                        float f = (float) (v.pos - p);
                        float s = v.data[p] * (1 - f) + v.data[p + 1] * f;
                        mix[k] += s * v.gain * v.env;
                        v.pos += v.step;
                        if (v.released) v.env *= fade;
                    }
                    if (v.env == 0) voices.remove(i);
                }
            }
            for (int k = 0; k < BLOCK; k++) {
                float x = mix[k] / 32768f;
                x = x / (1 + Math.abs(x));                              // soft, never clips
                out[k] = (short) (x * 32767);
            }
            track.write(out, 0, BLOCK);
        }
        track.stop();
        track.release();
    }
}
