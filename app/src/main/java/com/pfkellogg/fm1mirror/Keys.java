package com.pfkellogg.fm1mirror;

import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A keyboard (or any MIDI device: a sequencer, a USB MIDI interface's MIDI IN) playing the FM-1
 * through this app. On Android only one app can send to the FM-1, and this one already does, for
 * the screen, so the notes come through here too. Everything goes on as it came, clock included,
 * except SysEx: that's how the FM-1 is updated, so none of it is passed on. With `channel` 1 to 16
 * every channel message moves to that channel: 1 by default, where the FM-1 listens (a keyboard
 * knocked onto another channel, a Keystation on 13, say, would be silent otherwise); another one for a
 * firmware that listens there, e.g. ChoralRoot's MIDI Keys channel. 0: as they come.
 */
final class Keys implements Closeable {
    final MidiDeviceInfo info;
    private final MidiDevice device;
    private final MidiOutputPort out;

    private Keys(MidiDeviceInfo info, MidiDevice device, MidiOutputPort out) {
        this.info = info;
        this.device = device;
        this.out = out;
    }

    /** Devices that can play the FM-1: everything that sends MIDI except the FM-1 (`fm1`) itself. */
    static List<MidiDeviceInfo> sources(MidiManager mm, MidiDeviceInfo fm1) {
        List<MidiDeviceInfo> found = new ArrayList<>();
        for (MidiDeviceInfo i : mm.getDevices()) {
            if (i.getOutputPortCount() == 0 || (fm1 != null && i.getId() == fm1.getId())) continue;
            String n = Fm1Port.name(i).toLowerCase();
            if (n.contains("fm1") || n.contains("fm-1")) continue;
            found.add(i);
        }
        return found;
    }

    /** Picked by itself when nothing was chosen: a USB or Bluetooth device, not another app's. */
    static boolean plugged(MidiDeviceInfo i) {
        return i.getType() != MidiDeviceInfo.TYPE_VIRTUAL;
    }

    /** Null if it can't be opened. Waits for Android, so never call it on the main thread. */
    static Keys open(MidiManager mm, MidiDeviceInfo info, Fm1Port fm1, int channel, Played played) {
        MidiDevice device = Fm1Port.openDevice(mm, info);
        if (device == null) return null;
        MidiOutputPort out = device.openOutputPort(0);
        if (out == null) {
            try {
                device.close();
            } catch (IOException ignored) {
            }
            return null;
        }
        out.connect(new MidiReceiver() {
            private boolean sysex;

            @Override
            public void onSend(byte[] data, int offset, int count, long timestamp) {
                byte[] pass = new byte[count];
                int n = 0;
                for (int i = offset; i < offset + count; i++) {
                    int b = data[i] & 0xff;
                    if (b == 0xf0) sysex = true;
                    else if (b == 0xf7) sysex = false;
                    else if (b >= 0xf8) pass[n++] = data[i]; // clock and the like, even inside SysEx
                    else if (!sysex || b >= 0x80) {
                        sysex = false; // a status byte ends a SysEx that never closed
                        pass[n++] = channel > 0 && b >= 0x80 && b < 0xf0 ? (byte) ((b & 0xf0) | (channel - 1)) : data[i];
                    }
                }
                if (n == 0) return;
                played.keys.feed(pass, 0, n);
                try {
                    fm1.send(n == count ? pass : Arrays.copyOf(pass, n));
                } catch (IOException ignored) {
                    // the FM-1 went: the link notices and closes this
                }
            }
        });
        return new Keys(info, device, out);
    }

    /**
     * What goes on to the FM-1 from a keyboard, for the straight-USB path (MainActivity.openUsbKeys): as Keys does
     * it, everything but SysEx, clock included, channel messages moved to `channel` (1-16; 0 as they come).
     */
    static final class Filter {
        private boolean sysex;

        byte[] pass(byte[] data, int offset, int count, int channel) {
            byte[] pass = new byte[count];
            int n = 0;
            for (int i = offset; i < offset + count; i++) {
                int b = data[i] & 0xff;
                if (b == 0xf0) sysex = true;
                else if (b == 0xf7) sysex = false;
                else if (b >= 0xf8) pass[n++] = data[i];
                else if (!sysex || b >= 0x80) {
                    sysex = false;
                    pass[n++] = channel > 0 && b >= 0x80 && b < 0xf0 ? (byte) ((b & 0xf0) | (channel - 1)) : data[i];
                }
            }
            return Arrays.copyOf(pass, n);
        }
    }

    /** All notes off on every channel, so nothing hangs when the keyboard goes mid-note. */
    static void silence(Fm1Port fm1) {
        try {
            for (int ch = 0; ch < 16; ch++) fm1.send(new byte[] {(byte) (0xb0 | ch), 123, 0});
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try {
            device.close();
        } catch (IOException ignored) {
        }
    }
}
