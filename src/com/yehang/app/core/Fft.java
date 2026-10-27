package com.yehang.app.core;

/** 原地复数 FFT（基 2，长度必须是 2 的幂）。 */
public final class Fft {
    private final int n;
    private final float[] cos, sin;
    private final int[] rev;

    public Fft(int n) {
        this.n = n;
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = (float) Math.cos(-2 * Math.PI * i / n);
            sin[i] = (float) Math.sin(-2 * Math.PI * i / n);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    public void transform(float[] re, float[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    int m = j + half;
                    float tr = re[m] * cos[k] - im[m] * sin[k];
                    float ti = re[m] * sin[k] + im[m] * cos[k];
                    re[m] = re[j] - tr; im[m] = im[j] - ti;
                    re[j] += tr; im[j] += ti;
                }
            }
        }
    }
}
