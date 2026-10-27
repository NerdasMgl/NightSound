import com.yehang.app.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 用模拟的一晚数据，经真实的 Summary / ReportBuilder 代码，生成界面预览用的 JSON。 */
public class FixtureGen {
    static final long DAY = 86400000L;
    static long nextEventId = 1;

    static class Spec {
        String name; long startMs; int minutes; boolean motion = true; int flags = 0; String tags = "";
        int[][] snore = {};      // {起始分钟, 结束分钟, 响度dB}
        int[] apneaAt = {};      // 暂停发生在第几分钟
        int[] coughAt = {}, talkAt = {}, grindAt = {};
        int onset = 25; int[] wakeRuns = {};   // 夜里醒来的分钟
    }

    static Rec.Night night(long id, Spec sp) {
        Rec.Night n = new Rec.Night();
        n.id = id; n.startMs = sp.startMs; n.endMs = sp.startMs + sp.minutes * 60000L; n.lastBeat = n.endMs; n.flags = sp.flags; n.tags = sp.tags; n.sens = 1;
        return n;
    }

    static List<Rec.Epoch> epochs(Spec sp, Random rnd) {
        ArrayList<Rec.Epoch> out = new ArrayList<>();
        for (int i = 0; i < sp.minutes; i++) {
            Rec.Epoch e = new Rec.Epoch();
            e.t = sp.startMs + i * 60000L;
            e.noise = 24 + rnd.nextFloat() * 3;
            e.lux = 1;
            boolean awake = i < sp.onset;
            for (int k = 0; k + 1 < sp.wakeRuns.length; k += 2) if (i >= sp.wakeRuns[k] && i < sp.wakeRuns[k + 1]) awake = true;
            if (sp.motion) {
                if (awake) { e.act = 0.12f + rnd.nextFloat() * 0.1f; e.actMax = 0.5f + rnd.nextFloat(); }
                else if (rnd.nextInt(45) == 0) { e.act = 0.1f; e.actMax = 0.6f; }
                else { e.act = 0.005f; e.actMax = 0.03f; }
            } else { e.act = -1; e.actMax = -1; }
            if (awake && i < sp.onset) e.screenSec = rnd.nextInt(3) == 0 ? 20 : 0;
            out.add(e);
        }
        return out;
    }

    static List<Rec.Event> events(Spec sp, List<Rec.Epoch> eps, Random rnd, boolean clips) {
        ArrayList<Rec.Event> out = new ArrayList<>();
        for (int[] sn : sp.snore) {
            long t = sp.startMs + sn[0] * 60000L;
            long end = sp.startMs + sn[1] * 60000L;
            int clipCount = 0;
            while (t < end) {
                Rec.Event e = new Rec.Event();
                e.id = nextEventId++; e.type = Analyzer.T_SNORE; e.startMs = t; e.endMs = t + 1200 + rnd.nextInt(1500);
                e.peakDb = sn[2] + rnd.nextFloat() * 8 - 4; e.meanDb = e.peakDb - 6; e.conf = 0.8f;
                if (clips && clipCount < 2 && rnd.nextInt(200) == 0) { e.clip = "clips/x" + e.id + ".wav"; clipCount++; }
                out.add(e);
                int minute = (int) ((t - sp.startMs) / 60000);
                Rec.Epoch ep = eps.get(Math.min(minute, eps.size() - 1));
                ep.snoreSec += (int) Math.max(1, (e.endMs - e.startMs) / 1000); ep.snoreDb = Math.max(ep.snoreDb, e.peakDb);
                t += 3800 + rnd.nextInt(1800);
            }
        }
        for (int m : sp.apneaAt) {
            Rec.Event e = new Rec.Event();
            e.id = nextEventId++; e.type = Analyzer.T_APNEA; e.startMs = sp.startMs + m * 60000L + rnd.nextInt(30000); e.extra = 12 + rnd.nextInt(14);
            e.endMs = e.startMs + (long) (e.extra * 1000); e.peakDb = 58; e.meanDb = 50; e.conf = 0.7f; e.clip = clips ? "clips/a" + e.id + ".wav" : null;
            out.add(e);
        }
        add(out, sp, eps, sp.coughAt, Analyzer.T_COUGH, clips, rnd);
        add(out, sp, eps, sp.talkAt, Analyzer.T_TALK, clips, rnd);
        add(out, sp, eps, sp.grindAt, Analyzer.T_GRIND, clips, rnd);
        Collections.sort(out, (a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    static void add(List<Rec.Event> out, Spec sp, List<Rec.Epoch> eps, int[] at, int type, boolean clips, Random rnd) {
        for (int m : at) {
            Rec.Event e = new Rec.Event();
            e.id = nextEventId++; e.type = type; e.startMs = sp.startMs + m * 60000L + rnd.nextInt(40000); e.endMs = e.startMs + 1500; e.peakDb = 50; e.meanDb = 42; e.conf = 0.6f;
            e.clip = clips ? "clips/o" + e.id + ".wav" : null;
            eps.get(Math.min(m, eps.size() - 1)).evtCnt++;
            out.add(e);
        }
    }

    static Summary sum(Spec sp, long id, Random rnd, boolean clips, Rec.Night[] nOut, List<Rec.Epoch>[] eOut) {
        Rec.Night n = night(id, sp);
        List<Rec.Epoch> eps = epochs(sp, rnd);
        List<Rec.Event> evs = events(sp, eps, rnd, clips);
        nOut[0] = n; eOut[0] = eps;
        return Summary.compute(n, eps, evs, 450);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String outPath = args[0];
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        long today = 1793030400000L;   // 2026-10-27 00:00 CST
        Random rnd = new Random(3);

        LinkedHashMap<String, Object> reports = new LinkedHashMap<>();
        ArrayList<Object> nights = new ArrayList<>();
        ArrayList<Summary> prevs = new ArrayList<>();

        // 前几晚（用于对比和趋势）
        Spec[] older = new Spec[5];
        for (int i = 0; i < 5; i++) {
            Spec sp = new Spec(); sp.name = "older" + i; sp.startMs = today - (i + 2) * DAY + 23 * 3600000L + 10 * 60000L; sp.minutes = 400 + rnd.nextInt(60);
            sp.snore = new int[][] { { 90, 150 + i * 10, 52 }, { 200, 260, 58 } };
            sp.apneaAt = i % 2 == 0 ? new int[] { 130, 210 } : new int[] {};
            sp.onset = 20 + rnd.nextInt(15);
            older[i] = sp;
        }

        long id = 10;
        // ---- 最近一晚：典型，有打鼾集中时段、几次暂停
        Spec a = new Spec(); a.name = "typical"; a.startMs = today - 1 * DAY + 23 * 3600000L + 12 * 60000L; a.minutes = 470; a.tags = "酒精,仰卧"; a.onset = 28;
        a.snore = new int[][] { { 75, 100, 48 }, { 130, 215, 63 }, { 260, 300, 55 }, { 350, 380, 50 } };
        a.apneaAt = new int[] { 142, 151, 188 }; a.coughAt = new int[] { 120, 330 }; a.talkAt = new int[] { 305 }; a.grindAt = new int[] { 240, 241 };
        a.wakeRuns = new int[] { 233, 238, 400, 405 };
        Rec.Night[] nOut = new Rec.Night[1]; List<Rec.Epoch>[] eOut = new List[1];
        // 先算前几晚
        for (int i = 0; i < older.length; i++) {
            Summary s = sum(older[i], 100 + i, rnd, false, nOut, eOut);
            prevs.add(s);
        }
        Summary sa = sum(a, id, rnd, true, nOut, eOut);
        reports.put("typical", ReportBuilder.build(nOut[0], sa, eOut[0], prevs, null, today + 8 * 3600000L, 450));
        nights.add(ReportBuilder.nightItem(nOut[0], sa));
        for (int i = 0; i < older.length; i++) {
            Rec.Night nn = night(100 + i, older[i]);
            nights.add(ReportBuilder.nightItem(nn, prevs.get(i)));
        }

        // ---- 安静的一晚
        Spec q = new Spec(); q.name = "quiet"; q.startMs = today - 1 * DAY + 23 * 3600000L; q.minutes = 455; q.onset = 15;
        q.snore = new int[][] { { 200, 206, 40 } };
        Summary sq = sum(q, 20, rnd, true, nOut, eOut);
        reports.put("quiet", ReportBuilder.build(nOut[0], sq, eOut[0], prevs, null, today + 8 * 3600000L, 450));

        // ---- 很重的一晚 + 就医提示
        Spec h = new Spec(); h.name = "heavy"; h.startMs = today - 1 * DAY + 22 * 3600000L + 50 * 60000L; h.minutes = 380; h.onset = 40;
        h.snore = new int[][] { { 60, 340, 66 } };
        h.apneaAt = new int[] { 80, 92, 105, 118, 131, 160, 175, 190, 210, 230, 250, 270, 290, 310 };
        h.coughAt = new int[] { 100, 200 }; h.wakeRuns = new int[] { 150, 156, 255, 262, 300, 306 };
        Summary sh = sum(h, 30, rnd, true, nOut, eOut);
        reports.put("heavy", ReportBuilder.build(nOut[0], sh, eOut[0], prevs, "连续 5 晚疑似暂停指数不低于 15", today + 8 * 3600000L, 450));

        // ---- 太短
        Spec t = new Spec(); t.name = "short"; t.startMs = today - 1 * DAY + 23 * 3600000L; t.minutes = 85; t.onset = 20;
        t.snore = new int[][] { { 40, 70, 50 } };
        Summary st = sum(t, 40, rnd, false, nOut, eOut);
        reports.put("short", ReportBuilder.build(nOut[0], st, eOut[0], prevs, null, today + 8 * 3600000L, 450));

        // ---- 没有体动数据 + 中途被中断 + 模型没加载
        Spec nm = new Spec(); nm.name = "nomotion"; nm.startMs = today - 1 * DAY + 23 * 3600000L + 30 * 60000L; nm.minutes = 300; nm.motion = false; nm.flags = 1 | 2; nm.onset = 30;
        nm.snore = new int[][] { { 60, 200, 55 } }; nm.apneaAt = new int[] { 120 };
        Summary sn = sum(nm, 50, rnd, false, nOut, eOut);
        reports.put("nomotion", ReportBuilder.build(nOut[0], sn, eOut[0], prevs, null, today + 8 * 3600000L, 450));

        // ---- 第一晚（没有对比数据）
        Spec f = new Spec(); f.name = "first"; f.startMs = today - 1 * DAY + 23 * 3600000L + 5 * 60000L; f.minutes = 420; f.onset = 22;
        f.snore = new int[][] { { 100, 160, 56 } }; f.apneaAt = new int[] { 130 };
        Summary sf = sum(f, 60, rnd, true, nOut, eOut);
        reports.put("first", ReportBuilder.build(nOut[0], sf, eOut[0], new ArrayList<Summary>(), null, today + 8 * 3600000L, 450));

        StringBuilder sb = new StringBuilder();
        sb.append("window.FIX = ").append(Json.write(Json.m("reports", reports, "nights", nights))).append(";\n");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outPath), StandardCharsets.UTF_8)) { w.write(sb.toString()); }
        System.out.println("fixture " + sb.length() + " bytes");
        // 简要摘要，方便人工核对
        for (Map.Entry<String, Object> e : reports.entrySet()) {
            Map<String, Object> r = (Map<String, Object>) e.getValue();
            System.out.println(e.getKey() + ": " + r.get("headline") + " | 评分 " + r.get("score") + " | " + r.get("scoreNote"));
        }
    }
}
