package com.pfkellogg.fm1mirror;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * The FM-1 over Bluetooth MIDI: it advertises as "FM-1_BLE" once Bluetooth MIDI is on (its
 * BLUETOOTH page). Found once with a scan, then opened by its remembered address; while this app
 * holds it open, Android lists it with the other MIDI devices and Fm1Port opens it like USB.
 * The Big Screen firmware answers on the port that asked, so the screen comes back over Bluetooth
 * too (slower than USB). Firmware is only ever installed over USB.
 */
final class Ble {
    private Ble() {
    }

    static String[] permissions() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static boolean allowed(Context ctx) {
        for (String p : permissions())
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    static BluetoothAdapter adapter(Context ctx) {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        return a != null && a.isEnabled() ? a : null;
    }

    static boolean isBluetooth(MidiDeviceInfo info) {
        return info.getType() == MidiDeviceInfo.TYPE_BLUETOOTH;
    }

    private static boolean looksLikeFm1(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.contains("fm-1") || n.contains("fm1");
    }

    /** Looks for an FM-1 for up to `ms`; `found` gets it, or null. Call with permission. */
    @SuppressWarnings("MissingPermission")
    static void scan(Context ctx, long ms, Consumer<BluetoothDevice> found) {
        Handler ui = new Handler(Looper.getMainLooper());
        BluetoothAdapter a = adapter(ctx);
        BluetoothLeScanner s = a == null ? null : a.getBluetoothLeScanner();
        if (s == null) {
            ui.post(() -> found.accept(null));
            return;
        }
        boolean[] done = new boolean[1];
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (done[0] || !looksLikeFm1(name)) return;
                done[0] = true;
                s.stopScan(this);
                found.accept(r.getDevice());
            }

            @Override
            public void onScanFailed(int error) {
                if (done[0]) return;
                done[0] = true;
                found.accept(null);
            }
        };
        s.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        ui.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            try {
                s.stopScan(cb);
            } catch (RuntimeException ignored) {
            }
            found.accept(null);
        }, ms);
    }

    /**
     * The radio link to `address` is really up. Android opens a Bluetooth MIDI device before it has
     * reached it, and keeps retrying every 30 s if the FM-1 never answers, so "opened" isn't "connected".
     */
    @SuppressWarnings("MissingPermission")
    static boolean linked(Context ctx, String address) {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = adapter(ctx);
        if (bm == null || a == null || address.isEmpty() || !allowed(ctx)) return false;
        try {
            return bm.getConnectionState(a.getRemoteDevice(address), android.bluetooth.BluetoothProfile.GATT)
                    == android.bluetooth.BluetoothProfile.STATE_CONNECTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens the FM-1 at `address` over Bluetooth MIDI; `opened` gets the device, or null. */
    @SuppressWarnings("MissingPermission")
    static void open(Context ctx, MidiManager mm, String address, Consumer<MidiDevice> opened) {
        BluetoothAdapter a = adapter(ctx);
        if (a == null || address.isEmpty() || !allowed(ctx)) {
            opened.accept(null);
            return;
        }
        try {
            mm.openBluetoothDevice(a.getRemoteDevice(address), opened::accept, Fm1Port.callbacks());
        } catch (RuntimeException e) {
            opened.accept(null);
        }
    }
}
