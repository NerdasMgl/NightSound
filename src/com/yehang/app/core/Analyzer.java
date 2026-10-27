package com.yehang.app.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 流式音频分析器：输入 16 kHz 单声道 16 位 PCM，输出声音事件（鼾声、疑似呼吸暂停、梦话、磨牙、咳嗽、其他）。
 *
 *  1. 自适应噪声底：最近 90 秒的每秒电平取第 15 百分位。
 *  2. 能量门限切出“声音片段”：电平高于噪声底一定幅度即开始，回落后结束（带迟滞）。只有这些片段才会送去分类，平时不耗电。
 *  3. 分类：有端侧模型（YamNet）时，取片段里的 0.975 秒窗口（音量归一化后）送入模型，
 *     用 AudioSet 的“打鼾 / 咳嗽 / 说话 / 呼吸 / 刮擦”等类别得分决定类型；没有模型时退回到手写的信号特征规则。
 *  4. 鼾声：得分高的单独确认，得分中等的需要成串（≥3 个、间隔 1.8–10 秒）才确认。
 *  5. 疑似呼吸暂停：一串规律的鼾声之后出现明显长于呼吸周期的静默，期间没有咳嗽、说话、翻身，随后鼾声恢复。
 *  6. 磨牙：模型对“牙刷 / 刮擦 / 摩擦 / 咀嚼”类声音的得分，加上持续时间或节律；实验性质，没有真实磨牙数据验证。
 * 电平 dB 是“相对值”：dBFS + 90，没有经过校准。
 */
public final class Analyzer {
    public static final int SR = 16000, N = 1024, HOP = 512;
    public static final int T_SNORE = 1, T_APNEA = 2, T_TALK = 3, T_GRIND = 4, T_COUGH = 5, T_OTHER = 6;

    public static final class Ev {
        public int type;
        public long startSample, endSample;
        public float peakDb, meanDb;   // 绝对相对电平
        public float extra;            // 疑似暂停：静默秒数
        public float conf;             // 0..1
        public long startMs() { return startSample * 1000L / SR; }
        public long endMs() { return endSample * 1000L / SR; }
    }

    public interface Listener {
        void onEvent(Ev e);
        void onSecond(float levelDb, float floorDb);
    }

    // ---- 参数 ----
    private static final int GAP_HOPS = 8;            // 0.26 秒内的回落不算结束
    private static final int MIN_HOPS = 5;            // 最短 0.16 秒
    private static final int MAX_HOPS = 800;          // 单个片段最长约 25 秒
    private static final long CHAIN_GAP = 12L * SR;   // 鼾声成串的最大间隔
    private static final int FLOOR_SECS = 90;
    private static final int RING = 32 * SR;          // 保留最近 32 秒音频，供分类取窗口

    static final boolean DEBUG = System.getProperty("yh.debug") != null;
    static final boolean DUAL = System.getProperty("yh.dual") != null;
    private final Listener lis;
    private final YamNet model;
    private final boolean async;
    private float onThr, offThr;
    private boolean grindEnabled = true;

    private final Fft fft = new Fft(N);
    private final float[] hann = new float[N];
    private final float[] hopBuf = new float[HOP], prev = new float[HOP], window = new float[N];
    private final float[] re = new float[N], im = new float[N], re2 = new float[N], im2 = new float[N];
    private int hopFill;
    private long hopIndex;
    private final float[] ring;

    // 噪声底
    private final float[] secLevels = new float[FLOOR_SECS];
    private int secN, secPos, secHops;
    private double secAcc;
    private float floor = 20f;
    private float lastLevel;
    private int tickCount;

    // 片段状态
    private boolean active;
    private int quiet;
    private long segStart, segLast;
    private int segFrames, segVoiced;
    private double sumPow, sumW, sumLow, sumCent;
    private float peakE;
    private long peakHop;

    // ---- 分类侧（可能在工作线程里运行）----
    private static final class Cand {
        long start, end; float peak, mean, floorAt; int tier; float low, cent;
        float dur, crest, peakAbove, meanAbove, voiced, riseMs;
        float pSnore, pGrind, pSpeech, pCough, pBreath;
    }
    private static final int K_SEG = 0, K_TICK = 1, K_FLUSH = 2, K_STOP = 3;
    private static final class Job {
        int kind; Cand c; float[][] wins; long now; CountDownLatch latch;
    }
    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<Job>();
    private Thread worker;

    private final ArrayList<Cand> chain = new ArrayList<Cand>();
    private final ArrayList<long[]> accepted = new ArrayList<long[]>(); // {start,end}
    private final ArrayList<Float> acceptedPeak = new ArrayList<Float>();
    private final ArrayList<Long> disturbances = new ArrayList<Long>();  // 咳嗽 / 说话 / 其他较响声音的时刻（样本）
    private final ArrayList<Cand> grind = new ArrayList<Cand>();

    public Analyzer(Listener l, int sensitivity) { this(l, sensitivity, null, false); }

    /** model 不为空时用端侧模型分类；async 为真时模型推理放在后台线程里（手机上用），否则同步（测试用）。 */
    public Analyzer(Listener l, int sensitivity, YamNet model, boolean async) {
        lis = l;
        this.model = model;
        this.async = async && model != null;
        ring = new float[RING];
        setSensitivity(sensitivity);
        for (int i = 0; i < N; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        if (this.async) {
            worker = new Thread(new Runnable() { public void run() { workLoop(); } }, "yehang-model");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    /** 0 低 / 1 中 / 2 高。有模型时门限更低，让轻微的鼾声也能送去分类。 */
    public void setSensitivity(int s) {
        if (model != null) onThr = s == 0 ? 8f : (s == 2 ? 4.5f : 6f);
        else onThr = s == 0 ? 11f : (s == 2 ? 7f : 9f);
        offThr = onThr - (model != null ? 2.5f : 4f);
    }

    public void setGrindEnabled(boolean b) { grindEnabled = b; }

    public float floorDb() { return floor; }
    public float levelDb() { return lastLevel; }
    public long samplesFed() { return hopIndex * HOP + hopFill; }

    public void feed(short[] pcm, int len) {
        for (int i = 0; i < len; i++) {
            hopBuf[hopFill++] = pcm[i] / 32768f;
            if (hopFill == HOP) {
                System.arraycopy(hopBuf, 0, ring, (int) ((hopIndex * HOP) % RING), HOP);
                hop();
                hopFill = 0;
            }
        }
    }

    /** 结束时调用：等后台分类处理完，并确认尚未决定的鼾声链和磨牙链。 */
    public void flush() {
        if (active) { endSegment(); active = false; }
        Job j = new Job();
        j.kind = K_FLUSH; j.latch = new CountDownLatch(1);
        if (async) {
            queue.add(j);
            try { j.latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        } else {
            process(j);
        }
    }

    /** 停止后台线程。 */
    public void release() {
        if (!async) return;
        Job j = new Job(); j.kind = K_STOP;
        queue.add(j);
    }

    private void submit(Job j) {
        if (!async) { process(j); return; }
        if (j.kind == K_SEG && queue.size() > 150) return;   // 积压太多（手机太慢）就丢弃新的片段
        queue.add(j);
    }

    private void workLoop() {
        while (true) {
            Job j;
            try { j = queue.take(); } catch (InterruptedException e) { return; }
            if (j.kind == K_STOP) return;
            try { process(j); } catch (Throwable t) { if (DEBUG) t.printStackTrace(); if (j.latch != null) j.latch.countDown(); }
        }
    }

    // ================================================================= 音频线程：切片段
    private void hop() {
        System.arraycopy(prev, 0, window, 0, HOP);
        System.arraycopy(hopBuf, 0, window, HOP, HOP);
        System.arraycopy(hopBuf, 0, prev, 0, HOP);
        double ms = 0;
        for (int i = 0; i < N; i++) ms += window[i] * window[i];
        ms /= N;
        float e = (float) (10 * Math.log10(ms + 1e-12) + 90);
        if (e < 0) e = 0;
        lastLevel = e;
        long hi = hopIndex++;

        secAcc += ms; secHops++;
        if (secHops >= 31) {
            float s = (float) (10 * Math.log10(secAcc / secHops + 1e-12) + 90);
            if (s < 0) s = 0;
            secLevels[secPos] = s; secPos = (secPos + 1) % FLOOR_SECS; if (secN < FLOOR_SECS) secN++;
            secAcc = 0; secHops = 0;
            updateFloor();
            if (lis != null) lis.onSecond(s, floor);
            Job t = new Job(); t.kind = K_TICK; t.now = (hi + 1) * (long) HOP;
            submit(t);
        }

        if (!active) {
            if (e > floor + onThr) { active = true; quiet = 0; startSegment(hi); accumulate(e, ms); }
        } else {
            if (e < floor + offThr) {
                quiet++;
                if (quiet >= GAP_HOPS) { endSegment(); active = false; quiet = 0; }
            } else {
                quiet = 0; segLast = hi; accumulate(e, ms);
                if (segLast - segStart > MAX_HOPS) { endSegment(); active = false; }
            }
        }
    }

    private void updateFloor() {
        int n = secN;
        float[] tmp = Arrays.copyOf(secLevels, n);
        Arrays.sort(tmp);
        float f = n < 8 ? tmp[0] : tmp[(int) (n * 0.15f)];
        floor = Math.max(f, 12f);
    }

    private void startSegment(long hi) {
        segStart = hi; segLast = hi; segFrames = 0; segVoiced = 0;
        sumPow = sumW = sumLow = sumCent = 0; peakE = 0; peakHop = hi;
    }

    private void accumulate(float e, double ms) {
        for (int i = 0; i < N; i++) { re[i] = window[i] * hann[i]; im[i] = 0; }
        fft.transform(re, im);
        double tot = 0, low = 0, cn = 0;
        float[] p = re2;
        for (int k = 0; k <= N / 2; k++) {
            float pk = re[k] * re[k] + im[k] * im[k];
            p[k] = pk;
            if (k >= 4 && k <= 512) {
                tot += pk;
                if (k < 32) low += pk;
                cn += pk * (k * 15.625);
            }
        }
        for (int k = 1; k < N / 2; k++) p[N - k] = p[k];
        java.util.Arrays.fill(im2, 0f);
        fft.transform(re2, im2);
        float ac0 = re2[0] + 1e-12f, best = 0;
        for (int lag = 40; lag < 200; lag++) { float v = re2[lag] / ac0; if (v > best) best = v; }
        if (best > 0.45f) segVoiced++;
        if (tot > 0) {
            sumLow += (low / tot) * ms; sumCent += (cn / tot) * ms; sumW += ms;
        }
        sumPow += ms; segFrames++;
        if (e > peakE) { peakE = e; peakHop = segLast; }
    }

    private void endSegment() {
        long first = segStart, last = segLast;
        int hops = (int) (last - first + 1);
        if (hops < MIN_HOPS || segFrames == 0 || sumW <= 0) return;
        float dur = hops * HOP / (float) SR;
        float meanAbs = (float) (10 * Math.log10(sumPow / segFrames + 1e-12) + 90);
        float peakAbs = peakE;
        float crest = peakAbs - meanAbs;
        float peakAbove = peakAbs - floor, meanAbove = meanAbs - floor;
        float low = (float) (sumLow / sumW), cent = (float) (sumCent / sumW);
        float voiced = segVoiced / (float) segFrames;
        float riseMs = (peakHop - first) * HOP * 1000f / SR;
        if (DEBUG) System.out.printf("seg t=%.1fs dur=%.2f peakAbove=%.1f meanAbove=%.1f crest=%.1f low=%.2f cent=%.0f voiced=%.2f rise=%.0fms floor=%.1f%n", first * HOP / (float) SR, dur, peakAbove, meanAbove, crest, low, cent, voiced, riseMs, floor);
        if (crest < 3f && dur > 6f) return; // 稳态噪声（风扇等），不算事件

        long s0 = first * HOP, s1 = (last + 1) * HOP;
        Cand c = new Cand();
        c.start = s0; c.end = s1; c.peak = peakAbs; c.mean = meanAbs; c.floorAt = floor; c.low = low; c.cent = cent;
        c.dur = dur; c.crest = crest; c.peakAbove = peakAbove; c.meanAbove = meanAbove; c.voiced = voiced; c.riseMs = riseMs;
        Job j = new Job();
        j.kind = K_SEG; j.c = c;
        if (model != null) j.wins = pickWindows(s0, s1);
        submit(j);
    }

    /**
     * 从环形缓冲里取这个片段的模型输入窗口（每个 15600 个样本）。
     * 模型对窗口对齐很敏感（鼾声被窗口边缘切掉时得分会掉到 0），所以短片段取前后错开的 3 个窗口，
     * 长片段按 0.49 秒的步长铺满（最多 8 个）。每个窗口做音量归一化。
     */
    private float[][] pickWindows(long s0, long s1) {
        final int W = YamNet.WAVE;
        final long HOPW = 7800;
        long fed = hopIndex * HOP;
        long a = s0 - SR * 3L / 10, b = s1 + SR * 3L / 10;
        long len = b - a;
        ArrayList<Long> starts = new ArrayList<Long>();
        if (len <= W) {
            long mid = (a + b) / 2;
            for (int k = -1; k <= 1; k++) starts.add(mid - W / 2 + k * (SR * 3L / 10));
        } else {
            int n = (int) Math.min(8, (len - W + HOPW - 1) / HOPW + 1);
            for (int i = 0; i < n; i++) starts.add(n == 1 ? a : a + (len - W) * i / (n - 1));
        }
        long oldest = Math.max(0, fed - RING);
        ArrayList<float[]> out = new ArrayList<float[]>();
        long lastSt = Long.MIN_VALUE;
        for (long st : starts) {
            if (st + W > fed) st = fed - W;
            if (st < oldest) st = oldest;
            if (st == lastSt) continue;
            lastSt = st;
            float[] w = new float[W];
            if (st >= 0 && st + W <= fed) {
                for (int k = 0; k < W; k++) w[k] = ring[(int) ((st + k) % RING)];
            }
            if (DUAL) { float[] raw = w.clone(); normalize(w); out.add(w); if (!java.util.Arrays.equals(raw, w)) out.add(raw); }
            else { normalize(w); out.add(w); }
        }
        return out.toArray(new float[0][]);
    }

    /** 把窗口的均方根拉到 0.05（最多放大 30 倍），让很轻的鼾声和大声的鼾声对模型看起来一样。 */
    static void normalize(float[] w) {
        double s = 0;
        for (float v : w) s += v * v;
        double rms = Math.sqrt(s / w.length);
        if (rms < 1e-6) return;
        float g = (float) Math.min(30.0, 0.05 / rms);
        for (int i = 0; i < w.length; i++) { float v = w[i] * g; w[i] = v > 1f ? 1f : (v < -1f ? -1f : v); }
    }

    // ================================================================= 分类侧
    private void process(Job j) {
        switch (j.kind) {
            case K_SEG: classify(j); break;
            case K_TICK:
                if (!chain.isEmpty() && j.now - chain.get(chain.size() - 1).end > CHAIN_GAP) finalizeChain();
                if (!grind.isEmpty() && j.now - grind.get(grind.size() - 1).end > 6L * SR) finalizeGrind();
                break;
            case K_FLUSH:
                finalizeChain(); finalizeGrind();
                if (j.latch != null) j.latch.countDown();
                break;
            default: break;
        }
    }

    private void classify(Job j) {
        Cand c = j.c;
        if (model != null) { classifyModel(j); return; }
        // ---- 没有模型：手写规则 ----
        float dur = c.dur, low = c.low, cent = c.cent, crest = c.crest, peakAbove = c.peakAbove, meanAbove = c.meanAbove, voiced = c.voiced, riseMs = c.riseMs;
        boolean strict = low >= 0.55f && cent <= 750f && dur >= 0.3f && dur <= 6f && crest <= 7.5f && meanAbove >= 8f;
        boolean cough = peakAbove >= 20f && dur <= 3f && cent >= 500f && low <= 0.65f && riseMs <= 300f && crest >= 4f;
        boolean talk = voiced >= 0.45f && low <= 0.65f && cent >= 250f && cent <= 2800f && dur >= 0.4f && dur <= 12f && meanAbove >= 7f;
        boolean relaxed = low >= 0.5f && cent <= 900f && dur >= 0.25f && dur <= 6f && crest <= 10f && meanAbove >= 6f && voiced <= 0.8f;
        boolean scratch = cent >= 1800f && low <= 0.12f && dur >= 0.15f && dur <= 2f && voiced <= 0.35f && peakAbove >= 8f;
        if (strict) { c.tier = 2; addSnore(c); return; }
        if (cough) { disturb(c); emit(T_COUGH, c, 0.6f, 0); return; }
        if (talk) { disturb(c); emit(T_TALK, c, Math.min(1f, voiced), 0); return; }
        if (relaxed) { c.tier = 1; addSnore(c); return; }
        if (scratch && grindEnabled) { addGrind(c); return; }
        if (peakAbove >= 18f) { disturb(c); emit(T_OTHER, c, 0.4f, 0); }
    }

    private static float max(float[] s, int... idx) {
        float m = 0;
        for (int i : idx) if (s[i] > m) m = s[i];
        return m;
    }

    private void classifyModel(Job j) {
        Cand c = j.c;
        float[] agg = new float[YamNet.NCLASS];
        for (float[] w : j.wins) {
            float[] sc = model.scores(w);
            for (int i = 0; i < agg.length; i++) if (sc[i] > agg[i]) agg[i] = sc[i];
            if (agg[YamNet.C_SNORING] >= 0.9f || agg[YamNet.C_COUGH] >= 0.9f || agg[YamNet.C_SPEECH] >= 0.9f) break;   // 已经很确定
        }
        c.pSnore = agg[YamNet.C_SNORING];
        float pSnort = Math.max(agg[YamNet.C_SNORT], agg[YamNet.C_GASP]);
        c.pBreath = agg[YamNet.C_BREATHING];
        c.pCough = max(agg, YamNet.C_COUGH, YamNet.C_THROAT, YamNet.C_SNEEZE);
        c.pSpeech = max(agg, YamNet.C_SPEECH, YamNet.C_WHISPER, YamNet.C_BABBLING, 1, 2, 3);
        float pVoc = max(agg, YamNet.C_GROAN, YamNet.C_MOAN, YamNet.C_LAUGH, YamNet.C_CRY, 21);
        c.pGrind = Math.max(max(agg, YamNet.C_TOOTHBRUSH, YamNet.C_ETOOTHBRUSH, YamNet.C_SCRAPE, YamNet.C_RUB, YamNet.C_CRUNCH),
                0.8f * max(agg, YamNet.C_SCRATCH, YamNet.C_CHEWING, YamNet.C_BITING));
        if (DEBUG) { int[] top = new int[3]; float[] tv = new float[3]; for (int i = 0; i < agg.length; i++) for (int k = 0; k < 3; k++) if (agg[i] > tv[k]) { for (int m = 2; m > k; m--) { tv[m] = tv[m-1]; top[m] = top[m-1]; } tv[k] = agg[i]; top[k] = i; break; }
            System.out.printf("  top %d:%.2f %d:%.2f %d:%.2f%n", top[0], tv[0], top[1], tv[1], top[2], tv[2]); }
        if (DEBUG) System.out.printf("  cls t=%.1fs dur=%.2f snore=%.2f snort=%.2f breath=%.2f cough=%.2f speech=%.2f voc=%.2f grind=%.2f cent=%.0f%n",
                c.start / (float) SR, c.dur, c.pSnore, pSnort, c.pBreath, c.pCough, c.pSpeech, pVoc, c.pGrind, c.cent);

        float top1 = 0;
        for (float v : agg) if (v > top1) top1 = v;
        // 说话/咳嗽的得分要接近整体最高分；别的声音（敲门、水声……）得分压倒性更高时，不采纳
        if (c.pCough >= 0.35f && c.pCough >= 0.8f * c.pSnore && c.pCough >= 0.6f * top1) { disturb(c); emit(T_COUGH, c, c.pCough, 0); return; }
        float talkP = c.pSpeech;   // 只有真正的"说话"才算说梦话；呻吟、哼叫、笑、哭只当作干扰
        float topNonHuman = 0;   // 67 号起是动物、乐器、物体、环境声……（人体声音在 0–66 号）
        for (int i = 67; i < 494; i++) if (agg[i] > topNonHuman) topNonHuman = agg[i];
        if (talkP >= 0.5f && talkP > c.pSnore && talkP >= 0.9f * topNonHuman) { disturb(c); emit(T_TALK, c, talkP, 0); return; }
        boolean snoreLike = c.pSnore >= 0.2f || pSnort >= 0.45f;
        if (!snoreLike && pVoc >= 0.5f && pVoc >= 0.9f * topNonHuman) { disturb(c); return; }
        // 呼吸声比打鼾得分更高时，是普通的呼吸
        if (snoreLike && c.pBreath > 1.5f * c.pSnore && c.pSnore < 0.5f && pSnort < 0.6f) snoreLike = false;
        if (snoreLike) {
            c.tier = c.pSnore >= 0.6f ? 2 : 1;
            addSnore(c);
            return;
        }
        // 后备：模型的梅尔滤波器从 125 Hz 才开始，很闷（低频为主）的鼾声它几乎看不到。
        // 模型没认出任何明确的别的声音（最高分 < 0.5），而声音形状又像一声鼾（低频重、0.3–6 秒）时，
        // 当作"弱候选"。弱候选只有在连成规律的鼾声串（≥3 声、间隔 1.8–10 秒）时才会被采纳。
        if (top1 < 0.5f && c.low >= 0.85f && c.cent <= 450f && c.dur >= 0.3f && c.dur <= 6f && c.meanAbove >= 8f && c.crest <= 8f) {
            c.tier = 0;
            addSnore(c);
            return;
        }
        if (grindEnabled && c.pGrind >= 0.12f && c.cent >= 900f && c.dur >= 0.15f && c.dur <= 8f) { addGrind(c); return; }
        if (c.peakAbove >= 18f) { disturb(c); emit(T_OTHER, c, 0.4f, 0); }
    }

    private void disturb(Cand c) {
        disturbances.add(c.start);
        while (disturbances.size() > 20) disturbances.remove(0);
    }

    // ---------- 鼾声 ----------
    private void addSnore(Cand c) {
        if (!chain.isEmpty() && c.start - chain.get(chain.size() - 1).end > CHAIN_GAP) finalizeChain();
        chain.add(c);
    }

    private void finalizeChain() {
        if (chain.isEmpty()) return;
        boolean anyStrict = false;
        for (Cand c : chain) if (c.tier == 2) anyStrict = true;
        boolean ok;
        if (chain.size() >= 3) {
            // 成串：呼吸周期应该在 1.8–10 秒之间，太快（敲门、连续点击）不算
            float[] iv = new float[chain.size() - 1];
            for (int i = 0; i + 1 < chain.size(); i++) iv[i] = (chain.get(i + 1).start - chain.get(i).start) / (float) SR;
            Arrays.sort(iv);
            float med = iv[iv.length / 2];
            ok = med >= 1.8f && med <= 10f;
        } else {
            ok = anyStrict;
        }
        if (ok) for (Cand c : chain) { if (chain.size() < 3 && c.tier == 0) continue; acceptSnore(c, chain.size()); }
        chain.clear();
    }

    private void acceptSnore(Cand c, int chainSize) {
        // 疑似呼吸暂停：与上一次已确认鼾声之间出现异常长的静默
        if (!accepted.isEmpty()) {
            long[] p = accepted.get(accepted.size() - 1);
            float gap = (c.start - p[1]) / (float) SR;
            if (gap >= 11f && gap <= 70f) checkApnea(p, gap, c);
        }
        accepted.add(new long[] { c.start, c.end });
        acceptedPeak.add(c.peak);
        while (accepted.size() > 12) { accepted.remove(0); acceptedPeak.remove(0); }
        Ev e = new Ev();
        e.type = T_SNORE; e.startSample = c.start; e.endSample = c.end; e.peakDb = c.peak; e.meanDb = c.mean;
        if (model != null) e.conf = Math.min(1f, Math.max(0.3f, c.pSnore));
        else e.conf = c.tier == 2 ? 0.8f : (chainSize >= 3 ? 0.6f : 0.4f);
        if (lis != null) lis.onEvent(e);
    }

    private void checkApnea(long[] p, float gap, Cand resume) {
        // 取 p 之前 45 秒内的已确认鼾声，要求至少 3 个且间隔规律
        ArrayList<long[]> ep = new ArrayList<long[]>();
        for (long[] a : accepted) if (a[0] >= p[1] - 45L * SR) ep.add(a);
        if (ep.size() < 3) return;
        float[] gaps = new float[ep.size() - 1];
        float[] starts = new float[ep.size() - 1];
        for (int i = 0; i + 1 < ep.size(); i++) {
            gaps[i] = (ep.get(i + 1)[0] - ep.get(i)[1]) / (float) SR;
            starts[i] = (ep.get(i + 1)[0] - ep.get(i)[0]) / (float) SR;
        }
        Arrays.sort(gaps); Arrays.sort(starts);
        float normalGap = gaps[gaps.length / 2], cycle = starts[starts.length / 2];
        if (cycle > 9f || cycle < 1.5f) return;
        float pause = gap - normalGap;
        if (pause < 10f) return;
        // 静默期间如果有咳嗽、说话、翻身一类的声音，说明是醒了或动了，不是呼吸暂停
        for (long d : disturbances) if (d > p[1] && d < resume.start) return;
        Ev e = new Ev();
        e.type = T_APNEA; e.startSample = p[1]; e.endSample = resume.start; e.extra = pause;
        e.peakDb = resume.peak; e.meanDb = resume.mean;
        float prevPeak = 0; int n = 0;
        for (int i = 0; i < acceptedPeak.size(); i++) { prevPeak += acceptedPeak.get(i); n++; }
        prevPeak = n > 0 ? prevPeak / n : resume.peak;
        e.conf = Math.max(0.3f, Math.min(1f, 0.5f + (resume.peak - prevPeak) / 20f));
        if (lis != null) lis.onEvent(e);
    }

    // ---------- 磨牙（实验性）----------
    private void addGrind(Cand c) {
        if (!grind.isEmpty() && c.start - grind.get(grind.size() - 1).end > 5L * SR) finalizeGrind();
        grind.add(c);
    }

    private void finalizeGrind() {
        int n = grind.size();
        if (n == 0) return;
        if (model == null) {
            // 老规则：至少 6 个、节律均匀
            if (n >= 6) {
                float[] iv = new float[n - 1];
                double mean = 0;
                for (int i = 0; i + 1 < n; i++) { iv[i] = (grind.get(i + 1).start - grind.get(i).start) / (float) SR; mean += iv[i]; }
                mean /= iv.length;
                double var = 0;
                for (float v : iv) var += (v - mean) * (v - mean);
                double cv = Math.sqrt(var / iv.length) / Math.max(mean, 1e-3);
                if (mean >= 0.3 && mean <= 2.5 && cv < 0.45) emitGrind(0.4f);
            }
        } else {
            // 模型：一段较长的摩擦声，或者 ≥3 个连续的摩擦声
            float total = 0, best = 0, sum = 0;
            for (Cand c : grind) { total += c.dur; best = Math.max(best, c.pGrind); sum += c.pGrind; }
            float meanP = sum / n;
            boolean longOne = n <= 2 && best >= 0.3f && total >= 1.2f;
            boolean train = n >= 3 && n <= 40 && meanP >= 0.15f;
            if (longOne || train) emitGrind(Math.min(0.9f, Math.max(0.3f, meanP + 0.2f)));
        }
        grind.clear();
    }

    private void emitGrind(float conf) {
        Cand a = grind.get(0), b = grind.get(grind.size() - 1);
        float pk = 0;
        for (Cand c : grind) pk = Math.max(pk, c.peak);
        Cand m = new Cand(); m.start = a.start; m.end = b.end; m.peak = pk; m.mean = a.mean;
        emit(T_GRIND, m, conf, 0);
    }

    private void emit(int type, Cand c, float conf, float extra) {
        Ev e = new Ev();
        e.type = type; e.startSample = c.start; e.endSample = c.end; e.peakDb = c.peak; e.meanDb = c.mean; e.conf = conf; e.extra = extra;
        if (lis != null) lis.onEvent(e);
    }
}
