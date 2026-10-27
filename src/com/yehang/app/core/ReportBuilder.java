package com.yehang.app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static com.yehang.app.core.Json.m;

/**
 * 把一晚的原始记录整理成"看板"要用的数据：大白话的结论、红黄绿状态、全夜时间线。
 * 所有文字都在这里生成，界面只负责摆放。不依赖 Android，桌面 JVM 上也能跑（用来生成界面预览数据和做测试）。
 */
public final class ReportBuilder {
    private ReportBuilder() { }

    public static final long BIN_MS = 10 * 60000L;

    /** 一晚的完整看板数据。prev 是这一晚之前的几晚（新的在前），用来做"和平时比"。 */
    public static Map<String, Object> build(Rec.Night n, Summary s, List<Rec.Epoch> epochs, List<Summary> prev,
                                            String doctorReason, long nowMs, int goalMin) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        boolean interrupted = (n.flags & 1) != 0;
        boolean noModel = (n.flags & 2) != 0;
        r.put("id", n.id);
        r.put("startMs", s.startMs);
        r.put("endMs", s.endMs);
        r.put("dateLabel", Fmt.dayWeek(s.endMs));
        r.put("bedLabel", Fmt.clock(s.startMs));
        r.put("endLabel", Fmt.clock(s.endMs));
        r.put("tooShort", s.tooShort);
        r.put("interrupted", interrupted);
        r.put("score", s.score);
        r.put("verdict", Advice.verdictFor(s));
        r.put("goalMin", goalMin);

        // ---- 对比用的前几晚（只用够长的）
        ArrayList<Summary> base = new ArrayList<Summary>();
        for (Summary p : prev) if (p != null && !p.tooShort && p.sleepMin > 0 && base.size() < 7) base.add(p);

        String nightWord = sameDay(s.endMs, nowMs) ? "昨晚" : "这一晚";
        r.put("headline", headline(s, nightWord));
        r.put("scoreNote", scoreNote(s));

        // ---- 四个指标
        Map<String, Object> tl = timeline(s, epochs);
        Object peak = tl.get("peak");
        Map<String, Object> tiles = new LinkedHashMap<String, Object>();
        tiles.put("sleep", sleepTile(s, goalMin));
        tiles.put("snore", snoreTile(s, peak == null ? null : (String) ((Map<?, ?>) peak).get("label")));
        tiles.put("apnea", apneaTile(s));
        tiles.put("calm", calmTile(s));
        r.put("tiles", tiles);

        r.put("compare", compare(s, base));
        r.put("attention", attention(s, goalMin, doctorReason));
        r.put("timeline", tl);
        r.put("clips", clips(s));
        r.put("detail", detail(s));

        ArrayList<String> notes = new ArrayList<String>();
        if (s.tooShort) notes.add("这次记录不到 2 小时，不计分，也看不出规律。");
        if (interrupted) notes.add("这一晚的监测中途被系统中断了（后台被清理，或手机关机、没电），报告只包含中断之前的部分。");
        if (noModel) notes.add("这一晚声音识别模型没能加载，用的是基础规则，鼾声和呼吸暂停的判断会更粗糙。");
        if (!s.motionOk && !s.tooShort) notes.add("这次没有拿到可用的体动数据（手机没放在床上，或系统限制了传感器），什么时候睡着、醒来只能靠声音推测，仅供参考。");
        if (s.gaps.size() > 0) notes.add("中间有 " + s.gaps.size() + " 段时间没有记录到数据。");
        r.put("notes", notes);

        ArrayList<String> tags = new ArrayList<String>();
        for (String t : n.tags.split(",")) if (t.length() > 0) tags.add(t);
        r.put("tags", tags);

        if (doctorReason != null) {
            r.put("doctor", m("reason", doctorReason, "text", Advice.SEE_DOCTOR, "drive", Advice.DONT_DRIVE));
        } else {
            r.put("doctor", null);
        }
        return r;
    }

    // ================================================================= 文字
    private static boolean sameDay(long a, long b) {
        TimeZone tz = TimeZone.getDefault();
        long da = (a + tz.getOffset(a)) / 86400000L, db = (b + tz.getOffset(b)) / 86400000L;
        return da == db;
    }

    static String snoreWord(float pct) {
        if (pct < 3) return "几乎没有";
        if (pct < 10) return "轻";
        if (pct < 30) return "中";
        return "重";
    }

    private static String headline(Summary s, String nightWord) {
        if (s.tooShort) return "这次只记录了 " + Fmt.hm(s.inBedMin) + "，太短了，还看不出睡得怎么样。";
        StringBuilder sb = new StringBuilder();
        if (s.sleepMin > 0) sb.append(nightWord).append("睡了 ").append(Fmt.hm(s.sleepMin));
        else sb.append(nightWord).append("没能判断出你什么时候睡着");
        if (s.snorePct < 3) sb.append("，几乎没有打鼾");
        else if (s.snorePct < 10) sb.append("，有一点鼾声");
        else if (s.snorePct < 30) sb.append("，打鼾比较明显");
        else sb.append("，鼾声很重");
        if (s.apneaCount > 0) sb.append("，有 ").append(s.apneaCount).append(" 次疑似呼吸暂停");
        sb.append("。");
        return sb.toString();
    }

    private static String plainPart(String name) {
        if ("睡眠时长".equals(name)) return "睡得不够久";
        if ("入睡用时".equals(name)) return "入睡比较慢";
        if ("睡眠效率".equals(name)) return "夜里清醒的时间偏多";
        if ("夜间觉醒".equals(name)) return "夜里醒的次数偏多";
        if (name.startsWith("深睡")) return "深睡偏少";
        if ("鼾声".equals(name)) return "鼾声";
        if ("疑似暂停".equals(name)) return "疑似呼吸暂停";
        return name;
    }

    private static String scoreNote(Summary s) {
        if (s.score < 0) return "记录不到 2 小时，不计分。";
        if (s.scoreCap > 0) return (s.apneaIdx >= 15f ? "疑似呼吸暂停偏多" : (s.snorePct >= 30f ? "鼾声很重" : "疑似呼吸暂停偏多")) + "，评分最高只给到 " + s.scoreCap + " 分。";
        float worst = 0; int wi = -1;
        for (int i = 0; i < s.parts.size(); i++) {
            float d = s.parts.get(i)[1] - s.parts.get(i)[0];
            if (d > worst) { worst = d; wi = i; }
        }
        if (wi < 0 || worst < 3f) return "各方面都不错，没有明显扣分的地方。";
        return "主要是" + plainPart(s.partNames.get(wi)) + "，扣了 " + Math.round(worst) + " 分。";
    }

    private static Map<String, Object> sleepTile(Summary s, int goalMin) {
        String tone = "neutral", chip = "", sub;
        if (s.sleepMin <= 0) { sub = "没有判断出入睡时间"; }
        else {
            int diff = s.sleepMin - goalMin;
            if (s.sleepMin >= goalMin * 0.92f) { tone = "ok"; chip = "够了"; }
            else if (s.sleepMin >= 360) { tone = "watch"; chip = "略少"; }
            else { tone = "watch"; chip = "偏少"; }
            sub = diff >= 0 ? "达到了你的目标（" + Fmt.hm(goalMin) + "）" : "比目标（" + Fmt.hm(goalMin) + "）少 " + Fmt.hm(-diff);
        }
        return m("title", "睡了多久", "value", s.sleepMin > 0 ? hmShort(s.sleepMin) : "—", "chip", chip, "tone", tone, "sub", sub);
    }

    private static Map<String, Object> snoreTile(Summary s, String peakLabel) {
        String tone, chip;
        if (s.snorePct < 3) { tone = "ok"; chip = "几乎没有"; }
        else if (s.snorePct < 10) { tone = "neutral"; chip = "轻"; }
        else if (s.snorePct < 30) { tone = "watch"; chip = "中"; }
        else { tone = "watch"; chip = "重"; }
        String sub;
        if (s.snoreEvents == 0) sub = "整晚没有听到明显的鼾声";
        else {
            sub = "累计约 " + Fmt.hm(Math.max(1, Math.round(s.snoreMin)));
            if (peakLabel != null) sub += "，集中在 " + peakLabel.replace(" 至 ", "–");
        }
        return m("title", "打鼾", "value", Math.round(s.snorePct) + "%", "unit", "睡着的时间里在打鼾", "chip", chip, "tone", tone, "sub", sub);
    }

    /** 鼾声大多是轻、中还是响，用一句话说。 */
    static String loudWord(Summary s) {
        int n = Math.max(1, s.snoreLight + s.snoreMid + s.snoreHeavy);
        if (s.snoreHeavy * 100 / n >= 20) return "有不少声音比较响";
        if ((s.snoreMid + s.snoreHeavy) * 100 / n >= 40) return "声音中等";
        return "多数声音不算响";
    }

    private static Map<String, Object> apneaTile(Summary s) {
        String tone, chip, sub;
        if (s.apneaCount == 0) { tone = "ok"; chip = "没发现"; sub = "没有听到鼾声突然中断的情况"; }
        else {
            if (s.apneaIdx >= 15f) { tone = "alert"; chip = "较多"; }
            else if (s.apneaIdx >= 5f) { tone = "watch"; chip = "留意"; }
            else { tone = "neutral"; chip = "偶尔"; }
            sub = "最长约 " + Math.round(s.apneaMaxSec) + " 秒没有呼吸声（估算）";
        }
        return m("title", "疑似呼吸暂停", "value", s.apneaCount + " 次", "chip", chip, "tone", tone, "sub", sub);
    }

    private static Map<String, Object> calmTile(Summary s) {
        if (s.sleepMin <= 0) return m("title", "睡得稳不稳", "value", "—", "chip", "", "tone", "neutral", "sub", "没有判断出入睡时间");
        if (!s.motionOk) return m("title", "睡得稳不稳", "value", "—", "chip", "无数据", "tone", "neutral", "sub", "没有拿到体动数据，无法判断");
        String chip, tone;
        if (s.awakenings <= 2) { chip = "平稳"; tone = "ok"; }
        else if (s.awakenings <= 4) { chip = "一般"; tone = "neutral"; }
        else { chip = "不太稳"; tone = "watch"; }
        return m("title", "睡得稳不稳", "value", "醒 " + s.awakenings + " 次", "chip", chip, "tone", tone,
                "sub", "入睡用了 " + Fmt.hm(Math.max(1, s.latencyMin)));
    }

    private static String hmShort(int minutes) {
        int h = minutes / 60, mi = minutes % 60;
        if (h == 0) return minutes + " 分";
        return mi == 0 ? h + " 小时" : h + " 小时 " + mi + " 分";
    }

    // ================================================================= 和平时比
    private static List<Object> compare(Summary s, List<Summary> base) {
        ArrayList<Object> out = new ArrayList<Object>();
        if (s.tooShort || base.size() < 2) return out;
        int n = base.size();
        double sl = 0, sn = 0, ap = 0;
        for (Summary p : base) { sl += p.sleepMin; sn += p.snorePct; ap += p.apneaCount; }
        sl /= n; sn /= n; ap /= n;
        String who = "近 " + n + " 晚平均";
        int dsl = (int) Math.round(s.sleepMin - sl);
        out.add(m("label", "睡眠时长", "now", s.sleepMin > 0 ? hmShort(s.sleepMin) : "—", "avg", hmShort((int) Math.round(sl)),
                "text", Math.abs(dsl) < 10 ? "和" + who + "差不多" : "比" + who + (dsl > 0 ? "多" : "少") + " " + Fmt.hm(Math.abs(dsl)),
                "dir", Math.abs(dsl) < 10 ? 0 : (dsl > 0 ? 1 : -1)));
        int dsn = (int) Math.round(s.snorePct - sn);
        out.add(m("label", "打鼾占比", "now", Math.round(s.snorePct) + "%", "avg", Math.round(sn) + "%",
                "text", Math.abs(dsn) < 3 ? "和" + who + "差不多" : "比" + who + (dsn > 0 ? "多" : "少") + " " + Math.abs(dsn) + " 个百分点",
                "dir", Math.abs(dsn) < 3 ? 0 : (dsn > 0 ? 1 : -1)));
        double dap = s.apneaCount - ap;
        out.add(m("label", "疑似呼吸暂停", "now", s.apneaCount + " 次", "avg", Fmt.f1((float) ap) + " 次",
                "text", Math.abs(dap) < 1 ? "和" + who + "差不多" : "比" + who + (dap > 0 ? "多" : "少") + " " + Math.round(Math.abs(dap)) + " 次",
                "dir", Math.abs(dap) < 1 ? 0 : (dap > 0 ? 1 : -1)));
        return out;
    }

    // ================================================================= 最该注意的几件事
    private static List<Object> attention(Summary s, int goalMin, String doctorReason) {
        ArrayList<Object> out = new ArrayList<Object>();
        if (s.tooShort) return out;
        // 严重程度：2 = 需要留意并处理，1 = 值得看一眼，0 = 提示
        if (s.apneaCount > 0) {
            int sev = s.apneaIdx >= 15f ? 2 : (s.apneaIdx >= 5f ? 1 : 0);
            String where = "";
            if (!s.apneaEvents.isEmpty()) {
                long mid = s.apneaEvents.get(s.apneaEvents.size() / 2).startMs;
                where = "，集中在 " + Fmt.clock(mid) + " 前后";
                if (s.apneaEvents.size() <= 2) where = "，出现在 " + Fmt.clock(s.apneaEvents.get(0).startMs) + " 前后";
            }
            out.add(item(sev, "apnea", "有 " + s.apneaCount + " 次疑似呼吸暂停",
                    "鼾声连续出现后，突然安静了 10 秒以上才又响起来。最长约 " + Math.round(s.apneaMaxSec) + " 秒" + where + "。手机听不到气流和血氧，这只是一个提醒，不是诊断。",
                    sev == 0 ? "偶尔一次不用紧张。如果连续几晚都有，或者白天仍然很困，可以去睡眠门诊做一次专业监测。"
                            : "连续几晚都这样，或者白天很困，建议去睡眠门诊、呼吸科或耳鼻喉科做一次专业监测。"));
        }
        if (s.snorePct >= 10f) {
            int sev = s.snorePct >= 30f ? 2 : 1;
            out.add(item(sev, "snore", s.snorePct >= 30f ? "鼾声很重，占了睡着时间的 " + Math.round(s.snorePct) + "%" : "打鼾比较明显，占了睡着时间的 " + Math.round(s.snorePct) + "%",
                    "累计约 " + Fmt.hm(Math.max(1, Math.round(s.snoreMin))) + " 在打鼾，" + loudWord(s) + "。",
                    "可以试试：侧着睡、把床头稍微垫高、睡前几小时不喝酒、鼻子不通气先处理鼻子。在“今晚”页记下这些习惯，攒几晚后就能看出哪个最有关系。"));
        }
        if (s.sleepMin > 0 && s.sleepMin < 360) {
            out.add(item(1, "sleep", "睡得偏少，只有 " + Fmt.hm(s.sleepMin),
                    "不到 6 小时。白天犯困并不意外，不一定都是鼾声的原因。",
                    "今晚试着早点上床，睡前一小时少看手机。"));
        }
        if (s.latencyMin > 40) {
            out.add(item(0, "latency", "入睡花了 " + s.latencyMin + " 分钟",
                    "躺下后过了很久才睡着。", "固定上床时间、睡前少看手机会有帮助。"));
        }
        if (s.motionOk && s.awakenings >= 4) {
            out.add(item(0, "wake", "夜里醒了 " + s.awakenings + " 次",
                    "留意是起夜、被噪声吵醒，还是被自己的鼾声弄醒。", "对照下面的时间线，看看醒来前后有没有声音。"));
        }
        if (s.noiseAvg > 38f) {
            out.add(item(0, "noise", "卧室背景声音偏大", "可能影响睡眠，也会让鼾声的识别变得没那么准。", "试试关掉风扇、关好窗，或把手机放近一点。"));
        }
        Collections.sort(castItems(out), new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return ((Integer) b.get("sev")).compareTo((Integer) a.get("sev"));
            }
        });
        if (out.isEmpty()) {
            out.add(item(0, "fine", "没有需要特别留意的地方", "睡眠时长、打鼾和呼吸暂停都在平常的范围里。", "继续保持，每天记录几晚，趋势比单独一晚更有参考价值。"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castItems(List<Object> l) { return (List<Map<String, Object>>) (List<?>) l; }

    private static Map<String, Object> item(int sev, String kind, String title, String body, String action) {
        return m("sev", sev, "kind", kind, "title", title, "body", body, "action", action);
    }

    // ================================================================= 时间线
    private static Map<String, Object> timeline(Summary s, List<Rec.Epoch> epochs) {
        long off = TimeZone.getDefault().getOffset(s.startMs);
        long t0 = ((s.startMs + off) / BIN_MS) * BIN_MS - off;
        long t1 = ((s.endMs + off + BIN_MS - 1) / BIN_MS) * BIN_MS - off;
        int nb = (int) Math.max(1, (t1 - t0) / BIN_MS);
        float[] share = new float[nb];
        float[] maxDb = new float[nb];
        for (Rec.Epoch e : epochs) {
            if (e.snoreSec <= 0) continue;
            int b = (int) ((e.t - t0) / BIN_MS);
            if (b < 0 || b >= nb) continue;
            share[b] += e.snoreSec / 60f / (BIN_MS / 60000f);
            maxDb[b] = Math.max(maxDb[b], e.snoreDb);
        }
        ArrayList<Object> bins = new ArrayList<Object>();
        for (int i = 0; i < nb; i++) {
            if (share[i] <= 0.004f) continue;
            float sh = Math.min(1f, share[i]);
            bins.add(m("i", i, "share", Math.round(sh * 100) / 100f, "db", Math.round(maxDb[i]), "lv", maxDb[i] < 45 ? 1 : (maxDb[i] < 60 ? 2 : 3)));
        }

        // 打鼾最集中的一小时
        Object peak = null;
        float total = 0;
        for (float v : share) total += v * 10f;
        if (total >= 8f && nb >= 3) {
            int win = Math.min(6, nb);
            float best = -1; int bi = 0;
            for (int i = 0; i + win <= nb; i++) {
                float sum = 0;
                for (int j = i; j < i + win; j++) sum += share[j];
                if (sum > best) { best = sum; bi = i; }
            }
            // 收紧到真正有鼾声的头尾
            int a = bi, b = bi + win - 1;
            while (a < b && share[a] < 0.05f) a++;
            while (b > a && share[b] < 0.05f) b--;
            if (best * 10f >= 8f) {
                long pa = t0 + a * BIN_MS, pb = t0 + (b + 1) * BIN_MS;
                peak = m("a", pa, "b", pb, "label", Fmt.clock(pa) + " 至 " + Fmt.clock(pb), "minutes", Math.round(best * 10f));
            }
        }

        // 睡眠状态：把每分钟的状态合并成连续的段
        ArrayList<Object> stages = new ArrayList<Object>();
        int runStart = 0, cur = Integer.MIN_VALUE;
        for (int i = 0; i <= s.stage.length; i++) {
            int v = i < s.stage.length ? s.stage[i] : Integer.MIN_VALUE + 1;
            if (v != cur) {
                if (cur != Integer.MIN_VALUE) stages.add(m("a", s.startMs + runStart * 60000L, "b", s.startMs + i * 60000L, "s", cur));
                cur = v; runStart = i;
            }
        }

        ArrayList<Object> apnea = new ArrayList<Object>();
        for (Rec.Event e : s.apneaEvents) {
            apnea.add(m("id", e.id, "t", e.startMs, "b", e.endMs, "sec", Math.round(e.extra), "clip", e.clip != null, "label", Fmt.clock(e.startMs)));
        }
        ArrayList<Object> marks = new ArrayList<Object>();
        addMarks(marks, s.coughList, "cough");
        addMarks(marks, s.talkList, "talk");
        addMarks(marks, s.grindList, "grind");

        ArrayList<Object> gaps = new ArrayList<Object>();
        for (long[] g : s.gaps) gaps.add(m("a", g[0], "b", g[1]));

        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("t0", t0); t.put("t1", t1); t.put("binMs", BIN_MS);
        t.put("onset", s.onsetMs); t.put("wake", s.wakeMs);
        t.put("stages", stages);
        t.put("snore", bins);
        t.put("peak", peak);
        t.put("apnea", apnea);
        t.put("marks", marks);
        t.put("gaps", gaps);
        t.put("motionOk", s.motionOk);
        // 一句话说明，给读屏和"怎么读"用
        StringBuilder sum = new StringBuilder();
        if (peak != null) sum.append("鼾声集中在 ").append(((Map<?, ?>) peak).get("label"));
        else if (s.snoreEvents > 0) sum.append("鼾声零星分布在整晚");
        else sum.append("整晚没有明显的鼾声");
        if (s.apneaCount > 0) sum.append("，有 ").append(s.apneaCount).append(" 次疑似呼吸暂停");
        sum.append("。");
        t.put("summary", sum.toString());
        return t;
    }

    private static void addMarks(List<Object> out, List<Rec.Event> evs, String kind) {
        for (Rec.Event e : evs) out.add(m("id", e.id, "t", e.startMs, "k", kind, "clip", e.clip != null, "label", Fmt.clock(e.startMs)));
    }

    // ================================================================= 录音
    private static List<Object> clips(Summary s) {
        ArrayList<Object> out = new ArrayList<Object>();
        int na = 0;
        for (Rec.Event e : s.apneaEvents) {
            if (e.clip == null) continue;
            if (na++ >= 5) break;
            out.add(m("id", e.id, "k", "apnea", "t", Fmt.clock(e.startMs), "title", "疑似呼吸暂停", "desc", "安静了约 " + Math.round(e.extra) + " 秒，结尾能听到呼吸恢复", "sec", Math.round((e.endMs - e.startMs) / 1000f)));
        }
        ArrayList<Rec.Event> sn = new ArrayList<Rec.Event>();
        for (Rec.Event e : s.snoreList) if (e.clip != null) sn.add(e);
        Collections.sort(sn, new Comparator<Rec.Event>() {
            public int compare(Rec.Event a, Rec.Event b) { return Float.compare(b.peakDb, a.peakDb); }
        });
        for (int i = 0; i < Math.min(2, sn.size()); i++) {
            Rec.Event e = sn.get(i);
            out.add(m("id", e.id, "k", "snore", "t", Fmt.clock(e.startMs), "title", i == 0 ? "最响的一次鼾声" : "另一段鼾声", "desc", "", "sec", Math.round((e.endMs - e.startMs) / 1000f)));
        }
        addOtherClips(out, s.coughList, "cough", "咳嗽");
        addOtherClips(out, s.talkList, "talk", "说话声");
        addOtherClips(out, s.grindList, "grind", "疑似磨牙（实验功能）");
        return out;
    }

    private static void addOtherClips(List<Object> out, List<Rec.Event> evs, String k, String title) {
        int c = 0;
        for (Rec.Event e : evs) {
            if (e.clip == null) continue;
            if (c++ >= 1) break;
            out.add(m("id", e.id, "k", k, "t", Fmt.clock(e.startMs), "title", title, "desc", "", "sec", Math.round((e.endMs - e.startMs) / 1000f)));
        }
    }

    // ================================================================= 详细数据（折叠起来，给想看的人）
    private static Map<String, Object> detail(Summary s) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        ArrayList<Object> rows = new ArrayList<Object>();
        rows.add(row("在床时间", Fmt.hm(s.inBedMin)));
        if (s.sleepMin > 0) {
            rows.add(row("睡着的时间", Fmt.hm(s.sleepMin)));
            rows.add(row("入睡用时", Fmt.hm(Math.max(1, s.latencyMin))));
            rows.add(row("夜里醒来", s.motionOk ? s.awakenings + " 次，共 " + Fmt.hm(Math.max(0, s.awakeMin)) : "没有体动数据"));
            if (s.motionOk) rows.add(row("深睡（粗略估计）", Fmt.hm(s.deepMin) + "，占 " + Math.round(s.deepMin * 100f / Math.max(1, s.sleepMin)) + "%"));
        }
        rows.add(row("鼾声次数", String.valueOf(s.snoreEvents)));
        rows.add(row("轻 / 中 / 重的鼾声", s.snoreLight + " / " + s.snoreMid + " / " + s.snoreHeavy));
        rows.add(row("疑似呼吸暂停", s.apneaCount + " 次（折算每小时 " + Fmt.f1(s.apneaIdx) + " 次）"));
        rows.add(row("咳嗽", s.coughCount + " 次"));
        rows.add(row("可能的说话声", s.talkCount + " 次（仅供参考）"));
        rows.add(row("疑似磨牙", s.grindCount + " 次（实验功能）"));
        if (s.noiseAvg >= 0) rows.add(row("卧室背景声", noiseWord(s.noiseAvg)));
        if (s.luxAvg >= 0) rows.add(row("卧室亮度", s.luxAvg < 5 ? "很暗" : (s.luxAvg < 30 ? "较暗" : "有些亮")));
        if (s.screenOnMin > 0) rows.add(row("夜里亮屏", s.screenOnMin + " 分钟"));
        d.put("rows", rows);
        ArrayList<Object> parts = new ArrayList<Object>();
        for (int i = 0; i < s.parts.size(); i++) {
            parts.add(m("name", plainPart(s.partNames.get(i)), "got", Math.round(s.parts.get(i)[0]), "max", Math.round(s.parts.get(i)[1])));
        }
        d.put("scoreParts", parts);
        return d;
    }

    private static String noiseWord(float db) {
        if (db < 28) return "很安静";
        if (db < 38) return "比较安静";
        return "偏吵";
    }

    private static Map<String, Object> row(String k, String v) { return m("k", k, "v", v); }

    // ================================================================= 夜晚列表（历史、趋势）
    public static Map<String, Object> nightItem(Rec.Night n, Summary s) {
        return m("id", n.id, "dateLabel", Fmt.dayWeek(s.endMs), "short", Fmt.day(s.endMs),
                "sleepMin", s.sleepMin, "sleepLabel", s.sleepMin > 0 ? hmShort(s.sleepMin) : "—",
                "score", s.score, "snorePct", Math.round(s.snorePct), "snoreWord", snoreWord(s.snorePct), "apnea", s.apneaCount,
                "tooShort", s.tooShort, "interrupted", (n.flags & 1) != 0, "verdict", Advice.verdictFor(s),
                "bed", Fmt.clock(s.startMs), "end", Fmt.clock(s.endMs));
    }
}
