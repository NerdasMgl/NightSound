import com.yehang.app.core.*;
import java.util.*;

public class SummaryTest {
    public static void main(String[] a) {
        Rec.Night n = new Rec.Night();
        n.startMs = 1_000_000_000L; n.endMs = n.startMs + 8L * 3600_000;
        List<Rec.Epoch> eps = new ArrayList<Rec.Epoch>();
        Random r = new Random(3);
        for (int m = 0; m < 480; m++) {
            Rec.Epoch e = new Rec.Epoch();
            e.t = n.startMs + m * 60000L;
            boolean playing = m < 18;               // 前18分钟玩手机
            e.act = playing ? 0.3f : 0.01f; e.actMax = playing ? 1.0f : 0.05f;
            if (!playing && r.nextInt(30) == 0) { e.actMax = 0.6f; e.act = 0.1f; } // 翻身
            if (m >= 250 && m < 262) { e.actMax = 1.8f; e.act = 0.4f; }   // 起夜
            if (m >= 250 && m < 262) e.screenSec = 20;
            if (playing) e.screenSec = 45;
            e.noise = 28; e.lux = 0; 
            eps.add(e);
        }
        List<Rec.Event> evs = new ArrayList<Rec.Event>();
        for (int k = 0; k < 400; k++) {
            Rec.Event e = new Rec.Event(); e.type = Analyzer.T_SNORE;
            e.startMs = n.startMs + (30 + k) * 60000L + (k % 7) * 3000; e.endMs = e.startMs + 1500; e.peakDb = 40 + (k % 30);
            evs.add(e);
        }
        for (int k = 0; k < 5; k++) {
            Rec.Event e = new Rec.Event(); e.type = Analyzer.T_APNEA;
            e.startMs = n.startMs + (120 + k * 20) * 60000L; e.endMs = e.startMs + 14000; e.extra = 14;
            evs.add(e);
        }
        Summary s = Summary.compute(n, eps, evs, 450);
        System.out.println("inBed=" + s.inBedMin + " latency=" + s.latencyMin + " sleep=" + s.sleepMin + " deep=" + s.deepMin + " light=" + s.lightMin
            + " awake=" + s.awakeMin + " awakenings=" + s.awakenings + " eff=" + s.efficiency + " motionOk=" + s.motionOk);
        System.out.println("snoreEvents=" + s.snoreEvents + " snoreMin=" + s.snoreMin + " pct=" + s.snorePct + " idx=" + s.snoreIdx + " apnea=" + s.apneaCount + " apIdx=" + s.apneaIdx + " max=" + s.apneaMaxSec);
        System.out.println("score=" + s.score + " parts=" + s.partNames);
        for (float[] p : s.parts) System.out.print(p[0] + "/" + p[1] + " ");
        System.out.println();
        // 无加速度计场景
        for (Rec.Epoch e : eps) { e.act = -1; e.actMax = -1; }
        Summary s2 = Summary.compute(n, eps, evs, 450);
        System.out.println("noaccel: motionOk=" + s2.motionOk + " sleep=" + s2.sleepMin + " score=" + s2.score + " " + s2.partNames);
        // 太短
        Rec.Night n3 = new Rec.Night(); n3.startMs = 0; n3.endMs = 30 * 60000L;
        Summary s3 = Summary.compute(n3, new ArrayList<Rec.Epoch>(), new ArrayList<Rec.Event>(), 450);
        System.out.println("short: tooShort=" + s3.tooShort + " score=" + s3.score);
    }
}
