package com.pfkellogg.fm1mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps FM-1 Big Screen alive while another app (flowkey, say) is on screen, so the keyboard keeps playing the FM-1.
 * It does nothing itself: the link and the keyboards stay in MainActivity; this only shows "FM-1 Big Screen is on",
 * which stops Android (Android Go above all) from closing the app to save memory. Its Quit button closes the app.
 */
public class Background extends Service {
    private static final String CHANNEL = "background";
    static final String QUIT = "quit";

    static void start(Context c) {
        Intent i = new Intent(c, Background.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException ignored) { // (not allowed to start one now: the app just isn't kept alive)
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, Background.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Playing in the background", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int imm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent quit = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).setAction(QUIT);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("FM-1 Big Screen is on")
                .setContentText("Your keyboard keeps playing the FM-1. Tap to go back.")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, imm))
                .addAction(new Notification.Action.Builder(null, "Quit", PendingIntent.getActivity(this, 1, quit, imm)).build());
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, b.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else
                startForeground(1, b.build());
        } catch (RuntimeException e) { // (Android said no: the app works as before, just not kept alive)
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Swiped away from the recent apps: let go, as before. */
    @Override
    public void onTaskRemoved(Intent root) {
        stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
