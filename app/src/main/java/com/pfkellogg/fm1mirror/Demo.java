package com.pfkellogg.fm1mirror;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Debug builds only (the ".demo" app, never the release): plays notes as if the FM-1's own keys played them, so a demo
 * video can be recorded with nothing pressed by hand. From the computer:
 *   adb shell am broadcast -a com.pfkellogg.fm1mirror.DEMO --es seq "0,903c64;800,803c00"
 * "seq" is "ms,hex-bytes;..." with each time counted from the broadcast; the bytes go where the FM-1's own MIDI goes
 * (the spyglass and the Mellotron).
 */
final class Demo {
    static final String ACTION = "com.pfkellogg.fm1mirror.DEMO";

    static void startIfDebug(Context ctx, Played.Stream shown) {
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        Mellotron.DIAG = true;
        // its own thread, timed to the millisecond: on the screen's thread notes waited behind the drawing (up to 0.12 s late)
        android.os.HandlerThread th = new android.os.HandlerThread("Demo notes", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        th.start();
        Handler h = new Handler(th.getLooper());
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String seq = i.getStringExtra("seq");
                if (seq == null) return;
                long t0 = android.os.SystemClock.uptimeMillis();
                for (String step : seq.split(";")) {
                    String[] p = step.trim().split(",");
                    if (p.length != 2) continue;
                    byte[] m = hex(p[1].trim());
                    h.postAtTime(() -> {
                        Mellotron mel = Mellotron.active;
                        if (mel != null) mel.demo(m);
                        shown.feed(m, 0, m.length);       // the spyglass: a stream of its own, not the FM-1's
                    }, t0 + Long.parseLong(p[0].trim()));
                }
                Log.i("FM1Mirror", "demo: " + seq.split(";").length + " steps");
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, null, h, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f, null, h);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
