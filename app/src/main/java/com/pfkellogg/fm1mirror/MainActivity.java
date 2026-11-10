package com.pfkellogg.fm1mirror;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FM-1 Mirror for Android: the FM-1's screen, big, sent over USB by the FM-1 Big Screen firmware
 * (the desktop app's src/main.rs). Plug the FM-1 into the phone or tablet, open the app.
 * A keyboard plugged in too plays the FM-1 through it (Keys): Android lets only one app send to the FM-1.
 * What it plays shows in the spyglass (Glass), beside the screen. Over Bluetooth too (Ble), when the
 * FM-1 isn't plugged in: the newer Big Screen firmware answers on the port that asked.
 */
public class MainActivity extends Activity {
    static final String WORKBENCH = "https://open.m-vave.com/fm1-workbench";
    private static final long HELLO_EVERY = 900, QUIET = 4000;
    private static final int TEXT = Color.WHITE, DIM = 0xffb0b0b0, BG = Color.BLACK;

    private MidiManager mm;
    /** Straight USB, when Android has no MIDI service (an onn Google TV box): see UsbMidi. */
    private android.hardware.usb.UsbManager um;
    /** The keyboard over straight USB, and the USB devices already asked about (when, by device id). */
    private volatile UsbMidi usbKeys;
    private final java.util.Map<Integer, Long> usbAsked = new java.util.concurrent.ConcurrentHashMap<>();
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Shared with the MIDI threads; guarded by `this`.
    private final int[] pixels = new int[Codec.SIZE * Codec.SIZE];
    private boolean changed;
    private long lastScreen; // when the last screen message came (0: not since the link opened)
    private String identity;
    /** What a Felucca-family firmware says it is ("FELUCCA SLOOP 2.8.4"): its USB name may only say "Felucca". */
    private String firmware;
    /** The sound playing, when the firmware says it ("T1 FM6 ICE BELL"): on top of the spyglass. */
    private volatile String sound;

    private volatile Fm1Port link;
    private volatile long opened;
    private volatile boolean lost, running;
    private volatile Installer installer;
    private volatile Keys keys;
    private volatile boolean keysLost, keysChanged;
    private final Played played = new Played();
    private Glass glass;
    private volatile String build; // the Big Screen build the FM-1 runs (its updater's answer)
    private String bundled = "";   // the build this app installs
    private volatile MidiDevice ble; // held open while the FM-1 is used over Bluetooth
    private volatile boolean bleOpening, bleScanning;
    private volatile long bleTriedAt;
    /** The user disconnected Bluetooth here: don't reconnect until they say so. */
    private volatile boolean bleOff;
    /** An FM-1 that Android lists over Bluetooth while this app isn't the one using it (another app has it). */
    private volatile String bleOther;
    /** The Bluetooth radio link to the FM-1 is up (not just Android trying to reach it). */
    private volatile boolean bleLinked;
    private Button btButton;

    private Codec.Look look = Codec.Look.COLOR;
    private boolean confirming, full;
    private String shown = "";

    private ScreenView screen;
    private LinearLayout bar, panel;
    private FrameLayout stage;
    private ScrollView panelScroll;
    private Button lookButton, keysButton, updateButton, mirrorButton, smoothButton, showMirror;
    /** The FM-1's screen shows; and with the jaggies taken off (off: exactly as it sends it). */
    private boolean mirrorOn = true, smoothOn = true;
    private TextView title;
    private TextView stepText;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mm = (MidiManager) getSystemService(Context.MIDI_SERVICE); // (null where Android has no MIDI service)
        um = (android.hardware.usb.UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("mirror", MODE_PRIVATE);
        try {
            look = Codec.Look.valueOf(prefs.getString("look", "COLOR"));
        } catch (IllegalArgumentException ignored) {
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        title = text("FM-1 Mirror", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateButton = button("Update FM-1", v -> confirmUpdate());
        updateButton.setVisibility(View.GONE);
        bar.addView(updateButton);
        bleOff = prefs.getBoolean("bleOff", false);
        btButton = button("BT", v -> manageBle());
        btButton.setVisibility(View.GONE);
        bar.addView(btButton);
        try {
            bundled = Codec.buildId(new String(asset("FM1_BIG_SCREEN_V14.build-identity.json")));
        } catch (IOException ignored) {
        }
        mirrorOn = prefs.getBoolean("mirror", true);
        smoothOn = prefs.getBoolean("smooth", true);
        keysButton = button("Keys", v -> pickKeys());
        bar.addView(keysButton);
        mirrorButton = button("", v -> setMirror(!mirrorOn));
        bar.addView(mirrorButton);
        smoothButton = button("", v -> toggleSmooth());
        bar.addView(smoothButton);
        bar.addView(button("Spyglass", v -> setGlass(glass == null)));
        lookButton = button(look.label, v -> nextLook());
        bar.addView(lookButton);
        Button big = button("Full screen", v -> setFull(true));
        bar.addView(big);
        root.addView(bar);

        stage = new FrameLayout(this);
        screen = new ScreenView(this);
        screen.setOnClickListener(v -> setFull(!full));
        stage.addView(screen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Always a way back on the hidden mirror's black screen.
        showMirror = button("Show mirror", v -> setMirror(true));
        stage.addView(showMirror, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM));
        showMirrors();
        panelScroll = new ScrollView(this);
        panelScroll.setBackgroundColor(BG);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(16), dp(24), dp(24));
        panelScroll.addView(panel);
        stage.addView(panelScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        if (prefs.getBoolean("glass", false)) setGlass(true);

        if (mm != null) mm.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceRemoved(MidiDeviceInfo info) {
                Fm1Port l = link;
                if (l != null && l.info != null && l.info.getId() == info.getId()) lost = true;
                Keys k = keys;
                if (k != null && k.info.getId() == info.getId()) keysLost = true;
                MidiDevice b = ble;
                if (b != null && b.getInfo().getId() == info.getId()) dropBle();
            }
        }, Fm1Port.callbacks());
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        Thread t = new Thread(this::keepLinked, "FM-1 link");
        t.setDaemon(true);
        t.start();
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false; // the link thread lets go of the FM-1
        main.removeCallbacks(tick);
    }

    // ---- the link: open the FM-1, keep saying hello, ask what it is

    /** On a thread of its own: opening waits for Android, and the FM-1 stops sending 3 s after the last hello. */
    private void keepLinked() {
        long lookedAt = 0, helloAt = 0, keysLookedAt = 0, otherAt = 0;
        boolean askedIdentity = false, askedBuild = false, askedInfo = false, first = true;
        while (running) {
            Fm1Port l = link;
            if (l != null && l.usb != null && !l.usb.present(um)) lost = true; // (straight USB: unplugged)
            if (l != null && (lost || installing())) {
                link = null;
                l.close();
                l = null;
            }
            // Disconnected by the user: let go of the FM-1 over Bluetooth (USB stays).
            if (bleOff && (ble != null || (l != null && Ble.isBluetooth(l.info)))) {
                if (l != null && Ble.isBluetooth(l.info)) {
                    link = null;
                    l.close();
                    l = null;
                }
                dropBle();
            }
            long now = System.currentTimeMillis();
            if (mm != null && now - otherAt >= 2000) {
                otherAt = now;
                bleOther = otherBle(l);
                bleLinked = (ble != null || (l != null && Ble.isBluetooth(l.info)))
                        && Ble.linked(this, prefs.getString("bleAddress", ""));
            }
            // Plugged in while on Bluetooth: USB is much faster, so move over.
            if (l != null && Ble.isBluetooth(l.info) && now - lookedAt >= 2000 && usbFm1()) {
                lookedAt = now;
                link = null;
                l.close();
                l = null;
                dropBle();
            }
            // Not plugged in: open the FM-1 found over Bluetooth before (every 10 s while it isn't there).
            if (mm != null && l == null && !bleOff && !installing() && ble == null && !bleOpening && now - bleTriedAt >= 10000 && !usbFm1()) {
                bleTriedAt = now;
                String address = prefs.getString("bleAddress", "");
                if (!address.isEmpty() && Ble.allowed(this)) {
                    bleOpening = true;
                    Ble.open(this, mm, address, d -> {
                        if (running && d != null) ble = d;
                        else closeQuietly(d);
                        bleOpening = false;
                    });
                }
            }
            if (l == null && !installing() && now - lookedAt >= 2000) {
                lookedAt = now;
                l = openFm1();
                if (l != null) {
                    synchronized (this) {
                        lastScreen = 0;
                        identity = null;
                        firmware = null;
                    }
                    setSound(null);
                    lost = false;
                    opened = System.currentTimeMillis();
                    askedIdentity = askedBuild = askedInfo = false;
                    first = true;
                    helloAt = 0;
                    link = l;
                }
            }
            if (l != null) {
                try {
                    if (now - helloAt >= HELLO_EVERY) {
                        helloAt = now;
                        // The whole screen the first time: someone else may be keeping the mirror on.
                        l.send(first ? Codec.HELLO_ALL : Codec.HELLO);
                        first = false;
                    }
                    // One question at a time: the FM-1's updater drops a second one sent straight after.
                    if (!askedIdentity && now - opened > 2000) {
                        askedIdentity = true;
                        l.send(Codec.frame(17, new byte[0]));
                    }
                    if (!askedBuild && now - opened > 3000) {
                        askedBuild = true;
                        l.send(Codec.frame(49, new byte[0]));
                    }
                    if (!askedInfo && now - opened > 4000) {
                        askedInfo = true;
                        l.send(Codec.ASK_INFO);
                    }
                } catch (IOException e) {
                    lost = true;
                }
            }
            // The keyboard: only while the FM-1 is here to play.
            Keys k = keys;
            boolean again = keysChanged;
            if (again) {
                keysChanged = false;
                keysLookedAt = 0;
            }
            if (k != null && (l == null || keysLost || again)) {
                keys = null;
                k.close();
                if (l != null) Keys.silence(l);
                k = null;
            }
            UsbMidi uk = usbKeys;
            if (uk != null && (l == null || again || !uk.present(um))) {
                usbKeys = null;
                uk.close();
                if (l != null) Keys.silence(l);
                uk = null;
            }
            if (l != null && k == null && uk == null && now - keysLookedAt >= 2000) {
                keysLookedAt = now;
                if (mm != null) {
                    MidiDeviceInfo want = wantedKeys(l.info);
                    if (want != null) {
                        keysLost = false;
                        keys = Keys.open(mm, want, l, keysChannel(), played);
                    }
                } else {
                    usbKeys = openUsbKeys(l);
                }
            }
            sleep(100);
        }
        Keys k = keys;
        keys = null;
        if (k != null) k.close();
        UsbMidi uk = usbKeys;
        usbKeys = null;
        if (uk != null) uk.close();
        Fm1Port l = link;
        link = null;
        if (l != null) l.close();
        dropBle();
    }

    private void dropBle() {
        MidiDevice b = ble;
        ble = null;
        closeQuietly(b);
    }

    private static void closeQuietly(MidiDevice d) {
        try {
            if (d != null) d.close();
        } catch (IOException ignored) {
        }
    }

    /** The name of an FM-1 Android lists over Bluetooth that this app isn't using, or null. */
    private String otherBle(Fm1Port l) {
        MidiDevice mine = ble;
        for (MidiDeviceInfo i : Fm1Port.candidates(mm, true)) {
            if (!Ble.isBluetooth(i)) continue;
            if (l != null && l.info.getId() == i.getId()) continue;
            if (mine != null && mine.getInfo().getId() == i.getId()) continue;
            return Fm1Port.name(i);
        }
        return null;
    }

    /** An FM-1 on USB right now. */
    private boolean usbFm1() {
        for (MidiDeviceInfo i : Fm1Port.candidates(mm, true)) if (!Ble.isBluetooth(i)) return true;
        return false;
    }

    /** While the installer has the FM-1 to itself. */
    private boolean installing() {
        Installer ins = installer;
        return ins != null && !ins.progress.done && ins.progress.error == null;
    }

    /**
     * An FM-1 that names itself first (Open firmware), then one that doesn't (the original), then
     * anything else that might be one (a "USB MIDI" name may as well be a keyboard's interface).
     */
    private Fm1Port openFm1() {
        if (mm == null) return openUsbFm1();
        List<MidiDeviceInfo> all = Fm1Port.candidates(mm, true);
        all.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        for (MidiDeviceInfo info : all) {
            if (bleOff && Ble.isBluetooth(info)) continue;
            Fm1Port p = Fm1Port.open(mm, info, this::onMessage, played.fm1);
            if (p != null) return p;
        }
        return null;
    }

    // ---- straight over USB, where Android has no MIDI service (an onn Google TV box): see UsbMidi

    /** The FM-1 among the USB MIDI devices, opened straight over USB; asks for USB permission the first time. */
    private Fm1Port openUsbFm1() {
        for (android.hardware.usb.UsbDevice d : UsbMidi.devices(um)) {
            if (!Codec.isFm1(UsbMidi.name(d))) continue;
            if (!um.hasPermission(d)) {
                askUsb(d);
                continue;
            }
            Fm1Port p = Fm1Port.openUsb(um, d, this::onMessage, played.fm1);
            if (p != null) return p;
        }
        return null;
    }

    /** The keyboard over straight USB ("" any, "-" none, else its name), playing the FM-1 through `l` like Keys. */
    private UsbMidi openUsbKeys(Fm1Port l) {
        String from = prefs.getString("keys", "");
        if (from.equals("-")) return null;
        for (android.hardware.usb.UsbDevice d : UsbMidi.devices(um)) {
            if (l.usb != null && d.getDeviceId() == l.usb.device.getDeviceId()) continue;
            String n = UsbMidi.name(d);
            if (from.isEmpty() ? Codec.isFm1(n) && !n.toLowerCase().contains("key") : !n.equals(from)) continue;
            if (!um.hasPermission(d)) {
                askUsb(d);
                continue;
            }
            int channel = keysChannel();
            Keys.Filter filter = new Keys.Filter();
            UsbMidi m = UsbMidi.open(um, d, (data, offset, count) -> {
                byte[] pass = filter.pass(data, offset, count, channel);
                if (pass.length == 0) return;
                played.keys.feed(pass, 0, pass.length);
                try {
                    l.send(pass);
                } catch (IOException ignored) {
                    // the FM-1 went: the link notices
                }
            });
            if (m != null) return m;
        }
        return null;
    }

    /** Asks Android for permission to use a USB device (a dialog, answered with the remote); again after 15 s. */
    private void askUsb(android.hardware.usb.UsbDevice d) {
        long now = System.currentTimeMillis();
        Long at = usbAsked.get(d.getDeviceId());
        if (at != null && now - at < 15000) return;
        usbAsked.put(d.getDeviceId(), now);
        android.content.Intent i = new android.content.Intent("com.pfkellogg.fm1mirror.USB_PERMISSION").setPackage(getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, d.getDeviceId(), i, android.app.PendingIntent.FLAG_MUTABLE);
        runOnUiThread(() -> um.requestPermission(d, pi));
    }

    /** USB before Bluetooth; by name within each. */
    private static int rank(MidiDeviceInfo i) {
        String n = Fm1Port.name(i).toLowerCase();
        return (Ble.isBluetooth(i) ? 10 : 0) + (n.contains("fm1") || n.contains("fm-1") ? 0 : n.contains("composite") ? 1 : 2);
    }

    // ---- the keyboard: "" any keyboard plugged in, "-" none, or a device's name

    private MidiDeviceInfo wantedKeys(MidiDeviceInfo fm1) {
        String from = prefs.getString("keys", "");
        if (from.equals("-")) return null;
        for (MidiDeviceInfo i : Keys.sources(mm, fm1))
            if (from.isEmpty() ? Keys.plugged(i) : Fm1Port.name(i).equals(from)) return i;
        return null;
    }

    private void pickKeys() {
        Fm1Port l = link;
        List<String> names = new ArrayList<>();
        if (mm != null) {
            for (MidiDeviceInfo i : Keys.sources(mm, l == null ? null : l.info)) names.add(Fm1Port.name(i));
        } else {
            for (android.hardware.usb.UsbDevice d : UsbMidi.devices(um))
                if (l == null || l.usb == null || d.getDeviceId() != l.usb.device.getDeviceId()) names.add(UsbMidi.name(d));
        }
        List<String> items = new ArrayList<>();
        items.add("Any keyboard plugged in");
        items.addAll(names);
        items.add("None");
        int ch = keysChannel();
        items.add("Channel: " + (ch == 0 ? "as they come" : String.valueOf(ch)) + " (tap to change)");
        int none = items.size() - 2, channels = items.size() - 1;
        new AlertDialog.Builder(this)
                .setTitle("Play the FM-1 from")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == channels) {
                        pickChannel();
                        return;
                    }
                    prefs.edit().putString("keys", which == 0 ? "" : which == none ? "-" : names.get(which - 1)).apply();
                    keysChanged = true;
                })
                .show();
    }

    /** The channel Keys sends on: 1 to 16, or 0 for as they come (an older "everything to channel 1" switch carries over). */
    private int keysChannel() {
        return prefs.getInt("keysChannel", prefs.getBoolean("keysToOne", true) ? 1 : 0);
    }

    private void pickChannel() {
        String[] items = new String[17];
        items[0] = "As they come (each note on its own channel)";
        for (int c = 1; c <= 16; c++)
            items[c] = "Channel " + c + (c == 1 ? " (the FM-1's own: the usual one)" : "");
        new AlertDialog.Builder(this)
                .setTitle("Send the keyboard on")
                .setSingleChoiceItems(items, keysChannel(), (d, which) -> {
                    prefs.edit().putInt("keysChannel", which).apply();
                    keysChanged = true;
                    d.dismiss();
                })
                .show();
    }

    /** The sound playing (null: not known), shown on top of the spyglass. */
    private void setSound(String name) {
        if (java.util.Objects.equals(name, sound)) return;
        sound = name;
        runOnUiThread(() -> {
            if (glass != null) glass.setSound(name);
        });
    }

    /** The spyglass on or off; it stays as it was next time. */
    private void setGlass(boolean on) {
        prefs.edit().putBoolean("glass", on).apply();
        if (on == (glass != null)) return;
        if (on) {
            // The FM-1's sound for the spyglass's waveform: Android calls any audio input the microphone.
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 8);
            glass = new Glass(this, played, prefs, () -> setGlass(false));
            glass.setSound(sound);
            stage.addView(glass);
            stage.post(() -> {
                if (glass != null) glass.place(stage.getWidth(), stage.getHeight());
            });
        } else {
            stage.removeView(glass);
            glass = null;
        }
    }

    /** The Keys button says what's playing the FM-1 now. */
    private void showKeys() {
        Keys k = keys;
        UsbMidi uk = usbKeys;
        String from = prefs.getString("keys", "");
        String s = k != null ? "Keys: " + shortName(k.info) : uk != null ? "Keys: " + uk.name : from.equals("-") ? "Keys: none" : "Keys…";
        if (!s.contentEquals(keysButton.getText())) keysButton.setText(s);
    }

    private static String shortName(MidiDeviceInfo i) {
        String p = i.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT, "");
        return p.isEmpty() ? Fm1Port.name(i) : p;
    }

    private void onMessage(byte[] m) {
        String name = Codec.sound(m);
        if (name != null) {
            setSound(name.isEmpty() ? null : name);
            return;
        }
        String info = Codec.firmwareInfo(m);
        if (info != null) {
            synchronized (this) {
                firmware = info;
            }
            return;
        }
        synchronized (this) {
            if (Codec.apply(pixels, m)) {
                lastScreen = System.currentTimeMillis();
                if (!changed) {
                    changed = true;
                    screen.postInvalidateOnAnimation();
                }
                return;
            }
        }
        Codec.Reply r = Codec.parse(m);
        if (r != null && r.command == 17 && Codec.identity(r.data) != null) {
            synchronized (this) {
                identity = Codec.identity(r.data);
            }
        }
        if (r != null && r.command == 49 && Codec.capability(r.data) != null) build = Codec.capability(r.data).build;
    }

    // ---- what to show

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showKeys();
            update();
            showLink();
            main.postDelayed(this, 300);
        }
    };

    /** The screen, or (when there's no picture) what to do, step by step, in big type. */
    private void update() {
        Installer ins = installer;
        String need = ins != null ? installState(ins) : need();
        boolean picture = need == null;
        screen.setVisibility(picture ? View.VISIBLE : View.GONE);
        panelScroll.setVisibility(picture ? View.GONE : View.VISIBLE);
        if (glass != null) glass.setVisibility(picture ? View.VISIBLE : View.GONE);
        if (picture) {
            shown = "";
            return;
        }
        if (full) setFull(false);
        if (ins != null && stepText != null && need.equals(shown)) {
            stepText.setText(ins.progress.step);
            progressBar.setProgress(ins.progress.percent);
        }
        if (need.equals(shown)) return;
        shown = need;
        panel.removeAllViews();
        stepText = null;
        String[] parts = need.split(":", 2);
        String detail = parts.length > 1 ? parts[1] : "";
        switch (parts[0]) {
            case "plug":
                heading("Waiting for the FM-1…");
                if (mm == null) {
                    // No MIDI service (a Google TV box): straight over USB only, through a powered OTG hub.
                    body("Plug the FM-1 into this box's USB port through a powered USB OTG hub (the hub powers the box too), and turn it on.");
                    body("The first time, the TV asks whether this app may use the FM-1: choose OK with the remote.");
                    break;
                }
                body("Plug it into this phone or tablet with its USB cable (through a USB-C or OTG adapter if the phone needs one), and turn it on.");
                if (bleOff && !prefs.getString("bleAddress", "").isEmpty()) {
                    body("Bluetooth is off in this app: you disconnected it, so the FM-1 is free for other devices.");
                    row(button("Connect over Bluetooth", v -> setBleOff(false)));
                    break;
                }
                if (bleOther != null) {
                    body("Another app on this tablet has \"" + bleOther + "\" open over Bluetooth. Close that app, or tap BT: busy above.");
                    break;
                }
                body(bleScanning ? "Looking for the FM-1 over Bluetooth…"
                        : prefs.getString("bleAddress", "").isEmpty()
                        ? "Or over Bluetooth: turn on the FM-1's Bluetooth MIDI (on its BLUETOOTH page), then:"
                        : "Or over Bluetooth: it connects by itself when the FM-1's Bluetooth MIDI is on (its BLUETOOTH page). A different FM-1?");
                if (!bleScanning) row(button("Find the FM-1 over Bluetooth", v -> findBle()));
                break;
            case "connecting":
                heading("Connecting to the FM-1…");
                break;
            case "original":
                heading("This FM-1 has M-VAVE's original firmware");
                body("To show its screen here it needs the FM-1 Big Screen firmware: M-VAVE's free Official Open firmware plus one small addition that sends the screen to this app. Two steps, and you can go back to the original whenever you like.");
                step1();
                break;
            case "bluetooth":
                heading("No screen over Bluetooth yet");
                body("This FM-1 sends its screen over USB only. The newer Big Screen firmware in this app sends it over Bluetooth too: plug the FM-1 in with its USB cable once, and this app offers to update it (about 2 minutes). Installing over Bluetooth isn't possible.");
                break;
            case "open":
                heading("This FM-1 runs M-VAVE's Open firmware");
                body("Install the Big Screen version of it: the same firmware, plus sending its screen to this app. It takes about 2 minutes.");
                if (confirming) {
                    body("Ready? Keep the FM-1 plugged in and on until it's done, keep this app open, and close other apps that use the FM-1. If you made your own sounds or projects, saving them first on M-VAVE's page is always a good idea.");
                    row(button("Install now", v -> {
                        confirming = false;
                        startInstall();
                    }), button("Cancel", v -> {
                        confirming = false;
                        shown = "";
                    }));
                } else {
                    row(button("Install Big Screen…", v -> {
                        confirming = true;
                        shown = "";
                    }));
                }
                break;
            case "updater":
                heading("The FM-1 is waiting in its updater");
                body("An update stopped partway, so the FM-1 is waiting for the rest of its firmware. Nothing is broken. Keep it plugged in and on, close other apps that use it, and finish installing Big Screen here (about 2 minutes). M-VAVE's page can also finish it.");
                row(button("Finish installing Big Screen", v -> startInstall()));
                break;
            case "other":
                heading("This FM-1 runs another firmware");
                body("It says it is \"" + detail + "\". It's fine to keep using it, and nothing needs changing. This firmware doesn't send its screen, so it can't show here.");
                body("Only if you want to see the screen here: Big Screen replaces this firmware, and it installs over M-VAVE's Official Open firmware, so that comes first.");
                step1();
                break;
            case "unknown":
                Fm1Port ul = link;
                if (ul != null && Ble.isBluetooth(ul.info) && !bleLinked) {
                    heading("Can't reach the FM-1 over Bluetooth");
                    body("This tablet keeps trying \"" + detail + "\", but the FM-1 doesn't answer the radio, so there's no connection yet.");
                    body("On the FM-1, hold HOME and press GLO: BLUETOOTH should be ON and BLE ROLE DEVICE, and it should say WAITING FOR DEVICE. If it says it's connected, another phone, tablet or computer has it: disconnect it there. Turning the FM-1 off and on also helps.");
                    row(button("Stop trying", v -> setBleOff(true)));
                    break;
                }
                if (ul != null && Ble.isBluetooth(ul.info)) {
                    heading("No answer over Bluetooth yet");
                    body("Connected to \"" + detail + "\" over Bluetooth, but the FM-1 isn't answering: no screen and no firmware name. Your firmware doesn't need changing for this.");
                    body("Try: turn the FM-1's Bluetooth off and on (its BLUETOOTH page), close other apps or devices that use it, or plug it in with its USB cable.");
                    row(button("Disconnect Bluetooth", v -> setBleOff(true)));
                    break;
                }
                heading("No screen from the FM-1 yet");
                body("Connected to \"" + detail + "\", but it isn't sending its screen and didn't say which firmware it runs. If you're using a community firmware, it's fine to keep it, and nothing needs changing: it just can't show here.");
                body("Only if you want to see the screen here, or if this FM-1 has M-VAVE's original firmware:");
                step1();
                break;
            case "installing":
                heading("Installing Big Screen…");
                stepText = text(ins.progress.step, 22, TEXT);
                panel.addView(stepText);
                progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progressBar.setMax(100);
                progressBar.setProgress(ins.progress.percent);
                panel.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
                body("Keep the FM-1 plugged in and on, and keep this app open. The FM-1 restarts by itself at the end (about 2 minutes in all).");
                break;
            case "failed":
                heading("The install stopped");
                body(Character.toUpperCase(detail.charAt(0)) + detail.substring(1) + ".");
                body("Leave the FM-1 plugged in and on, close other apps that use it, and tap Try again: it carries on where it stopped. If the FM-1 is plugged in through a hub, plug it straight in. M-VAVE's page (or M-Upgrade on a computer) can also always install firmware on it.");
                row(button("Try again", v -> startInstall()), button("Close", v -> installer = null));
                break;
            case "installed":
                heading("Big Screen is installed");
                body("The FM-1 is coming back; its screen will appear here.");
                row(button("OK", v -> installer = null));
                break;
        }
        TextView back = text("Going back is easy: on M-VAVE's page, Device update › Start update puts back M-VAVE's own Official Open firmware, and Recovery & force update › Restore original V15 the firmware it came with.", 18, DIM);
        back.setPadding(0, dp(24), 0, 0);
        panel.addView(back);
    }

    private String installState(Installer ins) {
        if (ins.progress.error != null) return "failed:" + ins.progress.error;
        if (ins.progress.done) {
            if (screenSince(opened) && link != null) {
                installer = null;
                return need();
            }
            return "installed";
        }
        return "installing";
    }

    private synchronized boolean screenSince(long t) { return lastScreen != 0 && lastScreen >= t; }

    /** Why there's no picture, if there isn't one. */
    private String need() {
        Fm1Port l = link;
        if (l == null) return "plug:" + (bleOff ? "off" : "") + (bleOther != null ? "busy" : ""); // the panel redraws when Bluetooth changes
        // The FM-1 only sends what changes, so a still screen is quiet: once it has come, keep it.
        if (screenSince(opened)) return null;
        if (System.currentTimeMillis() - opened < QUIET) return "connecting";
        String id;
        synchronized (this) {
            id = identity;
        }
        if (id == null) return l.name.toLowerCase().contains("usb composite") ? "original" : "unknown:" + l.name;
        if (Ble.isBluetooth(l.info) && !Codec.isUpdater(id)) return "bluetooth"; // installs need USB
        if (Codec.isOriginal(id)) return "original";
        if (Codec.isOpenFirmware(id)) return "open";
        if (Codec.isUpdater(id)) return "updater";
        return "other:" + id;
    }

    // ---- Bluetooth and the firmware update

    /** The title says when it's over Bluetooth and which firmware says it's running; Update shows when the FM-1 runs another Big Screen build. */
    private void showLink() {
        Fm1Port l = link;
        boolean onBle = l != null && Ble.isBluetooth(l.info);
        String b = build;
        String id, fw;
        synchronized (this) {
            id = identity;
            fw = firmware;
        }
        // Short: the buttons leave the title little room on a tablet.
        String t = onBle ? "Bluetooth" : "FM-1 Mirror";
        if (l != null && fw != null) t = (onBle ? "BT · " : "") + Codec.firmwareName(fw, id);
        if (!t.contentEquals(title.getText())) title.setText(t);
        // Only over M-VAVE's Open firmware: another firmware with the mirror (ChoralRoot + Big Screen) stays.
        boolean offer = l != null && !onBle && installer == null && b != null && !bundled.isEmpty() && !b.equals(bundled)
                && id != null && Codec.isOpenFirmware(id) && screenSince(opened);
        updateButton.setVisibility(offer ? View.VISIBLE : View.GONE);
        // Bluetooth: in use here, used by another app, or switched off here.
        String bt = onBle ? (bleLinked ? "BT: on" : "BT: trying") : bleOther != null ? "BT: busy"
                : bleOff && !prefs.getString("bleAddress", "").isEmpty() ? "BT: off" : null;
        if (bt != null && !bt.contentEquals(btButton.getText())) btButton.setText(bt);
        btButton.setVisibility(bt != null ? View.VISIBLE : View.GONE);
        if (bleLinked != shownLinked) { // the panel says whether the radio link is up
            shownLinked = bleLinked;
            shown = "";
        }
    }

    private boolean shownLinked;

    /** The Bluetooth connection: what's using the FM-1, and a way to let go of it. */
    private void manageBle() {
        Fm1Port l = link;
        boolean onBle = l != null && Ble.isBluetooth(l.info);
        String other = bleOther;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("FM-1 over Bluetooth");
        if (onBle && !bleLinked) {
            b.setMessage("This tablet is trying to reach \"" + l.name + "\" over Bluetooth, but the FM-1 doesn't answer the radio, so there's no connection yet."
                    + "\n\nOn the FM-1, hold HOME and press GLO: BLUETOOTH should be ON and BLE ROLE DEVICE, and it should say WAITING FOR DEVICE. If it says it's connected, another phone, tablet or computer has it.")
                    .setPositiveButton("Stop trying", (d, w) -> setBleOff(true))
                    .setNeutralButton("Forget this FM-1", (d, w) -> forgetBle())
                    .setNegativeButton("Close", null);
        } else if (onBle) {
            String state = screenSince(opened) ? "Its screen is showing here."
                    : "It isn't answering yet (no screen and no firmware name).";
            b.setMessage("This tablet is connected to \"" + l.name + "\" over Bluetooth. " + state
                    + "\n\nThe FM-1 takes one Bluetooth connection at a time. Disconnect to free it for another device, such as a phone or a Mac.")
                    .setPositiveButton("Disconnect", (d, w) -> setBleOff(true))
                    .setNeutralButton("Forget this FM-1", (d, w) -> forgetBle())
                    .setNegativeButton("Close", null);
        } else if (other != null) {
            b.setMessage("Another app on this tablet has \"" + other + "\" open over Bluetooth, so this app can't use it. Close that app, or disconnect the FM-1 in Bluetooth settings.")
                    .setPositiveButton("Bluetooth settings", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                        } catch (RuntimeException ignored) {
                        }
                    })
                    .setNegativeButton("Close", null);
        } else {
            b.setMessage("Bluetooth is off in this app: you disconnected it, so it won't reconnect by itself. The FM-1 is free for other devices.")
                    .setPositiveButton("Connect again", (d, w) -> setBleOff(false))
                    .setNeutralButton("Forget this FM-1", (d, w) -> forgetBle())
                    .setNegativeButton("Close", null);
        }
        readable(b.create());
    }

    private void setBleOff(boolean off) {
        bleOff = off;
        prefs.edit().putBoolean("bleOff", off).apply();
        if (!off) bleTriedAt = 0; // connect now
        shown = "";
        Toast.makeText(this, off ? "Disconnected from the FM-1 over Bluetooth." : "Connecting over Bluetooth…", Toast.LENGTH_SHORT).show();
    }

    /** Disconnects and forgets the FM-1's address: Find the FM-1 over Bluetooth starts again. */
    private void forgetBle() {
        prefs.edit().remove("bleAddress").putBoolean("bleOff", true).apply();
        bleOff = true; // the link thread lets go
        main.postDelayed(() -> {
            bleOff = false;
            prefs.edit().putBoolean("bleOff", false).apply();
            shown = "";
        }, 1500);
        shown = "";
        Toast.makeText(this, "Forgot the FM-1 over Bluetooth.", Toast.LENGTH_SHORT).show();
    }

    private void confirmUpdate() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Update the FM-1's Big Screen firmware?")
                .setMessage("This app has a newer Big Screen firmware: it also sends the screen over Bluetooth. About 2 minutes; keep the FM-1 plugged in and on, and this app open. Your sounds and projects stay.")
                .setPositiveButton("Install now", (d, w) -> startInstall())
                .setNegativeButton("Not now", null)
                .create();
        readable(dialog);
    }

    /** Shows a dialog with its buttons big and yellow: the theme's blue-purple on dark is hard to read. */
    private void readable(AlertDialog dialog) {
        dialog.setOnShowListener(d -> {
            for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                Button b = dialog.getButton(which);
                if (b == null) continue;
                b.setTextColor(0xffffd60a);
                b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
                b.setAllCaps(false);
            }
        });
        dialog.show();
    }

    private void findBle() {
        if (!Ble.allowed(this)) {
            requestPermissions(Ble.permissions(), 7);
            return;
        }
        if (Ble.adapter(this) == null) {
            Toast.makeText(this, "Turn on Bluetooth on this tablet first.", Toast.LENGTH_LONG).show();
            return;
        }
        bleScanning = true;
        shown = "";
        Ble.scan(this, 12000, d -> {
            bleScanning = false;
            shown = "";
            if (d == null) {
                Toast.makeText(this, "No FM-1 found over Bluetooth. Is its Bluetooth MIDI on, and is it close by?", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("bleAddress", d.getAddress()).putBoolean("bleOff", false).apply();
            bleOff = false;
            dropBle();
            bleTriedAt = 0; // connect now
            Toast.makeText(this, "Found the FM-1. Connecting…", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 7 && Ble.allowed(this)) findBle();
    }

    private void step1() {
        body("Step 1 · On M-VAVE's FM1 Workbench page (in Chrome or Edge; a computer is easiest), put M-VAVE's free Official Open firmware on it: Device update › Recovery & force update › tick \"Force flash\" › Connect device › tick \"I have backed up…\" › Force flash. It replaces the FM-1's sounds with M-VAVE's: if you made your own, save them first on the page's Projects & sounds tab.");
        row(button("Open M-VAVE's page", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WORKBENCH)))));
        body("Step 2 · Plug the FM-1 back into this phone or tablet: this app will offer to install Big Screen.");
    }

    private void startInstall() {
        byte[] pkg, id;
        try {
            pkg = asset("FM1_BIG_SCREEN_V14.fwsc");
            id = asset("FM1_BIG_SCREEN_V14.build-identity.json");
        } catch (IOException e) {
            return;
        }
        if (mm == null) {
            android.widget.Toast.makeText(this, "Installing firmware needs a tablet, phone or computer with USB MIDI.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Installer ins = new Installer(mm, pkg, new String(id));
        installer = ins; // the link thread lets go of the FM-1: the installer has it to itself
        shown = "";
        Thread t = new Thread(ins, "FM-1 install");
        t.setDaemon(true);
        t.start();
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0;) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private void setMirror(boolean on) {
        mirrorOn = on;
        prefs.edit().putBoolean("mirror", on).apply();
        showMirrors();
        screen.invalidate();
    }

    private void toggleSmooth() {
        smoothOn = !smoothOn;
        prefs.edit().putBoolean("smooth", smoothOn).apply();
        synchronized (this) {
            changed = true; // repaint it the other way
        }
        setMirror(true); // it's the mirror that's smooth: show it
    }

    /** The buttons say what they do, and the hidden mirror keeps a way back. */
    private void showMirrors() {
        mirrorButton.setText(mirrorOn ? "Hide mirror" : "Show mirror");
        smoothButton.setText(smoothOn ? "Smooth: on" : "Smooth: off");
        showMirror.setVisibility(mirrorOn ? View.GONE : View.VISIBLE);
    }

    private void nextLook() {
        Codec.Look[] all = Codec.Look.values();
        look = all[(look.ordinal() + 1) % all.length];
        lookButton.setText(look.label);
        prefs.edit().putString("look", look.name()).apply();
        synchronized (this) {
            changed = true;
        }
        screen.invalidate();
    }

    /** Full screen: only the FM-1's screen, no bar, no system bars. Tap the screen to come back. */
    private void setFull(boolean on) {
        full = on;
        bar.setVisibility(on ? View.GONE : View.VISIBLE);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        }
    }

    // ---- views (sizes in dp, so a big system font size can't overflow them)

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private void heading(String s) {
        TextView t = text(s, 30, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(12));
        panel.addView(t);
    }

    private void body(String s) {
        TextView t = text(s, 22, TEXT);
        t.setPadding(0, dp(6), 0, dp(6));
        panel.addView(t);
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        b.setTextColor(Color.BLACK);
        b.setBackgroundColor(0xffffd60a);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        panel.addView(r);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** The FM-1's 240x240 screen, the biggest square that fits; nearest scaling keeps its letters crisp blocks,
     *  and Smooth draws a bigger, smoothed copy instead. */
    private final class ScreenView extends View {
        private final Bitmap bitmap = Bitmap.createBitmap(Codec.SIZE, Codec.SIZE, Bitmap.Config.ARGB_8888);
        private final int[] argb = new int[Codec.SIZE * Codec.SIZE];
        private final Paint paint = new Paint();
        private final Rect src = new Rect(0, 0, Codec.SIZE, Codec.SIZE), dst = new Rect();
        private final int smoothSize = Codec.SIZE * Codec.SMOOTH_SCALE;
        private final Bitmap smooth = Bitmap.createBitmap(smoothSize, smoothSize, Bitmap.Config.ARGB_8888);
        private final Rect smoothSrc = new Rect(0, 0, smoothSize, smoothSize);

        ScreenView(Context c) {
            super(c);
            paint.setFilterBitmap(false);
            paint.setAntiAlias(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            synchronized (MainActivity.this) {
                if (changed) {
                    changed = false;
                    for (int i = 0; i < argb.length; i++) argb[i] = look.paint(pixels[i]);
                    bitmap.setPixels(argb, 0, Codec.SIZE, 0, 0, Codec.SIZE, Codec.SIZE);
                    if (smoothOn) smooth.setPixels(Codec.smooth(argb, Codec.SIZE), 0, smoothSize, 0, 0, smoothSize, smoothSize);
                }
            }
            canvas.drawColor(BG);
            if (!mirrorOn) return;
            int side = Math.min(getWidth(), getHeight());
            int x = (getWidth() - side) / 2, y = (getHeight() - side) / 2;
            dst.set(x, y, x + side, y + side);
            paint.setFilterBitmap(smoothOn);
            canvas.drawBitmap(smoothOn ? smooth : bitmap, smoothOn ? smoothSrc : src, dst, paint);
        }
    }
}
