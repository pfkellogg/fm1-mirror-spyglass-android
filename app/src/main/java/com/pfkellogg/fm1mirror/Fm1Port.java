package com.pfkellogg.fm1mirror;

import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The FM-1's USB MIDI ports, open both ways; what it sends comes to `sink` one SysEx message at a time. */
final class Fm1Port implements Closeable {
    private static Handler callbacks;

    /** Android's MIDI callbacks come on a thread of their own, so opening (which waits for one) can't block them. */
    static synchronized Handler callbacks() {
        if (callbacks == null) {
            HandlerThread t = new HandlerThread("FM-1 MIDI");
            t.start();
            callbacks = new Handler(t.getLooper());
        }
        return callbacks;
    }

    final MidiDeviceInfo info;
    final String name;
    private final MidiDevice device;
    private final MidiInputPort in;
    private final MidiOutputPort out;

    private Fm1Port(MidiDeviceInfo info, MidiDevice device, MidiInputPort in, MidiOutputPort out) {
        this.info = info;
        this.name = name(info);
        this.device = device;
        this.in = in;
        this.out = out;
    }

    /** Android's name for it: maker and product, e.g. "Jieli Technology USB Composite Device". */
    static String name(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        String name = p.getString(MidiDeviceInfo.PROPERTY_NAME, "");
        String product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT, "");
        return name.contains(product) ? name : (name + " " + product).trim();
    }

    /** Devices that may be the FM-1 (or, with `updater`, its updater while it's working). */
    static List<MidiDeviceInfo> candidates(MidiManager mm, boolean updater) {
        List<MidiDeviceInfo> found = new ArrayList<>();
        for (MidiDeviceInfo i : mm.getDevices()) {
            String n = name(i);
            if (i.getInputPortCount() > 0 && i.getOutputPortCount() > 0 && Codec.isFm1(n) && (updater || !Codec.isUpdaterName(n)))
                found.add(i);
        }
        return found;
    }

    /** Null if it can't be opened (gone, or another app has its ports). Waits for Android, so never call it on the main thread. */
    static Fm1Port open(MidiManager mm, MidiDeviceInfo info, Codec.Sysex.Sink sink) {
        MidiDevice[] got = new MidiDevice[1];
        CountDownLatch opened = new CountDownLatch(1);
        mm.openDevice(info, d -> {
            got[0] = d;
            opened.countDown();
        }, callbacks());
        try {
            if (!opened.await(4, TimeUnit.SECONDS) || got[0] == null) return null;
        } catch (InterruptedException e) {
            return null;
        }
        MidiDevice d = got[0];
        MidiInputPort in = d.openInputPort(0);
        MidiOutputPort out = d.openOutputPort(0);
        if (in == null || out == null) {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(d);
            return null;
        }
        Codec.Sysex sysex = new Codec.Sysex(sink);
        out.connect(new MidiReceiver() {
            @Override
            public void onSend(byte[] data, int offset, int count, long timestamp) {
                sysex.feed(data, offset, count);
            }
        });
        return new Fm1Port(info, d, in, out);
    }

    synchronized void send(byte[] m) throws IOException {
        in.send(m, 0, m.length);
    }

    @Override
    public void close() {
        closeQuietly(out);
        closeQuietly(in);
        closeQuietly(device);
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
