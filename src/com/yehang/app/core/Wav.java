package com.yehang.app.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** 把 16 kHz 单声道 PCM 存成 8 kHz 的 WAV，并做峰值归一化（增益上限 30 dB），方便回放安静的鼾声。 */
public final class Wav {
    private Wav() { }

    public static final int OUT_RATE = 8000;

    public static void write(File f, short[] pcm16k, int off, int len) throws IOException {
        int n = len / 2;
        short[] o = new short[n];
        int peak = 1;
        for (int i = 0; i < n; i++) {
            int v = (pcm16k[off + 2 * i] + pcm16k[off + 2 * i + 1]) / 2;
            o[i] = (short) v;
            int a = v < 0 ? -v : v;
            if (a > peak) peak = a;
        }
        float gain = Math.min(31.6f, 0.7f * 32767f / peak);
        if (gain < 1f) gain = 1f;
        FileOutputStream fo = new FileOutputStream(f);
        BufferedOutputStream out = new BufferedOutputStream(fo, 1 << 15);
        try {
            int dataLen = n * 2;
            byte[] h = new byte[44];
            put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
            le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, OUT_RATE); le32(h, 28, OUT_RATE * 2);
            le16(h, 32, 2); le16(h, 34, 16); put(h, 36, "data"); le32(h, 40, dataLen);
            out.write(h);
            byte[] b = new byte[2];
            for (int i = 0; i < n; i++) {
                int v = Math.round(o[i] * gain);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                b[0] = (byte) v; b[1] = (byte) (v >> 8);
                out.write(b);
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void put(byte[] b, int p, String s) { for (int i = 0; i < s.length(); i++) b[p + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); }
}
