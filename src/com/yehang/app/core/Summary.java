package com.yehang.app.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 从原始记录（每分钟记录 + 事件）计算一晚的报告指标。全部是估算。 */
public final class Summary {
    public static final int AWAKE = 0, LIGHT = 1, DEEP = 2, GAP = -1;
    public static final int BIN_MIN = 5;

    public long startMs, endMs;
    public int inBedMin, sleepMin, latencyMin, awakenings, awakeMin, deepMin, lightMin;
    public float efficiency;
    public int[] stage = new int[0];        // 每分钟分期
    public boolean motionOk;                // 体动数据是否有参考价值
    public boolean hasAccel;                // 是否收到过加速度计数据
    public long onsetMs = -1, wakeMs = -1;
    public ArrayList<long[]> gaps = new ArrayList<long[]>();   // 监测中断区间 {起, 止}

    public float snoreMin, snorePct, snoreIdx, snoreMaxDb;
    public int snoreEvents, snoreLight, snoreMid, snoreHeavy;
    public float[] snoreBins = new float[0]; // 每 5 分钟最响（0 = 无）
    public int apneaCount; public float apneaIdx, apneaMaxSec;
    public ArrayList<Rec.Event> apneaEvents = new ArrayList<Rec.Event>();
    public int talkCount, grindCount, coughCount, otherCount;
    public int grindDropped;                  // 因为同一分钟里有明显体动（多半是翻身时床、被子的摩擦声）而不算磨牙的次数
    public ArrayList<Rec.Event> snoreList = new ArrayList<Rec.Event>(), talkList = new ArrayList<Rec.Event>(),
            grindList = new ArrayList<Rec.Event>(), coughList = new ArrayList<Rec.Event>(), otherList = new ArrayList<Rec.Event>();
    Rec.Epoch[] ep;                           // 按分钟排好的记录（可能有空洞）
    public float noiseAvg = -1, luxAvg = -1;
    public int screenOnMin;

    public int score = -1;                   // -1 = 不计分
    public int scoreCap = 0;                 // 鼾声或疑似暂停偏多时给评分封的上限（0 = 没有封顶）
    public ArrayList<String> partNames = new ArrayList<String>();
    public ArrayList<float[]> parts = new ArrayList<float[]>();  // {得分, 满分}
    public boolean tooShort;

    public static Summary compute(Rec.Night n, List<Rec.Epoch> epochs, List<Rec.Event> events, int goalMin) {
        Summary s = new Summary();
        s.startMs = n.startMs;
        s.endMs = n.endMs > 0 ? n.endMs : n.lastBeat;
        if (s.endMs <= s.startMs) s.endMs = s.startMs + 60000;
        int total = (int) Math.max(1, (s.endMs - s.startMs + 59999) / 60000);
        s.inBedMin = total;
        s.tooShort = total < 120;

        Rec.Epoch[] ep = new Rec.Epoch[total];
        for (Rec.Epoch e : epochs) {
            int i = (int) ((e.t - s.startMs) / 60000);
            if (i >= 0 && i < total) ep[i] = e;
        }
        s.ep = ep;
        // 中断区间：连续 ≥3 分钟没有记录（不含最后一分钟）
        int run = 0;
        for (int i = 0; i < total; i++) {
            if (ep[i] == null) { run++; }
            else { if (run >= 3) s.gaps.add(new long[] { s.startMs + (long) (i - run) * 60000, s.startMs + (long) i * 60000 }); run = 0; }
        }
        if (run >= 3 && n.endMs > 0 && (n.flags & 1) != 0) {
            // 末尾没有数据且被标记为中断
            s.gaps.add(new long[] { s.startMs + (long) (total - run) * 60000, s.endMs });
        }

        // ---- 体动 / 活动 ----
        int strongMove = 0;
        boolean[] active = new boolean[total];
        boolean[] big = new boolean[total];
        double noiseSum = 0, luxSum = 0; int noiseN = 0, luxN = 0;
        int[] evtMin = new int[total];
        for (int i = 0; i < total; i++) {
            Rec.Epoch e = ep[i];
            if (e == null) continue;
            if (e.act >= 0) s.hasAccel = true;
            if (e.actMax >= 0.2f) strongMove++;
            if (e.screenSec > 0) s.screenOnMin++;
            if (e.noise > 0) { noiseSum += e.noise; noiseN++; }
            if (e.lux >= 0) { luxSum += e.lux; luxN++; }
            evtMin[i] = e.evtCnt;
        }
        s.motionOk = s.hasAccel && strongMove >= 3;
        for (int i = 0; i < total; i++) {
            Rec.Epoch e = ep[i];
            if (e == null) continue;
            boolean mv = s.motionOk && (e.actMax >= 0.35f || e.act >= 0.08f);
            boolean scr = e.screenSec >= 10;
            boolean snd = e.evtCnt >= 2;
            active[i] = mv || scr || snd;
            big[i] = (s.motionOk && e.actMax >= 1.5f) || e.screenSec >= 30;
        }
        s.noiseAvg = noiseN > 0 ? (float) (noiseSum / noiseN) : -1;
        s.luxAvg = luxN > 0 ? (float) (luxSum / luxN) : -1;

        // ---- 入睡 ----
        int onset = -1;
        for (int i = 0; i < total; i++) {
            if (ep[i] == null || active[i]) continue;
            int cnt = 0, have = 0;
            for (int j = i; j < Math.min(total, i + 10); j++) { if (ep[j] != null) have++; if (active[j]) cnt++; }
            if (have >= 8 && cnt <= 1) { onset = i; break; }
        }
        s.stage = new int[total];
        Arrays.fill(s.stage, GAP);
        if (onset < 0) { finish(s, n, events, goalMin, -1, -1); return s; }
        s.latencyMin = onset;
        s.onsetMs = s.startMs + (long) onset * 60000;

        // ---- 清醒判定 ----
        boolean[] awake = new boolean[total];
        for (int i = 0; i < total; i++) {
            if (ep[i] == null) continue;
            int c = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(total - 1, i + 2); j++) if (active[j]) c++;
            awake[i] = i < onset || c >= 2 || (ep[i].screenSec >= 10);
        }
        // 短于 3 分钟的清醒（翻身）并入睡眠，除非有大动作 / 亮屏
        int i = onset;
        while (i < total) {
            if (!awake[i]) { i++; continue; }
            int j = i; boolean hasBig = false;
            while (j < total && awake[j]) { if (big[j]) hasBig = true; j++; }
            if (j - i < 3 && !hasBig) for (int k = i; k < j; k++) awake[k] = false;
            i = j;
        }
        int lastSleep = -1;
        for (int k = onset; k < total; k++) if (ep[k] != null && !awake[k]) lastSleep = k;
        int sleepEnd = lastSleep + 1;
        s.wakeMs = s.startMs + (long) sleepEnd * 60000;

        // ---- 静止段 → 深睡估算 ----
        int[] st = s.stage;
        for (int k = 0; k < total; k++) {
            if (ep[k] == null) { st[k] = GAP; continue; }
            if (k < onset || k >= sleepEnd || awake[k]) st[k] = AWAKE; else st[k] = LIGHT;
        }
        if (s.motionOk) {
            int k = onset;
            while (k < sleepEnd) {
                if (st[k] != LIGHT || active[k] || evtMin[k] > 0) { k++; continue; }
                int e2 = k;
                while (e2 < sleepEnd && st[e2] == LIGHT && !active[e2] && evtMin[e2] == 0) e2++;
                // 深睡集中在前半夜：只看入睡后 4.5 小时内的长时间静止；一段最多记 45 分钟（越过 5 分钟浅睡过渡期）
                if (e2 - k >= 20 && k < onset + 270) {
                    int from = k + 5, to = Math.min(Math.min(e2, from + 45), onset + 270);
                    for (int q = from; q < to; q++) st[q] = DEEP;
                }
                k = e2;
            }
            // 整晚深睡不超过睡眠时间的 25%，超出的从最靠后的开始改回浅睡
            int total2 = 0, deep2 = 0;
            for (int q = onset; q < sleepEnd; q++) { if (st[q] == LIGHT || st[q] == DEEP) total2++; if (st[q] == DEEP) deep2++; }
            int cap = total2 / 4;
            for (int q = sleepEnd - 1; q >= onset && deep2 > cap; q--) if (st[q] == DEEP) { st[q] = LIGHT; deep2--; }
        }
        finish(s, n, events, goalMin, onset, sleepEnd);
        return s;
    }

    private static void finish(Summary s, Rec.Night n, List<Rec.Event> events, int goalMin, int onset, int sleepEnd) {
        int total = s.inBedMin;
        int sleep = 0, deep = 0, light = 0, awakeAfter = 0, awakenings = 0;
        if (onset >= 0) {
            boolean inAwake = false; int runLen = 0;
            for (int k = onset; k < sleepEnd; k++) {
                int v = s.stage[k];
                if (v == LIGHT) { sleep++; light++; }
                else if (v == DEEP) { sleep++; deep++; }
                if (v == AWAKE) {
                    awakeAfter++;
                    runLen++;
                } else {
                    if (runLen >= 3) awakenings++;
                    runLen = 0;
                }
            }
            if (runLen >= 3) awakenings++;
        }
        s.sleepMin = sleep; s.deepMin = deep; s.lightMin = light; s.awakeMin = awakeAfter; s.awakenings = awakenings;
        int bedAfterOnset = onset >= 0 ? total - onset : total;
        s.efficiency = total > 0 && sleep > 0 ? sleep / (float) total : 0f;

        // ---- 事件 ----
        ArrayList<Rec.Event> snores = new ArrayList<Rec.Event>();
        int bins = Math.max(1, (total + BIN_MIN - 1) / BIN_MIN);
        s.snoreBins = new float[bins];
        for (Rec.Event e : events) {
            switch (e.type) {
                case Analyzer.T_SNORE: snores.add(e); break;
                case Analyzer.T_APNEA: s.apneaCount++; s.apneaEvents.add(e); s.apneaMaxSec = Math.max(s.apneaMaxSec, e.extra); break;
                case Analyzer.T_TALK: s.talkCount++; s.talkList.add(e); break;
                case Analyzer.T_GRIND: {
                    // 翻身时床、被子的摩擦声和磨牙很像：那一分钟里手机感觉到明显体动，就不算磨牙
                    int mi = (int) ((e.startMs - s.startMs) / 60000);
                    Rec.Epoch pe = s.ep != null && mi >= 0 && mi < s.ep.length ? s.ep[mi] : null;
                    if (s.motionOk && pe != null && (pe.actMax >= 0.35f || pe.act >= 0.08f)) s.grindDropped++;
                    else { s.grindCount++; s.grindList.add(e); }
                    break;
                }
                case Analyzer.T_COUGH: s.coughCount++; s.coughList.add(e); break;
                default: s.otherCount++; s.otherList.add(e);
            }
        }
        Collections.sort(snores, new Comparator<Rec.Event>() {
            public int compare(Rec.Event a, Rec.Event b) { return a.startMs < b.startMs ? -1 : (a.startMs > b.startMs ? 1 : 0); }
        });
        s.snoreEvents = snores.size();
        s.snoreList = snores;
        long union = 0, curS = -1, curE = -1;
        for (Rec.Event e : snores) {
            if (e.peakDb < 45) s.snoreLight++; else if (e.peakDb < 60) s.snoreMid++; else s.snoreHeavy++;
            s.snoreMaxDb = Math.max(s.snoreMaxDb, e.peakDb);
            int b = (int) ((e.startMs - s.startMs) / 60000 / BIN_MIN);
            if (b >= 0 && b < bins) s.snoreBins[b] = Math.max(s.snoreBins[b], e.peakDb);
            if (curS < 0) { curS = e.startMs; curE = e.endMs; }
            else if (e.startMs - curE <= 12000) curE = Math.max(curE, e.endMs);
            else { union += curE - curS; curS = e.startMs; curE = e.endMs; }
        }
        if (curS >= 0) union += curE - curS;
        s.snoreMin = union / 60000f;
        int denom = s.sleepMin > 0 ? s.sleepMin : total;
        s.snorePct = denom > 0 ? Math.min(100f, s.snoreMin * 100f / denom) : 0f;
        float hours = Math.max(0.5f, denom / 60f);
        s.snoreIdx = s.snoreEvents / hours;
        s.apneaIdx = s.apneaCount / hours;

        // ---- 评分 ----
        if (s.tooShort || onset < 0) { s.score = -1; return; }
        float goal = Math.max(240, goalMin);
        add(s, "睡眠时长", 25f * Math.min(1f, s.sleepMin / goal), 25f);
        add(s, "入睡用时", 10f * clamp(1f - (s.latencyMin - 20f) / 40f), 10f);
        if (s.motionOk) {
            add(s, "睡眠效率", 15f * clamp((s.efficiency - 0.6f) / 0.3f), 15f);
            add(s, "夜间觉醒", 10f * clamp(1f - Math.max(0, s.awakenings - 2) * 0.25f), 10f);
            float dp = s.sleepMin > 0 ? s.deepMin * 100f / s.sleepMin : 0f;
            add(s, "深睡（估算）", 10f * clamp(dp / 15f), 10f);
        }
        add(s, "鼾声", 15f * clamp(1f - Math.max(0f, s.snorePct - 10f) / 50f), 15f);
        add(s, "疑似暂停", 15f * clamp(1f - Math.max(0f, s.apneaIdx - 5f) / 25f), 15f);
        float got = 0, max = 0;
        for (float[] p : s.parts) { got += p[0]; max += p[1]; }
        s.score = max > 0 ? Math.round(got / max * 100f) : -1;
        // 睡得够久但鼾声很重、或者疑似暂停偏多的晚上，不应该显示成"睡得安稳"
        if (s.score >= 0) {
            int cap = s.apneaIdx >= 15f ? 69 : (s.snorePct >= 30f || s.apneaIdx >= 5f ? 79 : 0);
            if (cap > 0 && s.score > cap) { s.score = cap; s.scoreCap = cap; }
        }
    }

    private static void add(Summary s, String name, float got, float max) {
        s.partNames.add(name);
        s.parts.add(new float[] { got, max });
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** 疑似暂停指数的分档文字。 */
    public static String apneaBand(float idx) {
        if (idx < 5) return "低于 5";
        if (idx < 15) return "5 至 15";
        if (idx < 30) return "15 至 30";
        return "30 以上";
    }

    public static String snoreBand(float pct) {
        if (pct < 10) return "轻";
        if (pct < 30) return "中";
        return "重";
    }
}
