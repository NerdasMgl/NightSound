package com.yehang.app.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * YAMNet（Google，Apache 2.0）的纯 Java 推理实现：16 kHz 波形 → 521 类 AudioSet 声音事件得分。
 * 网络是 MobileNetV1：log-mel 特征（96×64）→ 1 个普通卷积 + 13 个深度可分离卷积 → 全局平均 → 全连接 → sigmoid。
 * 权重已折叠 BatchNorm，以 float16 存放在 assets/yamnet.bin（约 7.4 MB）。
 * 输入一次 15600 个样本（0.975 秒），没有额外依赖，手机上不需要 TFLite 运行库。
 */
public final class YamNet {
    public static final int WAVE = 15600, NCLASS = 521;
    public static final int C_SPEECH = 0, C_WHISPER = 12, C_BREATHING = 36, C_SNORING = 38, C_GASP = 39, C_SNORT = 41,
            C_COUGH = 42, C_THROAT = 43, C_SNEEZE = 44, C_CHEWING = 49, C_BITING = 50, C_TOOTHBRUSH = 369, C_ETOOTHBRUSH = 370,
            C_SQUEAK = 355, C_SCRATCH = 468, C_SCRAPE = 469, C_RUB = 470, C_CREAK = 480, C_CRUNCH = 493, C_SILENCE = 494,
            C_BABBLING = 4, C_LAUGH = 13, C_CRY = 19, C_GROAN = 33, C_MOAN = 22, C_SIGH = 23, C_SNIFF = 45;

    private static final int FRAMES = 96, BANDS = 64, WIN = 400, HOP = 160, NFFT = 512;
    private static final int[] STRIDE = { 1, 2, 1, 2, 1, 2, 1, 1, 1, 1, 1, 2, 1 };
    private static final int[] CH = { 32, 64, 128, 128, 256, 256, 512, 512, 512, 512, 512, 512, 1024, 1024 };

    private final float[] w1, b1, dense, denseB;
    private final float[][] dw = new float[13][], dwB = new float[13][], pw = new float[13][], pwB = new float[13][];

    private final Fft fft = new Fft(NFFT);
    private final float[] hann = new float[WIN];
    private final int[] melStart = new int[BANDS];
    private final float[][] melW = new float[BANDS][];

    // 复用的缓冲区（推理在单线程里调用）
    private final float[] re = new float[NFFT], im = new float[NFFT];
    private final float[] feat = new float[FRAMES * BANDS];
    private final float[] bufA = new float[48 * 32 * 64], bufB = new float[48 * 32 * 64];
    private final float[] pooled = new float[1024];

    public YamNet(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[4];
        d.readFully(magic);
        if (magic[0] != 'Y' || magic[1] != 'M' || magic[2] != 'N' || magic[3] != '1') throw new IOException("模型文件格式不对");
        w1 = read(d, 3 * 3 * 32); b1 = read(d, 32);
        int cin = 32;
        for (int i = 0; i < 13; i++) {
            int cout = CH[i + 1];
            dw[i] = read(d, 9 * cin); dwB[i] = read(d, cin);
            pw[i] = read(d, cin * cout); pwB[i] = read(d, cout);
            cin = cout;
        }
        dense = read(d, 1024 * NCLASS); denseB = read(d, NCLASS);
        for (int i = 0; i < WIN; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / WIN));
        buildMel();
    }

    private static float[] read(DataInputStream d, int n) throws IOException {
        byte[] b = new byte[n * 2];
        d.readFully(b);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = half((b[2 * i] & 0xff) | ((b[2 * i + 1] & 0xff) << 8));
        return out;
    }

    static float half(int h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
        int bits;
        if (e == 0) {
            if (m == 0) bits = s << 31;
            else { // 次正规数
                int ee = 127 - 15 + 1;
                while ((m & 0x400) == 0) { m <<= 1; ee--; }
                m &= 0x3ff;
                bits = (s << 31) | (ee << 23) | (m << 13);
            }
        } else if (e == 31) bits = (s << 31) | (0xff << 23) | (m << 13);
        else bits = (s << 31) | ((e - 15 + 127) << 23) | (m << 13);
        return Float.intBitsToFloat(bits);
    }

    private static double hz2mel(double f) { return 1127.0 * Math.log(1.0 + f / 700.0); }

    private void buildMel() {
        int nSpec = NFFT / 2 + 1;
        double lo = hz2mel(125.0), hi = hz2mel(7500.0);
        double[] edges = new double[BANDS + 2];
        for (int i = 0; i < edges.length; i++) edges[i] = lo + (hi - lo) * i / (BANDS + 1);
        for (int b = 0; b < BANDS; b++) {
            double l = edges[b], c = edges[b + 1], u = edges[b + 2];
            int first = -1, last = -1;
            float[] tmp = new float[nSpec];
            for (int k = 1; k < nSpec; k++) {             // 第 0 个频点置零
                double f = hz2mel(8000.0 * k / (nSpec - 1));
                double w = Math.max(0.0, Math.min((f - l) / (c - l), (u - f) / (u - c)));
                tmp[k] = (float) w;
                if (w > 0) { if (first < 0) first = k; last = k; }
            }
            if (first < 0) { melStart[b] = 0; melW[b] = new float[0]; continue; }
            melStart[b] = first;
            melW[b] = java.util.Arrays.copyOfRange(tmp, first, last + 1);
        }
    }

    /** wave: 至少 WAVE 个样本，取前 15600 个。返回 521 个 sigmoid 得分。 */
    public float[] scores(float[] wave) {
        logMel(wave);
        float[] cur = bufA, nxt = bufB;
        // layer1：3×3 卷积，步长 2，1→32
        int h = FRAMES, w = BANDS;
        int oh = 48, ow = 32;
        conv1(feat, h, w, cur, oh, ow);
        h = oh; w = ow;
        int cin = 32;
        for (int i = 0; i < 13; i++) {
            int s = STRIDE[i], cout = CH[i + 1];
            oh = (h + s - 1) / s; ow = (w + s - 1) / s;
            dwConv(cur, h, w, cin, s, dw[i], dwB[i], nxt, oh, ow);
            float[] t = cur; cur = nxt; nxt = t;
            pwConv(cur, oh * ow, cin, cout, pw[i], pwB[i], nxt);
            t = cur; cur = nxt; nxt = t;
            h = oh; w = ow; cin = cout;
        }
        int p = h * w;
        for (int c = 0; c < 1024; c++) {
            float sum = 0;
            for (int q = 0; q < p; q++) sum += cur[q * 1024 + c];
            pooled[c] = sum / p;
        }
        float[] out = new float[NCLASS];
        System.arraycopy(denseB, 0, out, 0, NCLASS);
        for (int i = 0; i < 1024; i++) {
            float v = pooled[i];
            if (v == 0f) continue;
            int base = i * NCLASS;
            for (int o = 0; o < NCLASS; o++) out[o] += v * dense[base + o];
        }
        for (int o = 0; o < NCLASS; o++) out[o] = (float) (1.0 / (1.0 + Math.exp(-out[o])));
        return out;
    }

    private void logMel(float[] wave) {
        for (int f = 0; f < FRAMES; f++) {
            int off = f * HOP;
            for (int i = 0; i < WIN; i++) re[i] = wave[off + i] * hann[i];
            for (int i = WIN; i < NFFT; i++) re[i] = 0;
            java.util.Arrays.fill(im, 0f);
            fft.transform(re, im);
            for (int k = 0; k <= NFFT / 2; k++) re[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            int base = f * BANDS;
            for (int b = 0; b < BANDS; b++) {
                float[] mw = melW[b];
                int st = melStart[b];
                float s = 0;
                for (int j = 0; j < mw.length; j++) s += re[st + j] * mw[j];
                feat[base + b] = (float) Math.log(s + 0.001);
            }
        }
    }

    private void conv1(float[] x, int h, int w, float[] out, int oh, int ow) {
        // SAME，步长 2：总填充 = (oh-1)*2+3-h，前面填一半，余下在后面
        int padH = Math.max((oh - 1) * 2 + 3 - h, 0), padW = Math.max((ow - 1) * 2 + 3 - w, 0);
        int top = padH / 2, left = padW / 2;
        for (int y = 0; y < oh; y++) {
            for (int xx = 0; xx < ow; xx++) {
                int o = (y * ow + xx) * 32;
                for (int c = 0; c < 32; c++) out[o + c] = b1[c];
                for (int ky = 0; ky < 3; ky++) {
                    int iy = y * 2 + ky - top;
                    if (iy < 0 || iy >= h) continue;
                    for (int kx = 0; kx < 3; kx++) {
                        int ix = xx * 2 + kx - left;
                        if (ix < 0 || ix >= w) continue;
                        float v = x[iy * w + ix];
                        int wb = (ky * 3 + kx) * 32;
                        for (int c = 0; c < 32; c++) out[o + c] += v * w1[wb + c];
                    }
                }
                for (int c = 0; c < 32; c++) if (out[o + c] < 0) out[o + c] = 0;
            }
        }
    }

    private static void dwConv(float[] x, int h, int w, int ch, int s, float[] k, float[] bias, float[] out, int oh, int ow) {
        int padH = Math.max((oh - 1) * s + 3 - h, 0), padW = Math.max((ow - 1) * s + 3 - w, 0);
        int top = padH / 2, left = padW / 2;
        for (int y = 0; y < oh; y++) {
            for (int xx = 0; xx < ow; xx++) {
                int o = (y * ow + xx) * ch;
                for (int c = 0; c < ch; c++) out[o + c] = bias[c];
                for (int ky = 0; ky < 3; ky++) {
                    int iy = y * s + ky - top;
                    if (iy < 0 || iy >= h) continue;
                    for (int kx = 0; kx < 3; kx++) {
                        int ix = xx * s + kx - left;
                        if (ix < 0 || ix >= w) continue;
                        int ib = (iy * w + ix) * ch, kb = (ky * 3 + kx) * ch;
                        for (int c = 0; c < ch; c++) out[o + c] += x[ib + c] * k[kb + c];
                    }
                }
                for (int c = 0; c < ch; c++) if (out[o + c] < 0) out[o + c] = 0;
            }
        }
    }

    private static void pwConv(float[] x, int positions, int cin, int cout, float[] w, float[] bias, float[] out) {
        for (int p = 0; p < positions; p++) {
            int o = p * cout, ib = p * cin;
            System.arraycopy(bias, 0, out, o, cout);
            for (int i = 0; i < cin; i++) {
                float v = x[ib + i];
                if (v == 0f) continue;                     // ReLU 之后有很多 0，直接跳过
                int wb = i * cout;
                for (int c = 0; c < cout; c++) out[o + c] += v * w[wb + c];
            }
            for (int c = 0; c < cout; c++) if (out[o + c] < 0) out[o + c] = 0;
        }
    }
}
