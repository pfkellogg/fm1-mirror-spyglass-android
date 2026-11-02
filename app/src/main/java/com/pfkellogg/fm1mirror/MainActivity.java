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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FM-1 Mirror for Android: the FM-1's screen, big, sent over USB by the FM-1 Big Screen firmware
 * (the desktop app's src/main.rs). Plug the FM-1 into the phone or tablet, open the app.
 * A keyboard plugged in too plays the FM-1 through it (Keys): Android lets only one app send to the FM-1.
 * What it plays shows in the spyglass (Glass), beside the screen.
 */
public class MainActivity extends Activity {
    static final String WORKBENCH = "https://open.m-vave.com/fm1-workbench";
    private static final long HELLO_EVERY = 900, QUIET = 4000;
    private static final int TEXT = Color.WHITE, DIM = 0xffb0b0b0, BG = Color.BLACK;

    private MidiManager mm;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Shared with the MIDI threads; guarded by `this`.
    private final int[] pixels = new int[Codec.SIZE * Codec.SIZE];
    private boolean changed;
    private long lastScreen; // when the last screen message came (0: not since the link opened)
    private String identity;

    private volatile Fm1Port link;
    private volatile long opened;
    private volatile boolean lost, running;
    private volatile Installer installer;
    private volatile Keys keys;
    private volatile boolean keysLost, keysChanged;
    private final Played played = new Played();
    private Glass glass;

    private Codec.Look look = Codec.Look.COLOR;
    private boolean confirming, full;
    private String shown = "";

    private ScreenView screen;
    private LinearLayout bar, panel;
    private FrameLayout stage;
    private ScrollView panelScroll;
    private Button lookButton, keysButton;
    private TextView stepText;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mm = (MidiManager) getSystemService(Context.MIDI_SERVICE);
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
        TextView title = text("FM-1 Mirror + Spyglass", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        keysButton = button("Keys", v -> pickKeys());
        bar.addView(keysButton);
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

        mm.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceRemoved(MidiDeviceInfo info) {
                Fm1Port l = link;
                if (l != null && l.info.getId() == info.getId()) lost = true;
                Keys k = keys;
                if (k != null && k.info.getId() == info.getId()) keysLost = true;
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
        long lookedAt = 0, helloAt = 0, keysLookedAt = 0;
        boolean askedIdentity = false, askedBuild = false, first = true;
        while (running) {
            Fm1Port l = link;
            if (l != null && (lost || installing())) {
                link = null;
                l.close();
                l = null;
            }
            long now = System.currentTimeMillis();
            if (l == null && !installing() && now - lookedAt >= 2000) {
                lookedAt = now;
                l = openFm1();
                if (l != null) {
                    synchronized (this) {
                        lastScreen = 0;
                        identity = null;
                    }
                    lost = false;
                    opened = System.currentTimeMillis();
                    askedIdentity = askedBuild = false;
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
            if (l != null && k == null && now - keysLookedAt >= 2000) {
                keysLookedAt = now;
                MidiDeviceInfo want = wantedKeys(l.info);
                if (want != null) {
                    keysLost = false;
                    keys = Keys.open(mm, want, l, prefs.getBoolean("keysToOne", true), played);
                }
            }
            sleep(100);
        }
        Keys k = keys;
        keys = null;
        if (k != null) k.close();
        Fm1Port l = link;
        link = null;
        if (l != null) l.close();
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
        List<MidiDeviceInfo> all = Fm1Port.candidates(mm, true);
        all.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        for (MidiDeviceInfo info : all) {
            Fm1Port p = Fm1Port.open(mm, info, this::onMessage);
            if (p != null) return p;
        }
        return null;
    }

    private static int rank(MidiDeviceInfo i) {
        String n = Fm1Port.name(i).toLowerCase();
        return n.contains("fm1") || n.contains("fm-1") ? 0 : n.contains("composite") ? 1 : 2;
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
        for (MidiDeviceInfo i : Keys.sources(mm, l == null ? null : l.info)) names.add(Fm1Port.name(i));
        List<String> items = new ArrayList<>();
        items.add("Any keyboard plugged in");
        items.addAll(names);
        items.add("None");
        boolean toOne = prefs.getBoolean("keysToOne", true);
        items.add(toOne ? "Channels: all to channel 1 (tap to send them as they come)" : "Channels: as they come (tap to move all to channel 1)");
        int none = items.size() - 2, channels = items.size() - 1;
        new AlertDialog.Builder(this)
                .setTitle("Play the FM-1 from")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == channels) prefs.edit().putBoolean("keysToOne", !toOne).apply();
                    else prefs.edit().putString("keys", which == 0 ? "" : which == none ? "-" : names.get(which - 1)).apply();
                    keysChanged = true;
                })
                .show();
    }

    /** The spyglass on or off; it stays as it was next time. */
    private void setGlass(boolean on) {
        prefs.edit().putBoolean("glass", on).apply();
        if (on == (glass != null)) return;
        if (on) {
            glass = new Glass(this, played, prefs, () -> setGlass(false));
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
        String from = prefs.getString("keys", "");
        String s = k != null ? "Keys: " + shortName(k.info) : from.equals("-") ? "Keys: none" : "Keys…";
        if (!s.contentEquals(keysButton.getText())) keysButton.setText(s);
    }

    private static String shortName(MidiDeviceInfo i) {
        String p = i.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT, "");
        return p.isEmpty() ? Fm1Port.name(i) : p;
    }

    private void onMessage(byte[] m) {
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
    }

    // ---- what to show

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showKeys();
            update();
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
                body("Plug it into this phone or tablet with its USB cable (through a USB-C or OTG adapter if the phone needs one), and turn it on.");
                break;
            case "connecting":
                heading("Connecting to the FM-1…");
                break;
            case "original":
                heading("This FM-1 has M-VAVE's original firmware");
                body("To show its screen here it needs the FM-1 Big Screen firmware: M-VAVE's free Official Open firmware plus one small addition that sends the screen to this app. Two steps, and you can go back to the original whenever you like.");
                step1();
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
                body("It says it is \"" + detail + "\". Big Screen installs over M-VAVE's Official Open firmware, so that comes first.");
                step1();
                break;
            case "unknown":
                heading("No screen from the FM-1 yet");
                body("Connected to \"" + detail + "\", but it isn't sending its screen and didn't say which firmware it runs. With M-VAVE's original firmware:");
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
        if (l == null) return "plug";
        // The FM-1 only sends what changes, so a still screen is quiet: once it has come, keep it.
        if (screenSince(opened)) return null;
        if (System.currentTimeMillis() - opened < QUIET) return "connecting";
        String id;
        synchronized (this) {
            id = identity;
        }
        if (id == null) return l.name.toLowerCase().contains("usb composite") ? "original" : "unknown:" + l.name;
        if (Codec.isOriginal(id)) return "original";
        if (Codec.isOpenFirmware(id)) return "open";
        if (Codec.isUpdater(id)) return "updater";
        return "other:" + id;
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

    /** The FM-1's 240x240 screen, the biggest square that fits; nearest scaling keeps its letters crisp blocks. */
    private final class ScreenView extends View {
        private final Bitmap bitmap = Bitmap.createBitmap(Codec.SIZE, Codec.SIZE, Bitmap.Config.ARGB_8888);
        private final int[] argb = new int[Codec.SIZE * Codec.SIZE];
        private final Paint paint = new Paint();
        private final Rect src = new Rect(0, 0, Codec.SIZE, Codec.SIZE), dst = new Rect();

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
                }
            }
            canvas.drawColor(BG);
            int side = Math.min(getWidth(), getHeight());
            int x = (getWidth() - side) / 2, y = (getHeight() - side) / 2;
            dst.set(x, y, x + side, y + side);
            canvas.drawBitmap(bitmap, src, dst, paint);
        }
    }
}
