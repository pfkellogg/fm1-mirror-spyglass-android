package com.pfkellogg.fm1mirror;

import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Installing the Big Screen firmware over the FM-1's USB MIDI connection: the desktop app's
 * src/install.rs, step for step (itself a port of the "tagged" update in M-VAVE's WebUpdate.js).
 * It needs an FM-1 that already runs M-VAVE's Open firmware, or one waiting in its updater.
 */
final class Installer implements Runnable {
    /** How it's going; read by the window. */
    static final class Progress {
        volatile String step = "";
        /** 0-100 while writing. */
        volatile int percent;
        volatile boolean done;
        volatile String error;
    }

    /** The package uses M-VAVE's own updater program, which names itself this while it works. */
    private static final String LOADER_IDENTITY = "ota-FM-1_015";
    /** When the FM-1 doesn't come back on USB in time: an update can always be finished. */
    private static final String LOST = "the phone lost the FM-1 partway through. Nothing is broken: the FM-1 keeps waiting for the rest of the update";

    final Progress progress = new Progress();
    private final MidiManager mm;
    private final byte[] pkg;
    private final String build;

    Installer(MidiManager mm, byte[] pkg, String buildIdentityJson) {
        this.mm = mm;
        this.pkg = pkg;
        this.build = Codec.buildId(buildIdentityJson);
    }

    private static final class Failure extends Exception {
        Failure(String why) { super(why); }
    }

    /** One FM-1 connection and the updater replies that come back on it. */
    private static final class Link implements AutoCloseable {
        final Fm1Port port;
        final LinkedBlockingQueue<Codec.Reply> replies;

        Link(Fm1Port port, LinkedBlockingQueue<Codec.Reply> replies) {
            this.port = port;
            this.replies = replies;
        }

        static Link open(MidiManager mm, MidiDeviceInfo info) {
            LinkedBlockingQueue<Codec.Reply> replies = new LinkedBlockingQueue<>();
            Fm1Port p = Fm1Port.open(mm, info, m -> {
                Codec.Reply r = Codec.parse(m);
                if (r != null) replies.add(r);
            });
            return p == null ? null : new Link(p, replies);
        }

        void send(byte[] m) throws Failure {
            try {
                port.send(m);
            } catch (IOException e) {
                throw new Failure("the USB connection went away");
            }
        }

        Codec.Reply read(long ms) {
            try {
                return replies.poll(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        }

        byte[] query(int command, byte[] payload, long ms) {
            replies.clear();
            try {
                port.send(Codec.frame(command, payload));
            } catch (IOException e) {
                return null;
            }
            long until = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < until) {
                Codec.Reply r = read(100);
                if (r != null && r.command == command) return r.data;
            }
            return null;
        }

        String identity() {
            for (int i = 0; i < 2; i++) {
                byte[] r = query(17, new byte[0], 750);
                if (r != null) return Codec.identity(r);
            }
            return null;
        }

        @Override
        public void close() { port.close(); }
    }

    private static final class Found {
        final Link link;
        final String identity;

        Found(Link link, String identity) {
            this.link = link;
            this.identity = identity;
        }
    }

    /** Opens the one device that answers as `want` (or as anything), trying again for `waitMs`. */
    private Found find(String want, boolean updater, long waitMs) throws Failure {
        long until = System.currentTimeMillis() + waitMs;
        while (true) {
            List<Found> found = new ArrayList<>();
            for (MidiDeviceInfo info : Fm1Port.candidates(mm, updater)) {
                if (Ble.isBluetooth(info)) continue; // firmware goes over USB only
                Link link = Link.open(mm, info);
                if (link == null) continue;
                String id = link.identity();
                if (id != null && (want == null || want.equals(id))) found.add(new Found(link, id));
                else link.close();
            }
            if (found.size() > 1) {
                for (Found f : found) f.link.close();
                throw new Failure("more than one FM-1 answered: unplug the others");
            }
            if (!found.isEmpty()) return found.get(0);
            if (System.currentTimeMillis() >= until) return null;
            sleep(600);
        }
    }

    private static Codec.Capability capability(byte[] d) throws Failure {
        if (d == null) throw new Failure("the FM-1 didn't answer");
        Codec.Capability c = Codec.capability(d);
        if (c == null) throw new Failure("the FM-1's updater isn't one this app knows");
        return c;
    }

    /** Answers the FM-1's requests for blocks of `image` until it says it has finished `finish`. */
    private void serve(Link link, byte[] image, long finish, long idleMs, String step) throws Failure {
        boolean[] covered = new boolean[image.length];
        long total = 0, started = System.currentTimeMillis(), last = started;
        while (true) {
            long now = System.currentTimeMillis();
            if (now - started > Math.max(idleMs * 3, 120_000)) throw new Failure("the update took too long");
            if (now - last > idleMs)
                throw new Failure("the FM-1 stopped asking for the firmware. Nothing is broken: the FM-1 keeps waiting for the rest of the update");
            Codec.Reply r = link.read(250);
            if (r == null || r.command != 48 || r.data.length != 8) continue;
            byte[] d = r.data;
            long address = Codec.le(d, 1, 4);
            int n = (int) Codec.le(d, 5, 3);
            if (n == 0 || n > 512) throw new Failure("the FM-1 asked for a bad length");
            if (address == 0xe000_0000L || address == 0xf000_0000L) {
                if (address != finish || n != 8) throw new Failure("the FM-1 is at a different step of the update");
                link.send(done(d));
                // Answer repeats of the finish request for a moment, as the workbench page does.
                long until = System.currentTimeMillis() + 600;
                while (System.currentTimeMillis() < until) {
                    Codec.Reply again = link.read(100);
                    if (again != null && again.command == 48 && again.data.length == 8 && Codec.le(again.data, 1, 4) == finish)
                        link.send(done(again.data));
                }
                return;
            }
            if (address > image.length || n > image.length - address) throw new Failure("the FM-1 asked for data outside the firmware");
            int a = (int) address;
            last = System.currentTimeMillis();
            byte[] p = Arrays.copyOf(d, 8 + n);
            System.arraycopy(image, a, p, 8, n);
            link.send(Codec.frame(48, p));
            for (int i = a; i < a + n; i++)
                if (!covered[i]) {
                    covered[i] = true;
                    total++;
                }
            progress.step = step;
            progress.percent = (int) Math.min(99, total * 100 / image.length);
        }
    }

    private static byte[] done(byte[] request) {
        byte[] p = Arrays.copyOf(request, 16);
        System.arraycopy("success\0".getBytes(), 0, p, 8, 8);
        return Codec.frame(48, p);
    }

    private void set(String step) {
        progress.step = step;
        progress.percent = 0;
    }

    @Override
    public void run() {
        try {
            install();
            progress.step = "Installed. The FM-1's screen will appear here.";
            progress.percent = 100;
            progress.done = true;
        } catch (Failure e) {
            progress.error = e.getMessage();
        } catch (RuntimeException e) {
            progress.error = String.valueOf(e.getMessage());
        }
    }

    private void install() throws Failure {
        Codec.Package p;
        try {
            p = Codec.inspect(pkg);
        } catch (IllegalArgumentException e) {
            throw new Failure(e.getMessage());
        }
        byte[] image = p.image;
        set("Finding the FM-1…");
        // The window's own link to the FM-1 lets go first.
        sleep(400);
        Found who = find(null, true, 5000);
        if (who == null) throw new Failure("no FM-1 answered. Is it plugged in and on?");
        if (Codec.isUpdater(who.identity)) {
            // Left in its updater by an update that stopped partway: carry on from there,
            // as M-VAVE's own page does.
            try (Link link = who.link) {
                link.send(Codec.ENTER_LOADER);
                serve(link, image, 0xf000_0000L, 180_000, "Finishing the update: writing the firmware…");
            }
        } else {
            try (Link link = who.link) {
                if (!Codec.isOpenFirmware(who.identity))
                    throw new Failure("this FM-1 runs " + who.identity + ", not M-VAVE's Open firmware. Install that first (see Step 1).");
                Codec.Capability c = capability(link.query(49, new byte[0], 1500));
                if (c.state != 0) throw new Failure("the FM-1 is busy with another update");
                if (c.build.equals(build)) return; // already there
                int n = image.length;
                byte[] tag = new byte[38];
                tag[0] = 1;
                tag[1] = (byte) c.family;
                for (int i = 0; i < 4; i++) tag[2 + i] = (byte) (n >> (8 * i));
                for (int i = 0; i < 32; i++) tag[6 + i] = (byte) Integer.parseInt(build.substring(i * 2, i * 2 + 2), 16);
                byte[] ok = link.query(50, tag, 1500);
                if (ok == null || ok.length != 1 || ok[0] != 0) throw new Failure("the FM-1 didn't accept the firmware");
                link.send(Codec.ENTER_LOADER);
                serve(link, image, 0xe000_0000L, 10_000, "Preparing the FM-1's updater…");
            }
            set("Waiting for the FM-1's updater…");
            Found loader = find(LOADER_IDENTITY, true, 60_000);
            if (loader == null) throw new Failure(LOST);
            try (Link link = loader.link) {
                link.send(Codec.ENTER_LOADER);
                serve(link, image, 0xf000_0000L, 180_000, "Writing the firmware…");
            }
        }
        set("Waiting for the FM-1 to restart…");
        Found back = find(p.product, false, 40_000);
        if (back == null) throw new Failure("the FM-1 didn't come back. Turn it off and on; if it shows M-VAVE's updater, try again.");
        try (Link link = back.link) {
            Codec.Capability c = capability(link.query(49, new byte[0], 1500));
            if (!c.build.equals(build) || c.state != 0)
                throw new Failure("the FM-1 restarted, but on another build (" + c.build.substring(0, 16) + ") than expected");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
