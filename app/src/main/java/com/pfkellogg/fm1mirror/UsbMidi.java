package com.pfkellogg.fm1mirror;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * USB MIDI straight over USB, for Android devices that can be a USB host but have no MIDI service (Android's
 * android.software.midi), such as an onn Google TV box: the MIDI Streaming interface (USB Audio class 1, subclass
 * 3) is claimed and its bulk endpoints read and written here. On the wire every MIDI message travels as 4-byte
 * packets: a cable/code byte (its low nibble, the Code Index Number, says how many of the next three bytes count),
 * then up to three MIDI bytes; SysEx goes three bytes a packet (CIN 4), its last packet CIN 5, 6 or 7.
 */
final class UsbMidi implements Closeable {
    /** What comes in: a few MIDI bytes at a time, in order (SysEx split over calls). From the reading thread. */
    interface Receiver {
        void onSend(byte[] data, int offset, int count);
    }

    final UsbDevice device;
    final String name;
    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint in, out;
    private volatile boolean closed;
    private Thread reader;

    private UsbMidi(UsbDevice device, UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
        this.device = device;
        this.name = name(device);
        this.conn = conn;
        this.intf = intf;
        this.in = in;
        this.out = out;
    }

    /** "Jieli Technology USB Composite Device", as Android's MIDI service would name it: maker and product. */
    static String name(UsbDevice d) {
        String maker = d.getManufacturerName() == null ? "" : d.getManufacturerName().trim();
        String product = d.getProductName() == null ? "" : d.getProductName().trim();
        String n = product.contains(maker) ? product : (maker + " " + product).trim();
        return n.isEmpty() ? "USB MIDI device " + d.getDeviceId() : n;
    }

    /** The interface that carries MIDI, or null. */
    private static UsbInterface midiInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO && f.getInterfaceSubclass() == 3 && f.getEndpointCount() > 0) return f;
        }
        return null;
    }

    /** The USB MIDI devices plugged in now. */
    static List<UsbDevice> devices(UsbManager um) {
        List<UsbDevice> found = new ArrayList<>();
        if (um == null) return found;
        for (UsbDevice d : um.getDeviceList().values()) if (midiInterface(d) != null) found.add(d);
        return found;
    }

    /** Opens one (permission already granted) and starts reading into `rx`; null if it can't be had. */
    static UsbMidi open(UsbManager um, UsbDevice d, Receiver rx) {
        UsbInterface f = midiInterface(d);
        if (f == null || !um.hasPermission(d)) return null;
        UsbEndpoint in = null, out = null;
        for (int e = 0; e < f.getEndpointCount(); e++) {
            UsbEndpoint ep = f.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
        }
        if (in == null && out == null) return null;
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) return null;
        if (!c.claimInterface(f, true)) {
            c.close();
            return null;
        }
        UsbMidi m = new UsbMidi(d, c, f, in, out);
        if (in != null && rx != null) m.startReading(rx);
        return m;
    }

    private void startReading(Receiver rx) {
        reader = new Thread(() -> {
            byte[] buf = new byte[Math.max(64, in.getMaxPacketSize())];
            byte[] msg = new byte[3];
            while (!closed) {
                int n = conn.bulkTransfer(in, buf, buf.length, 250);
                if (n <= 0) continue;   // (a time-out reads as -1: nothing came; unplugging is noticed by present())
                for (int i = 0; i + 4 <= n; i += 4) {
                    int len = packetLength(buf[i] & 0x0f);
                    if (len == 0) continue;
                    System.arraycopy(buf, i + 1, msg, 0, len);
                    rx.onSend(msg, 0, len);
                }
            }
        }, "USB MIDI " + name);
        reader.setDaemon(true);
        reader.start();
    }

    /** How many MIDI bytes a packet with this Code Index Number carries. */
    static int packetLength(int cin) {
        switch (cin) {
            case 0x5: case 0xf: return 1;          // a single byte (SysEx end alone, real time)
            case 0x2: case 0x6: case 0xc: case 0xd: return 2;
            case 0x3: case 0x4: case 0x7: case 0x8: case 0x9: case 0xa: case 0xb: case 0xe: return 3;
            default: return 0;                     // (0, 1: reserved)
        }
    }

    /** MIDI bytes (whole messages, SysEx included) as USB MIDI packets on cable 0. */
    static byte[] packets(byte[] m, int offset, int count) {
        byte[] p = new byte[(count + 2) / 3 * 4 + count * 4];   // plenty
        int o = 0, i = offset, end = offset + count;
        while (i < end) {
            int b = m[i] & 0xff;
            if (b == 0xf0) {                               // SysEx: three bytes a packet until F7
                int j = i;
                while (j < end && (m[j] & 0xff) != 0xf7) j++;
                int stop = Math.min(end, j + 1);
                while (i < stop) {
                    int left = stop - i;
                    boolean last = left <= 3 && (m[stop - 1] & 0xff) == 0xf7;
                    int take = Math.min(3, left);
                    p[o] = (byte) (last ? (take == 1 ? 0x5 : take == 2 ? 0x6 : 0x7) : 0x4);
                    for (int k = 0; k < 3; k++) p[o + 1 + k] = k < take ? m[i + k] : 0;
                    o += 4;
                    i += take;
                }
            } else if (b >= 0xf8) {                        // real time: one byte
                p[o] = 0xf;
                p[o + 1] = m[i];
                o += 4;
                i++;
            } else if (b >= 0x80) {                        // a channel or common message
                int len = b >= 0xf0 ? (b == 0xf2 ? 3 : b == 0xf1 || b == 0xf3 ? 2 : 1)
                        : ((b & 0xf0) == 0xc0 || (b & 0xf0) == 0xd0) ? 2 : 3;
                len = Math.min(len, end - i);
                int cin = b >= 0xf0 ? (len == 1 ? 0x5 : len == 2 ? 0x2 : 0x3) : (b >> 4);
                p[o] = (byte) cin;
                for (int k = 0; k < len; k++) p[o + 1 + k] = m[i + k];
                o += 4;
                i += len;
            } else {
                i++;                                       // (a stray data byte: nothing to send it with)
            }
        }
        return java.util.Arrays.copyOf(p, o);
    }

    /** Sends whole MIDI messages. */
    synchronized void send(byte[] m) throws IOException {
        if (closed || out == null) throw new IOException("closed");
        byte[] p = packets(m, 0, m.length);
        int max = Math.max(4, out.getMaxPacketSize() / 4 * 4);
        for (int i = 0; i < p.length; i += max) {
            int n = Math.min(max, p.length - i);
            if (conn.bulkTransfer(out, p, i, n, 1000) < 0) throw new IOException("USB MIDI write failed");
        }
    }

    /** Still plugged in. */
    boolean present(UsbManager um) {
        for (UsbDevice d : um.getDeviceList().values()) if (d.getDeviceId() == device.getDeviceId()) return true;
        return false;
    }

    @Override
    public void close() {
        closed = true;
        try {
            conn.releaseInterface(intf);
        } catch (RuntimeException ignored) {
        }
        conn.close();
    }
}
