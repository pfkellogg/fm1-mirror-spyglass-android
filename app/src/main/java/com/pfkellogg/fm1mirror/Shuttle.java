package com.pfkellogg.fm1mirror;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * A Contour ShuttleXpress (or ShuttlePRO) on USB, read directly (USB host, no driver): the same remote as the desktop
 * app's shuttle.rs. Its report is 5 bytes: [ring -7..7, dial counter, 0, buttons 1-4 = 0x10/0x20/0x40/0x80, button 5 = 0x01].
 * Events come on the main thread.
 */
final class Shuttle {
    interface Listener {
        void dial(int notches);          // + clockwise
        void ring(int pos);              // -7..7, 0 = let go
        void button(int n, boolean down);
        void connected(boolean on);
    }

    static final int CONTOUR = 0x0B33;
    private static final String PERMISSION = "com.pfkellogg.fm1mirror.SHUTTLE_PERMISSION";
    private final Context ctx;
    private final UsbManager um;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private volatile UsbDevice open;
    private Thread reader;
    private final java.util.Set<Integer> asked = new java.util.HashSet<>();

    Shuttle(Context ctx, UsbManager um, Listener l) {
        this.ctx = ctx;
        this.um = um;
        this.l = l;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                look();
            }
        };
        IntentFilter f = new IntentFilter(PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f);
        look();
    }

    void close() {
        running = false;
    }

    /** Finds a Shuttle; asks for permission once; reads it in its own thread. */
    synchronized void look() {
        if (um == null || !running || open != null) return;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() != CONTOUR) continue;
            if (!um.hasPermission(d)) {
                if (asked.add(d.getDeviceId())) {
                    Intent i = new Intent(PERMISSION).setPackage(ctx.getPackageName());
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    um.requestPermission(d, PendingIntent.getBroadcast(ctx, 77, i, flags));
                }
                return;
            }
            start(d);
            return;
        }
    }

    private void start(UsbDevice d) {
        UsbInterface in = null;
        UsbEndpoint ep = null;
        for (int k = 0; k < d.getInterfaceCount() && ep == null; k++) {
            UsbInterface f = d.getInterface(k);
            for (int e = 0; e < f.getEndpointCount(); e++) {
                UsbEndpoint p = f.getEndpoint(e);
                if (p.getDirection() == UsbConstants.USB_DIR_IN && p.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    in = f;
                    ep = p;
                    break;
                }
            }
        }
        if (ep == null) return;
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) return;
        if (!c.claimInterface(in, true)) {
            c.close();
            return;
        }
        open = d;
        main.post(() -> l.connected(true));
        final UsbEndpoint fep = ep;
        final UsbInterface fin = in;
        reader = new Thread(() -> {
            Decoder dec = new Decoder();
            byte[] buf = new byte[Math.max(8, fep.getMaxPacketSize())];
            while (running) {
                int n = c.bulkTransfer(fep, buf, buf.length, 250);
                if (n == 0 || (n < 0 && um.getDeviceList().containsValue(d))) continue;
                if (n < 0) break; // unplugged
                dec.feed(buf, n, SystemClock.uptimeMillis(), (kind, a, b) -> main.post(() -> {
                    if (kind == 0) l.dial(a);
                    else if (kind == 1) l.ring(a);
                    else l.button(a, b);
                }));
            }
            try {
                c.releaseInterface(fin);
            } catch (Exception ignored) {
            }
            c.close();
            open = null;
            main.post(() -> l.connected(false));
        }, "Shuttle");
        reader.start();
    }

    /** As shuttle.rs's Decoder: dial steps (big jumps ignored), the ring (let go -> 0, even a step short or bouncing), buttons. */
    static final class Decoder {
        interface Out {
            void ev(int kind, int a, boolean b);
        }

        private int dial = -1, ring = 0, buttons = 0;
        private long outAt = -1, letGoAt = -1;

        void feed(byte[] r, int n, long now, Out out) {
            if (n < 5) return;
            int raw = Math.max(-7, Math.min(7, (int) r[0]));
            int pos = raw;
            boolean afterLetGo = letGoAt >= 0 && now - letGoAt < 200;
            if (Math.abs(raw) >= 3 && !afterLetGo) outAt = now;
            else if (Math.abs(raw) <= 2 && outAt >= 0 && now - outAt < 150) pos = 0;
            if (pos != 0 && (afterLetGo || (ring != 0 && Integer.signum(pos) != Integer.signum(ring)))) pos = 0;
            if (pos == 0 && ring != 0) {
                letGoAt = now;
                outAt = -1;
            }
            if (pos != ring) {
                ring = pos;
                out.ev(1, pos, false);
            }
            int d = r[1] & 0xff;
            if (dial >= 0) {
                int dd = (byte) (d - dial);
                if (dd != 0 && Math.abs(dd) <= 6) out.ev(0, dd, false);
            }
            dial = d;
            int b = ((r[3] & 0xff) >> 4) | ((r[4] & 1) << 4);
            for (int i = 0; i < 5; i++) {
                int was = buttons >> i & 1, is = b >> i & 1;
                if (was != is) out.ev(2, i + 1, is == 1);
            }
            buttons = b;
        }
    }
}
