package com.pfkellogg.fm1mirror;

import android.graphics.Bitmap;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;

import java.io.Closeable;
import java.io.IOException;

/**
 * Another FM-1 plugged in at the same time as the one the app works with (MainActivity's own link): linked and
 * listening on a thread of its own, its screen kept in its own pixels, shown side by side and on its tab. Picking its
 * tab moves the app's own link over to it (MainActivity.pick) and this one is let go.
 * The hellos are the link thread's, cut down: Big Screen version 2 first, else version 1, what it is, and Charles
 * Vestal's fm1-base mirror with its own remote (so the lock can turn this FM-1's knobs too).
 */
final class Extra implements Closeable {
    private static final long HELLO_EVERY = 900;
    final MidiDeviceInfo info;
    final String name;
    private final Fm1Port port;
    final int[] pixels = new int[Codec.SIZE * Codec.SIZE];
    private final BigScreen2.Viewer v2 = new BigScreen2.Viewer();
    private volatile int v2Answers;
    private volatile boolean baseOn;
    private final Fm1Base.Remote remote = new Fm1Base.Remote();
    volatile String identity, firmware;
    /** Its screen changed since it was last drawn; set by the input, cleared by the drawing. */
    volatile boolean changed = true;
    volatile boolean lost;
    /** Drawn by the screen view (its look painted in), when `changed`. */
    final Bitmap bitmap = Bitmap.createBitmap(Codec.SIZE, Codec.SIZE, Bitmap.Config.ARGB_8888);
    final int[] argb = new int[Codec.SIZE * Codec.SIZE];
    private volatile boolean running = true;
    private final Runnable onChange;
    private final java.util.function.BooleanSupplier behind;

    private Extra(MidiDeviceInfo info, Fm1Port port, Runnable onChange, java.util.function.BooleanSupplier behind) {
        this.info = info;
        this.name = port.name;
        this.port = port;
        this.onChange = onChange;
        this.behind = behind;
    }

    /** Opens it and starts saying hello; null if it can't be had. Waits for Android: never on the main thread. */
    static Extra open(MidiManager mm, MidiDeviceInfo info, Runnable onChange, java.util.function.BooleanSupplier behind) {
        Extra[] me = new Extra[1];
        Fm1Port p = Fm1Port.open(mm, info, m -> {
            Extra e = me[0];
            if (e != null) e.onMessage(m);
        });
        if (p == null) return null;
        Extra e = new Extra(info, p, onChange, behind);
        me[0] = e;
        Thread t = new Thread(e::hellos, "FM-1 extra");
        t.setDaemon(true);
        t.start();
        return e;
    }

    /** A Big Knobs / Key Lights message (the lock): as it is to a Big Screen build, as fm1-base's remote to fm1-base. */
    void sendPanel(byte[] m) {
        try {
            if (baseOn) {
                remote.take(m);
                byte[] f = remote.next();
                if (f != null) port.send(f);
            } else {
                port.send(m);
            }
        } catch (IOException e) {
            lost = true;
        }
    }

    private void hellos() {
        long opened = System.currentTimeMillis(), helloAt = 0, baseAt = 0;
        boolean askedIdentity = false, askedInfo = false, baseFirst = true;
        int baseAsked = 0;
        final long[] baseAskAt = {1000, 5000, 6000};
        BigScreen2.Asker asker = new BigScreen2.Asker();
        while (running && !lost) {
            long now = System.currentTimeMillis();
            boolean away = behind.getAsBoolean();
            try {
                if (away) {
                    asker.wantAll();
                    baseFirst = true;
                } else if (now - helloAt >= asker.every(HELLO_EVERY)) {
                    helloAt = now;
                    port.send(asker.next(v2Answers));
                }
                if (!askedIdentity && now - opened > 2000) {
                    askedIdentity = true;
                    port.send(Codec.frame(17, new byte[0]));
                }
                if (!askedInfo && now - opened > 4000) {
                    askedInfo = true;
                    port.send(Codec.ASK_INFO);
                }
                if (baseOn) {
                    byte[] next = remote.next();
                    if (next != null) port.send(next);
                    if (!away && now - baseAt >= 2000) {
                        baseAt = now;
                        port.send(Fm1Base.request(baseFirst ? 2 : 1));
                        baseFirst = false;
                    }
                } else if (!away && baseAsked < baseAskAt.length && now - opened > baseAskAt[baseAsked]) {
                    baseAsked++;
                    port.send(Fm1Base.request(2));
                }
            } catch (IOException e) {
                lost = true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void onMessage(byte[] m) {
        String info = Codec.firmwareInfo(m);
        if (info != null) {
            firmware = info;
            onChange.run();
            return;
        }
        synchronized (this) {
            int got = v2.apply(m, System.currentTimeMillis());
            if (got == BigScreen2.INFO) {
                v2Answers++;
                return;
            }
            if (got == BigScreen2.FRAME || (got == BigScreen2.PAINTED && v2.overdue(System.currentTimeMillis()))) {
                v2.show(pixels);
                changed();
                return;
            }
            if (got == BigScreen2.PAINTED) return;
            if (Codec.apply(pixels, m)) {
                changed();
                return;
            }
            int b = Fm1Base.applyScreen(pixels, m);  // (its lights are the one picked's business, not this one's)
            if (b == Fm1Base.HELLO) {
                baseOn = true;
                return;
            }
            if (b == Fm1Base.ANSWERED) {
                remote.answered();
                return;
            }
            if (b == Fm1Base.PAINTED) {
                changed();
                return;
            }
        }
        Codec.Reply r = Codec.parse(m);
        if (r != null && r.command == 17 && Codec.identity(r.data) != null) {
            identity = Codec.identity(r.data);
            onChange.run();
        }
    }

    private void changed() {
        if (!changed) {
            changed = true;
            onChange.run();
        }
    }

    /** Paints its screen into `bitmap` in `look` if it changed (call on the main thread, while drawing). */
    synchronized void paint(Codec.Look look) {
        if (!changed) return;
        changed = false;
        look.paintAll(pixels, argb, Codec.SIZE);
        bitmap.setPixels(argb, 0, Codec.SIZE, 0, 0, Codec.SIZE, Codec.SIZE);
    }

    @Override
    public void close() {
        running = false;
        port.close();
    }
}
