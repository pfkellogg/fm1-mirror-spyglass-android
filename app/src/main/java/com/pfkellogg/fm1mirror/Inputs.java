package com.pfkellogg.fm1mirror;

import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (From MIDI Spyglass, for FM-1 Mirror + Spyglass: the FM-1 FM-1 Mirror has open is left to it, and "FM-1" under
 * TO plays it through FM-1 Mirror's link, `fm1Out`.)
 * The MIDI devices, in pairs as in the desktop app (and Synth Spyglass): a keyboard (FROM) and the synths it plays
 * (TO). Several pairs play at once; the spyglass shows one at a time. Every device that sends MIDI is opened to
 * listen (its own Played), so a pair's keyboard is shown as soon as it's picked; a device's MIDI in is opened only
 * when it's a TO (Android lets one app at a time have it); an FM-1 ticked under TO also gets the hello, about once a
 * second, that makes its Big Screen firmware say the sound playing ("T1 FM6 PIANO 1"). Devices that come and go are picked up as
 * they do.
 */
final class Inputs {
    /** A pair: a keyboard ("" is the first keyboard) and the devices it plays. */
    static final class Route {
        String from = "";
        final Set<String> to = new TreeSet<>();
    }

    final List<Route> routes = new ArrayList<>();

    /** The FM-1 under TO: FM-1 Mirror's own link to it (it has the FM-1 open for its screen). */
    static final String FM1 = "FM-1";
    /** Where FM-1 Mirror's link sends (null while there's no FM-1), and the device it has open (skipped here). */
    volatile java.util.function.Consumer<byte[]> fm1Out;
    volatile int fm1Id = -1;

    private final MidiManager mm;
    private final Runnable changed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Dev> open = new HashMap<>();
    /** The TO devices' MIDI ins open now, by name (read from MIDI threads). */
    private final Map<String, MidiInputPort> sinks = new ConcurrentHashMap<>();

    /** One open device: what's played on it, and where it goes (null: it isn't a pair's keyboard). */
    private final class Dev implements Closeable {
        final MidiDeviceInfo info;
        final String name;
        final MidiDevice device;
        final Played played = new Played();
        final List<MidiOutputPort> ports = new ArrayList<>();
        volatile Set<String> targets;
        MidiInputPort in;

        Dev(MidiDeviceInfo info, MidiDevice device) {
            this.info = info;
            this.name = name(info);
            this.device = device;
        }

        void closeIn() {
            if (in == null) return;
            sinks.remove(name, in);
            notesOff(in);
            try {
                in.close();
            } catch (IOException ignored) {
            }
            in = null;
        }

        @Override
        public void close() {
            closeIn();
            for (MidiOutputPort p : ports) {
                try {
                    p.close();
                } catch (IOException ignored) {
                }
            }
            try {
                device.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final MidiManager.DeviceCallback watch = new MidiManager.DeviceCallback() {
        @Override
        public void onDeviceAdded(MidiDeviceInfo info) {
            consider(info);
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo info) {
            Dev d = open.remove(info.getId());
            if (d != null) d.close();
            apply();
        }
    };

    Inputs(MidiManager mm, Runnable changed) {
        this.mm = mm;
        this.changed = changed;
    }

    private static final byte[] HELLO = {(byte) 0xf0, 0x7d, 'F', 'S', 0, (byte) 0xf7};
    private static final String[] FM1_HINTS = {"fm-1", "fm1", "felucca", "melodee", "choralroot", "loom-8", "jiant"};

    static boolean isFm1(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String h : FM1_HINTS) if (n.contains(h)) return true;
        return false;
    }

    /** Hello to each FM-1, about once a second (it stops saying its sound three seconds after the last). */
    private final Runnable greet = new Runnable() {
        @Override
        public void run() {
            for (Dev d : open.values()) {
                if (d.in == null || !isFm1(d.name)) continue;
                try {
                    d.in.send(HELLO, 0, HELLO.length);
                } catch (IOException ignored) {
                }
            }
            ui.postDelayed(this, 900);
        }
    };

    void start() {
        mm.registerDeviceCallback(watch, ui);
        for (MidiDeviceInfo i : mm.getDevices()) consider(i);
        ui.post(greet);
    }

    void stop() {
        ui.removeCallbacks(greet);
        mm.unregisterDeviceCallback(watch);
        for (Dev d : open.values()) d.close();
        open.clear();
        sinks.clear();
    }

    // ---- what there is

    /** How a device ranks for being the first keyboard: keyboards, then other devices, then other apps' ports. */
    private static int rank(MidiDeviceInfo i, String name) {
        String n = name.toLowerCase(Locale.US);
        if (i.getType() == MidiDeviceInfo.TYPE_VIRTUAL) return 2;
        for (String h : new String[] {"key", "piano", "minilab", "launch", "oxygen", "keystep", "akai", "arturia", "novation", "m-audio", "roland", "yamaha", "casio", "korg", "nektar", "alesis"})
            if (n.contains(h)) return 0;
        return 1;
    }

    private List<MidiDeviceInfo> sorted(boolean sends) {
        List<MidiDeviceInfo> found = new ArrayList<>();
        for (MidiDeviceInfo i : mm.getDevices()) {
            if (isSelf(i)) continue;
            if (sends ? i.getOutputPortCount() > 0 : i.getInputPortCount() > 0) found.add(i);
        }
        Collections.sort(found, (a, b) -> {
            int r = Integer.compare(rank(a, name(a)), rank(b, name(b)));
            return r != 0 ? r : name(a).compareTo(name(b));
        });
        return found;
    }

    /** The keyboards (devices that send MIDI), keyboards first. */
    List<String> froms() {
        List<String> n = new ArrayList<>();
        for (MidiDeviceInfo i : sorted(true)) if (i.getId() != fm1Id) n.add(name(i));
        return n;
    }

    /** The devices that can be played (they take MIDI in): the FM-1 first, through FM-1 Mirror's link. */
    List<String> tos() {
        List<String> n = new ArrayList<>();
        n.add(FM1);
        for (MidiDeviceInfo i : sorted(false)) if (i.getId() != fm1Id) n.add(name(i));
        return n;
    }

    /** FM-1 Mirror linked to (or lost) an FM-1: its device is left to FM-1 Mirror, or comes back here. */
    void setFm1(int id, java.util.function.Consumer<byte[]> out) {
        fm1Out = out;
        if (id == fm1Id) return;
        int was = fm1Id;
        fm1Id = id;
        Dev d = open.remove(id);
        if (d != null) d.close();
        if (was >= 0) for (MidiDeviceInfo i : mm.getDevices()) if (i.getId() == was) consider(i);
        apply();
    }

    /** The keyboard a pair means, among those there are now ("" is the first keyboard); null if none. */
    String inputOf(Route r) {
        List<String> f = froms();
        if (r.from.isEmpty()) return f.isEmpty() ? null : f.get(0);
        return f.contains(r.from) ? r.from : null;
    }

    /** What's been played on a keyboard (null while it isn't open). */
    Played played(String name) {
        for (Dev d : open.values()) if (d.name.equals(name)) return d.played;
        return null;
    }

    /** A pair's TO devices whose MIDI in is open now. */
    List<String> playingTo(Route r) {
        List<String> n = new ArrayList<>();
        for (String t : r.to) if (sinks.containsKey(t) || (t.equals(FM1) && fm1Out != null)) n.add(t);
        return n;
    }

    /** The sound an FM-1 says it's playing, while it keeps saying it. */
    String fm1Sound() {
        for (Dev d : open.values()) {
            String s = d.played.fm1Sound();
            if (s != null) return s;
        }
        return null;
    }

    // ---- opening and closing

    private void consider(MidiDeviceInfo info) {
        if (isSelf(info) || open.containsKey(info.getId()) || info.getId() == fm1Id) return;
        if (info.getOutputPortCount() == 0 && info.getInputPortCount() == 0) return;
        mm.openDevice(info, device -> {
            if (device == null) return;
            if (open.containsKey(info.getId())) {
                try {
                    device.close();
                } catch (IOException ignored) {
                }
                return;
            }
            Dev d = new Dev(info, device);
            for (int k = 0; k < info.getOutputPortCount(); k++) {
                MidiOutputPort port = device.openOutputPort(k);
                if (port == null) continue;
                Played.Stream stream = d.played.stream(d.name);
                port.connect(new MidiReceiver() {
                    @Override
                    public void onSend(byte[] data, int offset, int count, long timestamp) {
                        Set<String> to = d.targets;
                        if (to != null) forward(d.name, to, data, offset, count);
                        stream.feed(data, offset, count);
                    }
                });
                d.ports.add(port);
            }
            open.put(info.getId(), d);
            apply();
        }, ui);
    }

    /**
     * Makes the open devices match the pairs: each keyboard's targets, and the MIDI ins of every TO (and FM-1, for
     * the hello) open, the rest closed. Call after changing `routes`.
     */
    void apply() {
        Map<String, Set<String>> wants = new HashMap<>();
        Set<String> allTo = new TreeSet<>();
        for (Route r : routes) {
            String in = inputOf(r);
            if (in == null) continue;
            Set<String> t = wants.get(in);
            if (t == null) wants.put(in, t = new TreeSet<>());
            t.addAll(r.to);
            allTo.addAll(r.to);
        }
        for (Dev d : open.values()) {
            Set<String> t = wants.get(d.name);
            d.targets = t == null ? null : Collections.unmodifiableSet(new TreeSet<>(t));
            // Only a TO's MIDI in: Android lets one app at a time send to a device, and FM-1 Mirror needs the FM-1's for its
            // screen. (Its hellos make the FM-1 say its sound to every app listening, so the name still comes.)
            boolean need = d.info.getInputPortCount() > 0 && allTo.contains(d.name);
            if (need && d.in == null) d.in = d.device.openInputPort(0);
            else if (!need) d.closeIn();
            if (d.in != null && allTo.contains(d.name)) sinks.put(d.name, d.in);
            else sinks.remove(d.name);
        }
        changed.run();
    }

    /**
     * What goes on to the TO devices: everything but clock and the like, and SysEx; to an FM-1 on channel 1, where
     * it listens; never back to the device it came from.
     */
    private void forward(String from, Set<String> to, byte[] data, int offset, int count) {
        if (count == 0 || (data[offset] & 0xff) >= 0xf0) return;
        byte[] m = java.util.Arrays.copyOfRange(data, offset, offset + count);
        byte[] one = null;
        for (String t : to) {
            if (t.equals(from)) continue;
            if (t.equals(FM1)) {
                java.util.function.Consumer<byte[]> out = fm1Out;
                if (out != null) out.accept(m.clone());   // (FM-1 Mirror puts it on the FM-1's channel)
                continue;
            }
            MidiInputPort in = sinks.get(t);
            if (in == null) continue;
            byte[] out = m;
            if (isFm1(t)) {
                if (one == null) {
                    one = m.clone();
                    for (int i = 0; i < one.length; i++) {
                        int b = one[i] & 0xff;
                        if (b >= 0x80 && b < 0xf0) one[i] = (byte) (b & 0xf0);
                    }
                }
                out = one;
            }
            try {
                in.send(out, 0, out.length);
            } catch (IOException ignored) {
            }
        }
    }

    private static void notesOff(MidiInputPort in) {
        try {
            for (int ch = 0; ch < 16; ch++) in.send(new byte[] {(byte) (0xb0 | ch), 123, 0}, 0, 3);
        } catch (IOException ignored) {
        }
    }

    private static boolean isSelf(MidiDeviceInfo i) {
        Bundle p = i.getProperties();
        return p != null && "com.pfkellogg.midispyglass".equals(p.getString(MidiDeviceInfo.PROPERTY_NAME));
    }

    static String name(MidiDeviceInfo i) {
        Bundle p = i.getProperties();
        String n = p == null ? null : p.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (n == null || n.isEmpty()) n = p == null ? null : p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        if (n == null || n.isEmpty()) n = "MIDI device " + i.getId();
        String how = i.getType() == MidiDeviceInfo.TYPE_BLUETOOTH ? "Bluetooth" : i.getType() == MidiDeviceInfo.TYPE_USB ? "USB" : "app";
        return n + " (" + how + ")";
    }
}
