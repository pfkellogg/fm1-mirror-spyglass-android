package com.pfkellogg.fm1mirror;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Debug builds only: a pretend FM-1 running Big Screen (like tools/simulate_fm1.py on a computer).
 * Answers "what are you" (17) as FM-1_8000014 and, while it hears hellos, sends a test screen:
 * colour bars, a yellow frame and a moving bar, as rows of at most 70 bytes like the firmware's.
 * With a file named "plain-open" in the app's files folder it sends no screen, like Open firmware
 * without Big Screen (adb shell run-as com.pfkellogg.fm1mirror touch files/plain-open).
 */
public class Simulator extends MidiDeviceService {
    private volatile long heard;
    private Thread painter;

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        Codec.Sysex sysex = new Codec.Sysex(this::message);
        return new MidiReceiver[] {new MidiReceiver() {
            @Override
            public void onSend(byte[] data, int offset, int count, long timestamp) {
                sysex.feed(data, offset, count);
            }
        }};
    }

    private synchronized void message(byte[] m) {
        if (m.length == 6 && m[1] == 0x7d && m[2] == 'F' && m[3] == 'S') {
            heard = System.currentTimeMillis();
            if (painter == null && !new java.io.File(getFilesDir(), "plain-open").exists()) {
                painter = new Thread(this::paint, "simulator");
                painter.start();
            }
            return;
        }
        Codec.Reply r = Codec.parse(m);
        if (r != null && r.command == 17) {
            byte[] id = new byte[27];
            byte[] name = "FM-1_8000014".getBytes();
            System.arraycopy(name, 0, id, 0, name.length);
            send(Codec.frame(17, id));
        }
    }

    private void send(byte[] m) {
        try {
            getOutputPortReceivers()[0].send(m, 0, m.length);
        } catch (IOException ignored) {
        }
    }

    private static int pixel(int x, int y, int frame) {
        if (x < 4 || y < 4 || x >= 236 || y >= 236) return 0xffe0; // yellow frame
        if (y > 200 && y < 216 && x > 20 && x < 20 + (frame * 7) % 200) return 0x07e0; // moving green bar
        if (y < 60) return new int[] {0xf800, 0x07e0, 0x001f, 0xffff}[x / 60]; // colour bars
        if (y >= 100 && y < 140 && (x / 20 + y / 20) % 2 == 0) return 0xffff; // checks
        return 0;
    }

    private void paint() {
        int frame = 0;
        while (System.currentTimeMillis() - heard < 3000) {
            for (int y = 0; y < 240; y++) {
                ByteArrayOutputStream m = new ByteArrayOutputStream();
                int x = 0;
                while (x < 240) {
                    int start = x;
                    m.reset();
                    m.write(0xf0); m.write(0x7d); m.write('F'); m.write('S'); m.write(1);
                    m.write(y & 127); m.write(y >> 7); m.write(start & 127); m.write(start >> 7);
                    while (x < 240 && m.size() + 5 <= 70) {
                        int c = pixel(x, y, frame), run = 1;
                        while (x + run < 240 && run < 127 && pixel(x + run, y, frame) == c) run++;
                        m.write(run); m.write(c & 127); m.write((c >> 7) & 127); m.write(c >> 14);
                        x += run;
                    }
                    m.write(0xf7);
                    send(m.toByteArray());
                }
            }
            frame++;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        synchronized (this) {
            painter = null;
        }
    }
}
