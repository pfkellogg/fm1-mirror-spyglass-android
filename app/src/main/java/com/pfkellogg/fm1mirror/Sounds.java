package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Mellotron's sounds: the ones built into the app (the full version has flute, strings and choir in
 * assets/mellotron/ID/; the lite version has none), and the ones downloaded from the list of sounds online
 * (sounds/sounds.json in this repo; each sound a zip on a GitHub release, its 35 strips 043.flac ... 077.flac), kept in
 * the app's files (files/sounds/ID/). New sounds are added to the list, so every version of the app can get them
 * without an update. The app goes online only when the list is opened or a sound is downloaded.
 */
final class Sounds {
    static final String INDEX = "https://raw.githubusercontent.com/pfkellogg/fm1-mirror-spyglass-android/main/sounds/sounds.json";
    private static final String TAG = "FM1Mirror";
    /** The built-in order first; others after, by name. */
    private static final List<String> ORDER = Arrays.asList("flute", "strings", "choir");

    /** A sound on the list online. */
    static final class Pack {
        String id, name, url, sha256;
        long bytes;
        int version;
    }

    private Sounds() {
    }

    static File dir(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), "sounds"), id);
    }

    /** Built into this version of the app. */
    static boolean bundled(Context ctx, String id) {
        try {
            String[] f = ctx.getAssets().list("mellotron/" + id);
            return f != null && f.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean downloaded(Context ctx, String id) {
        return new File(dir(ctx, id), "077.flac").isFile() && new File(dir(ctx, id), "043.flac").isFile();
    }

    /** The sounds this device has now: built in or downloaded, in the button's order. */
    static List<String> installed(Context ctx) {
        List<String> ids = new ArrayList<>();
        try {
            String[] b = ctx.getAssets().list("mellotron");
            if (b != null) for (String id : b) if (bundled(ctx, id) && !ids.contains(id)) ids.add(id);
        } catch (IOException ignored) {
        }
        File[] d = new File(ctx.getFilesDir(), "sounds").listFiles();
        if (d != null) for (File f : d) if (downloaded(ctx, f.getName()) && !ids.contains(f.getName())) ids.add(f.getName());
        ids.sort((a, b) -> {
            int ia = ORDER.indexOf(a), ib = ORDER.indexOf(b);
            if (ia >= 0 || ib >= 0) return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
            return a.compareTo(b);
        });
        return ids;
    }

    /** What a strip's cache files are kept under: changes when the sound does (an update, a new app version). */
    static String stamp(Context ctx, String id) {
        if (bundled(ctx, id)) {
            try {
                return "app" + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            } catch (Exception e) {
                return "app";
            }
        }
        return "dl" + new File(dir(ctx, id), "077.flac").lastModified();
    }

    /** The list of sounds online (network: call off the screen's thread). */
    static List<Pack> list() throws IOException {
        String json = new String(get(INDEX), "UTF-8");
        List<Pack> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONObject(json).getJSONArray("sounds");
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Pack p = new Pack();
                p.id = o.getString("id");
                p.name = o.optString("name", p.id);
                p.url = o.getString("url");
                p.sha256 = o.optString("sha256", "");
                p.bytes = o.optLong("bytes", 0);
                p.version = o.optInt("version", 1);
                if (p.id.matches("[a-z0-9_-]+")) out.add(p);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("the list of sounds can't be read", e);
        }
        return out;
    }

    /** Downloads a sound, checks it, and puts it in place (network and disk: off the screen's thread). */
    static void download(Context ctx, Pack p) throws IOException {
        byte[] zip = get(p.url);
        if (!p.sha256.isEmpty() && !p.sha256.equalsIgnoreCase(sha256(zip))) throw new IOException("the download was damaged");
        File tmp = new File(new File(ctx.getFilesDir(), "sounds"), p.id + ".part");
        deleteAll(tmp);
        if (!tmp.mkdirs()) throw new IOException("no room for it");
        int n = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();          // no folders, no "..": just the strips
                if (e.isDirectory() || !name.matches("[0-9]{3}\\.flac")) continue;
                try (FileOutputStream out = new FileOutputStream(new File(tmp, name))) {
                    int r;
                    while ((r = z.read(buf)) > 0) out.write(buf, 0, r);
                }
                n++;
            }
        }
        if (n < 35) {
            deleteAll(tmp);
            throw new IOException("the sound was incomplete (" + n + " of 35 strips)");
        }
        File dest = dir(ctx, p.id);
        deleteAll(dest);
        if (!tmp.renameTo(dest)) throw new IOException("it couldn't be put in place");
        Log.i(TAG, "sounds: " + p.id + " downloaded (" + zip.length + " bytes)");
    }

    static void remove(Context ctx, String id) {
        deleteAll(dir(ctx, id));
        deleteAll(new File(new File(ctx.getCacheDir(), "mellotron"), id));
    }

    private static byte[] get(String url) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "FM-1-Big-Screen");
            int code = c.getResponseCode();
            if (code / 100 == 3) {                                       // GitHub sends release files elsewhere
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new IOException("the server said " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                return out.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static String sha256(byte[] b) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteAll(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteAll(c);
        f.delete();
    }
}
