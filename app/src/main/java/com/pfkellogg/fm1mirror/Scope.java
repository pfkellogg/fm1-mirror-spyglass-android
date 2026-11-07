package com.pfkellogg.fm1mirror;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.Locale;

/**
 * The FM-1's real sound, for the spyglass's waveform (Synth Spyglass's scope.rs on the Mac).
 * The FM-1 sends its audio back over its USB cable, and Android lists it as a USB input.
 * Whatever voice the FM-1 is set to, this is what it actually plays, so the waveform is always
 * right (it can't be worked out from MIDI: the FM-1 never says which voice it's on).
 * Needs RECORD_AUDIO.
 */
class Scope {
    /** Its USB name has one of these in it ("FM-1 Audio"; "USB-Audio", from the Jieli chip, with Open firmware). */
    private static final String[] HINTS = {"fm-1", "fm1", "jieli", "usb-audio", "usb composite", "felucca", "melodee", "choralroot", "jiant"};
    private static final int RATE = 48000;
    /** Samples kept: plenty for the window plus a period to find the trigger in. */
    private static final int KEEP = 8192;
    /** How much of the sound is shown: the same for every note, so a note an octave up shows twice as many waves. */
    private static final float WINDOW = 0.02f;
    /** No sound for this long: it has gone. */
    private static final long STALE_MS = 1000;

    private final float[] ring = new float[KEEP];
    private int head;  // next write
    private long filled, lastAt;
    private volatile boolean stop, dead;
    private Thread thread;
    private java.io.File wav;

    /** The FM-1's USB audio input, or null when it isn't plugged in. */
    static AudioDeviceInfo findDevice(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        // For trying it out without an FM-1 (an emulator): any input will do while this file
        // exists in the app's folder (Android/data/<app>/files, reachable with adb).
        boolean any = new java.io.File(ctx.getExternalFilesDir(null), "scope-any-input").exists();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (any) return d;
            int t = d.getType();
            if (t != AudioDeviceInfo.TYPE_USB_DEVICE && t != AudioDeviceInfo.TYPE_USB_HEADSET) continue;
            String n = String.valueOf(d.getProductName()).toLowerCase(Locale.US);
            for (String h : HINTS) if (n.contains(h)) return d;
        }
        return null;
    }

    static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts listening, on a thread of its own; null if it isn't plugged in or allowed. */
    static Scope open(Context ctx) {
        AudioDeviceInfo dev = findDevice(ctx);
        if (dev == null || !allowed(ctx)) return null;
        Scope s = new Scope();
        // For making videos: while a file named "save-sound" is in the app's folder
        // (Android/data/<app>/files, reachable with adb), what it
        // hears is also saved there, as fm1-<time>.wav (the same sound the waveform shows).
        if (new java.io.File(ctx.getExternalFilesDir(null), "save-sound").exists())
            s.wav = new java.io.File(ctx.getExternalFilesDir(null), "fm1-" + System.currentTimeMillis() + ".wav");
        s.thread = new Thread(() -> s.listen(dev), "FM-1 sound");
        s.thread.start();
        return s;
    }

    @SuppressWarnings("MissingPermission")  // checked in open()
    private void listen(AudioDeviceInfo dev) {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // UNPROCESSED where there is one: no automatic gain or noise cleaning bending the wave.
            int source = MediaRecorder.AudioSource.UNPROCESSED;
            try {
                rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
            } catch (RuntimeException e) {
                rec = null;
            }
            if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                if (rec != null) rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
            }
            rec.setPreferredDevice(dev);
            rec.startRecording();
            short[] buf = new short[1024];
            java.io.RandomAccessFile out = null;
            if (wav != null) {
                out = new java.io.RandomAccessFile(wav, "rw");
                out.write(new byte[44]);  // the header, written at the end
            }
            byte[] bytes = new byte[buf.length * 2];
            while (!stop) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) break;
                if (out != null && n > 0) {
                    for (int i = 0; i < n; i++) {
                        bytes[2 * i] = (byte) buf[i];
                        bytes[2 * i + 1] = (byte) (buf[i] >> 8);
                    }
                    out.write(bytes, 0, 2 * n);
                    if ((out.length() & 0x3ffff) < 2 * n) finishWav(out);  // every few seconds, so it's always playable
                }
                synchronized (this) {
                    for (int i = 0; i < n; i++) {
                        ring[head] = buf[i] / 32768f;
                        head = (head + 1) % KEEP;
                    }
                    filled += n;
                    if (n > 0) lastAt = System.currentTimeMillis();
                }
            }
            if (out != null) {
                finishWav(out);
                out.close();
            }
        } catch (RuntimeException | java.io.IOException e) {
            Log.w("FM1Mirror", "FM-1 sound", e);
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
            }
            dead = true;  // unplugged or stopped: the spyglass looks again later
        }
    }

    /** Fills in a WAV header (16-bit mono at RATE) for what's been written so far. */
    private static void finishWav(java.io.RandomAccessFile f) throws java.io.IOException {
        long data = f.length() - 44;
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt((int) data);
        long at = f.getFilePointer();
        f.seek(0);
        f.write(h.array());
        f.seek(at);
    }

    /** Hearing it now. */
    synchronized boolean listening() {
        return !dead && System.currentTimeMillis() - lastAt < STALE_MS;
    }

    boolean isDead() {
        return dead;
    }

    void close() {
        stop = true;
    }

    /**
     * The last WINDOW of the sound, starting where the wave crosses zero going up (searched
     * within one period at `hz`, the note played), so it stands still like an oscilloscope's.
     * Scaled so the loudest point is ±1; null while it's silent.
     */
    synchronized float[] wave(double hz) {
        if (!listening()) return null;
        int span = Math.max(32, Math.min(KEEP / 2, Math.round(WINDOW * RATE)));
        if (filled < span * 2L) return null;
        int period = (int) (RATE / Math.max(30.0, hz));
        // Oldest first: s(i) for i in 0..KEEP-1.
        int lastStart = KEEP - span, first = Math.max(1, lastStart - period - 1), start = lastStart;
        for (int i = lastStart; i >= first; i--) {
            if (at(i - 1) < 0 && at(i) >= 0) {
                start = i;
                break;
            }
        }
        float[] w = new float[span];
        float peak = 0;
        for (int i = 0; i < span; i++) {
            w[i] = at(start + i);
            peak = Math.max(peak, Math.abs(w[i]));
        }
        if (peak < 0.002f) return null;
        for (int i = 0; i < span; i++) w[i] /= peak;
        return w;
    }

    private float at(int i) {
        return ring[(head + i) % KEEP];
    }
}
