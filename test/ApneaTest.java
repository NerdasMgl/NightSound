import com.yehang.app.core.Analyzer;
import java.util.*;

/** 合成鼾声串：正常呼吸周期 vs 插入静默，检查疑似呼吸暂停的判定。 */
public class ApneaTest {
    static void burst(short[] x, int at, double db, Random r) {
        int n = (int) (1.2 * 16000);
        double amp = Math.pow(10, (db - 90) / 20) * 1.414;
        for (int i = 0; i < n && at + i < x.length; i++) {
            double t = i / 16000.0;
            double env = Math.min(1, t / 0.4) * Math.min(1, (1.2 - t) / 0.5);
            double v = 0;
            for (int h = 1; h <= 8; h++) v += Math.sin(2 * Math.PI * 70 * h * t + h) / (h * 0.8);
            v = v * 0.45 + r.nextGaussian() * 0.08;
            x[at + i] = (short) (v * env * amp * 32768);
        }
    }
    static List<Analyzer.Ev> run(short[] pcm) {
        final List<Analyzer.Ev> out = new ArrayList<>();
        Analyzer a = new Analyzer(new Analyzer.Listener() {
            public void onEvent(Analyzer.Ev e) { out.add(e); }
            public void onSecond(float l, float f) { }
        }, 1);
        for (int i = 0; i < pcm.length; i += 1600) a.feed(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 1600)), Math.min(1600, pcm.length - i));
        a.flush();
        return out;
    }
    static void scenario(String name, double[] cycleSecs, double[] silenceAfter) {
        Random r = new Random(1);
        int total = 16000 * 400; short[] x = new short[total];
        for (int i = 0; i < total; i++) x[i] = (short) (r.nextGaussian() * Math.pow(10, (27 - 90) / 20.0) * 32768);
        double t = 70;
        for (int i = 0; i < cycleSecs.length; i++) { burst(x, (int) (t * 16000), 58 + r.nextGaussian(), r); t += cycleSecs[i]; if (i < silenceAfter.length) t += silenceAfter[i]; }
        List<Analyzer.Ev> ev = run(x);
        int s = 0, ap = 0; StringBuilder sb = new StringBuilder();
        for (Analyzer.Ev e : ev) { if (e.type == Analyzer.T_SNORE) s++; if (e.type == Analyzer.T_APNEA) { ap++; sb.append(String.format(" [%.0fs, 静默 %.0f 秒]", e.startMs() / 1000.0, e.extra)); } }
        System.out.printf("%-28s 鼾声事件 %2d / 突发 %2d  疑似暂停 %d%s%n", name, s, cycleSecs.length, ap, sb);
    }
    public static void main(String[] a) {
        double[] regular = new double[30]; Arrays.fill(regular, 4.5);
        scenario("规律鼾声，无暂停", regular, new double[0]);
        double[] c = new double[30]; Arrays.fill(c, 4.5);
        double[] sil = new double[30]; sil[12] = 17; // 第 13 个鼾声后静默 17 秒
        scenario("中间静默 17 秒", c, sil);
        double[] sil2 = new double[30]; sil2[12] = 6; sil2[20] = 22;
        scenario("静默 6 秒 + 22 秒", c, sil2);
        double[] sil3 = new double[30]; sil3[4] = 90;
        scenario("静默 90 秒（翻身/停止）", c, sil3);
    }
}
