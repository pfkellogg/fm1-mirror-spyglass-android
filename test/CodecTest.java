package com.pfkellogg.fm1mirror;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs on a computer: test/run.sh. The same checks as the desktop app's tests. */
public class CodecTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok  " + what);
    }

    /** The firmware's encoding, for a row segment. */
    static byte[] encode(int y, int x, int[] px) {
        List<Integer> m = new ArrayList<>(Arrays.asList(0xf0, 0x7d, (int) 'F', (int) 'S', 1, y & 127, y >> 7, x & 127, x >> 7));
        for (int i = 0; i < px.length;) {
            int run = 1;
            while (i + run < px.length && run < 127 && px[i + run] == px[i]) run++;
            int c = px[i];
            m.addAll(Arrays.asList(run, c & 127, (c >> 7) & 127, c >> 14));
            i += run;
        }
        m.add(0xf7);
        byte[] out = new byte[m.size()];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (int) m.get(i);
        return out;
    }

    public static void main(String[] args) throws Exception {
        int[] screen = new int[Codec.SIZE * Codec.SIZE];
        int[] px = new int[200];
        for (int i = 0; i < 200; i++) px[i] = i % 7 < 3 ? 0xef5d : (i * 977) & 0xffff;
        check(Codec.apply(screen, encode(200, 30, px)), "row applies");
        check(Arrays.equals(Arrays.copyOfRange(screen, 200 * 240 + 30, 200 * 240 + 230), px) && screen[200 * 240 + 29] == 0, "row round trip");
        check(!Codec.apply(screen, Codec.HELLO) && !Codec.apply(screen, encode(240, 0, new int[] {1, 2, 3})), "ignores other messages");

        // Pieces, glued messages and a clock byte in the middle.
        List<byte[]> got = new ArrayList<>();
        Codec.Sysex s = new Codec.Sysex(got::add);
        byte[] stream = {(byte) 0xf0, 1, 2, (byte) 0xf8, (byte) 0xf7, (byte) 0x90, 60, 100, (byte) 0xf0, 3, (byte) 0xf7};
        for (int i = 0; i < stream.length; i += 3) s.feed(stream, i, Math.min(3, stream.length - i));
        check(got.size() == 2 && Arrays.equals(got.get(0), new byte[] {(byte) 0xf0, 1, 2, (byte) 0xf7}) && got.get(1).length == 3, "sysex in pieces");

        check(Arrays.equals(Codec.rgb(0xffff), new int[] {255, 255, 255}) && Arrays.equals(Codec.rgb(0x07ff), new int[] {0, 255, 255}), "colours");

        byte[] payload = new byte[200];
        for (int i = 0; i < 200; i++) payload[i] = (byte) i;
        Codec.Reply r = Codec.parse(Codec.frame(48, payload));
        check(r != null && r.command == 48 && Arrays.equals(r.data, payload), "frame round trip");
        r = Codec.parse(Codec.frame(17, new byte[0]));
        check(r != null && r.command == 17 && r.data.length == 0, "empty frame");

        // A capability reply captured from an FM-1 running Big Screen.
        int[] cap = {240, 0, 50, 69, 121, 2, 0, 0, 35, 77, 6, 69, 9, 112, 0, 64, 0, 0, 4, 0, 0, 0, 2, 64, 122, 50, 80, 14, 106, 24, 88, 26, 113, 24, 66, 14, 41, 104, 93, 43, 87, 116, 37, 63, 65, 16, 118, 46, 117, 39, 55, 90, 79, 65, 123, 13, 8, 22, 124, 53, 7, 96, 7, 247};
        byte[] m = new byte[cap.length];
        for (int i = 0; i < m.length; i++) m[i] = (byte) cap[i];
        r = Codec.parse(m);
        check(r != null && r.command == 49 && Codec.capability(r.data) != null, "real capability reply");

        String dir = args[0];
        Codec.Package p = Codec.inspect(Files.readAllBytes(Paths.get(dir, "FM1_BIG_SCREEN_V14.fwsc")));
        String id = new String(Files.readAllBytes(Paths.get(dir, "FM1_BIG_SCREEN_V14.build-identity.json")));
        check(p.product.equals("FM-1_8000014") && p.image.length > 400_000, "package is sound (" + p.image.length + " bytes)");
        check(Codec.buildId(id).length() == 64 && Codec.capability(r.data).build.length() == 64, "build id " + Codec.buildId(id).substring(0, 8));
        check(Codec.isFm1("Jieli Technology USB Composite Device") && Codec.isFm1("FM1 Community") && !Codec.isFm1("MIDI Bridge"), "names");
        System.out.println("all ok");
    }
}
