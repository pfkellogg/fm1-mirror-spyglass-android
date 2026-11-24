package com.pfkellogg.fm1mirror;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Rich sounds: a real sampled instrument for the sound the FM-1 plays, out of this device, the way the Mellotron is
 * (the FM-1's 1 MB of flash has no room for samples). The desktop app's synth, rustysynth (MIT, sinshu), built for
 * Android (rich-jni, librich.so), playing GeneralUser GS (S. Christian Collins: 128 General MIDI instruments, 32 MB,
 * free to use), downloaded once. "Auto" follows the sound the firmware names, with the desktop's rules (src/rich.rs:
 * keep the two lists the same), or one picked from the 128.
 */
final class Rich {
    static final String ID = "rich";
    static final String URL_SF2 = "https://github.com/mrbumpy409/GeneralUser-GS/raw/main/GeneralUser-GS.sf2";
    private static final String FILE = "GeneralUser-GS.sf2", TAG = "FM1Mirror";
    /** GeneralUser GS leaves about 20 dB of headroom: up to the Mellotron's level (the desktop's 6) */
    private static final float GAIN = 6f;
    private static final boolean NATIVE;

    static {
        boolean ok;
        try {
            System.loadLibrary("rich");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "rich: no native synth on this device", t);
            ok = false;
        }
        NATIVE = ok;
    }

    private static native long nNew(String path, int rate);
    private static native void nFree(long h);
    private static native void nProgram(long h, int program);
    private static native void nNoteOn(long h, int key, int vel);
    private static native void nNoteOff(long h, int key);
    private static native void nAllOff(long h);
    private static native void nRender(long h, float[] out, int n, float gain);

    /** The instrument picked (null: Auto, which follows the FM-1's sound name); the FM-1's sound name now. */
    static volatile Integer pick;
    static volatile String fm1Sound = "";

    // the desktop's WORDS (src/rich.rs), in its order: the first word found in a sound's name picks the instrument
    private static final String[] WORDS = {
        "electric piano", "4", "e.piano", "4", "epiano", "4", "e piano", "4", "rhodes", "4", "wurli", "5",
        "dx piano", "5", "tine", "4", "grand", "0", "piano", "0", "upright", "0", "honky", "3",
        "harpsi", "6", "clav", "7", "celest", "8", "glock", "9", "music box", "10", "musicbox", "10",
        "vibra", "11", "marimba", "12", "xylo", "13", "tubular", "14", "bell", "14", "chime", "14",
        "dulcimer", "15", "drawbar", "16", "b3", "16", "hammond", "16", "perc org", "17", "rock org", "18",
        "church", "19", "pipe org", "19", "organ", "16", "accordion", "21", "harmonica", "22", "bandoneon", "23",
        "nylon", "24", "acoustic gt", "25", "guitar", "25", "jazz gt", "26", "fretless", "35", "slap", "36",
        "acid", "38", "303", "38", "sub bass", "38", "synth bass", "38", "bass", "33", "violin", "40",
        "viola", "41", "cello", "42", "contrabass", "43", "trem str", "44", "pizz", "45", "harp", "46",
        "timpani", "47", "slow str", "49", "syn str", "50", "synth str", "50", "ensemble", "48", "string", "48",
        "choir", "52", "aah", "52", "ooh", "53", "vox", "54", "voice", "53", "orch hit", "55",
        "trumpet", "56", "trombone", "57", "tuba", "58", "mute tp", "59", "horn", "60", "syn brass", "62",
        "brass", "61", "soprano", "64", "alto", "65", "tenor", "66", "sax", "66", "bari", "67",
        "oboe", "68", "english h", "69", "bassoon", "70", "clarinet", "71", "piccolo", "72", "flute", "73",
        "recorder", "74", "pan", "75", "bottle", "76", "shakuhachi", "77", "whistle", "78", "ocarina", "79",
        "square", "80", "chip", "80", "sid", "80", "8-bit", "80", "saw", "81", "lead", "81",
        "calliope", "82", "chiff", "83", "charang", "84", "fifth", "86", "new age", "88", "warm", "89",
        "polysynth", "90", "poly", "90", "pad", "89", "halo", "94", "sweep", "95", "drone", "89",
        "ambient", "88", "rain", "96", "soundtrack", "97", "crystal", "98", "atmos", "99", "bright", "100",
        "goblin", "101", "echo", "102", "sci", "103", "sitar", "104", "banjo", "105", "shamisen", "106",
        "koto", "107", "kalimba", "108", "mbira", "108", "bagpipe", "109", "fiddle", "110", "shanai", "111",
        "steel drum", "114", "taiko", "116", "tom", "117", "synth drum", "118", "mellotron", "49", "b3 organ", "16",
        "cs-1", "50", "scintilla", "90", "synthex", "90", "obervave", "90", "prophet", "90", "s1vave", "90",
        "monk", "52", "fomni", "46", "omnichord", "46", "fimba", "108", "gator", "81", "flat-20", "81",
        "ms-20", "81", "niagara", "89", "eminent", "50", "artihive", "90", "fmini", "5", "fm6", "5",
        "fm-106", "4", "atek303", "38", "dub-1", "38", "amb-1", "89", "keys", "0", "minipops", "118",
        "sloop", "0", "melodee", "89", "erik37", "0", "loom", "48", "polly", "0", "synth", "90"
    };

    static int programFor(String name) {
        String n = " " + (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)) + " ";
        if (n.contains("organ") || n.contains(" b3")) {             // an organ's switches change its kind
            if (n.contains(" od ") || n.contains("overdrive")) return 18;
            if (n.contains(" perc")) return 17;
        }
        for (int i = 0; i < WORDS.length; i += 2) if (n.contains(WORDS[i])) return Integer.parseInt(WORDS[i + 1]);
        return 48;
    }

    static int program() {
        Integer p = pick;
        return p != null ? p : programFor(fm1Sound);
    }

    static File file(Context ctx) {
        return new File(Sounds.dir(ctx, ID), FILE);
    }

    /** On this device and playable (the synth runs here, the SoundFont is downloaded). */
    static boolean downloaded(Context ctx) {
        return NATIVE && file(ctx).length() > 1_000_000;
    }

    static boolean supported() {
        return NATIVE;
    }

    /** Downloads GeneralUser GS (network: off the screen's thread); `progress` gets 0..100. */
    static void download(Context ctx, java.util.function.IntConsumer progress) throws IOException {
        File dir = Sounds.dir(ctx, ID);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room for it");
        File part = new File(dir, FILE + ".part");
        String url = URL_SF2;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 5; hop++) {                          // (GitHub sends it on from raw to raw.githubusercontent)
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code / 100 == 3 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new IOException("the download said " + code);
            break;
        }
        long total = c.getContentLengthLong(), got = 0;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                int pc = total > 0 ? (int) (got * 100 / total) : -1;
                if (pc != last && progress != null) progress.accept(pc);
                last = pc;
            }
        } finally {
            c.disconnect();
        }
        byte[] head = new byte[12];
        try (java.io.FileInputStream in = new java.io.FileInputStream(part)) {
            if (in.read(head) != 12 || !new String(head, 0, 4, "US-ASCII").equals("RIFF") || !new String(head, 8, 4, "US-ASCII").equals("sfbk")) {
                part.delete();
                throw new IOException("that wasn't a SoundFont");
            }
        }
        if (!part.renameTo(file(ctx))) throw new IOException("couldn't keep it");
    }

    static void remove(Context ctx) {
        file(ctx).delete();
        new File(Sounds.dir(ctx, ID), FILE + ".part").delete();
    }

    // ---- one synth

    private long h;
    private int playing = -1;

    private Rich(long h) {
        this.h = h;
    }

    /** The synth at `rate` Hz (reads 32 MB: off the screen's thread), or null. */
    static Rich open(Context ctx, int rate) {
        if (!downloaded(ctx)) return null;
        long h = nNew(file(ctx).getAbsolutePath(), rate);
        return h == 0 ? null : new Rich(h);
    }

    private void follow() {
        int p = program();
        if (p != playing) {
            playing = p;
            nProgram(h, p);
        }
    }

    void noteOn(int key, int vel) {
        if (h == 0) return;
        follow();
        nNoteOn(h, key, vel);
    }

    void noteOff(int key) {
        if (h != 0) nNoteOff(h, key);
    }

    void allOff() {
        if (h != 0) nAllOff(h);
    }

    /** `n` mono samples (±1) into `out`. */
    void render(float[] out, int n) {
        if (h != 0) nRender(h, out, n, GAIN);
    }

    void close() {
        if (h != 0) nFree(h);
        h = 0;
    }
}
