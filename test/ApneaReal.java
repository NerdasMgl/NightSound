import com.yehang.app.core.Analyzer;
import com.yehang.app.core.YamNet;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 用真实打鼾声（从 ESC-50 里挑出模型得分最高的 1 秒）排成规律的鼾声串，中间插入不同长度的静默，检查疑似呼吸暂停的检出和误报。 */
public class ApneaReal {
    static short[] read(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        YamNet model = new YamNet(new FileInputStream(args[1]));
        double[] gaps = { 0, 8, 14, 20, 30 };
        File[] fs = new File(dir).listFiles(); Arrays.sort(fs);
        List<short[]> tiles = new ArrayList<>(); List<Double> floors = new ArrayList<>();
        for (File f : fs) {
            short[] x = read(f.getPath());
            float[] best = null; float bestSc = 0; int bestAt = 0;
            for (int at = 0; at + 15600 <= x.length; at += 4000) {
                float[] w = new float[15600]; for (int i = 0; i < w.length; i++) w[i] = x[at + i] / 32768f;
                double s = 0; for (float v : w) s += v * v; double rms = Math.sqrt(s / w.length);
                if (rms < 1e-4) continue;
                float g = (float) Math.min(30, 0.05 / rms); float[] n = new float[w.length]; for (int i = 0; i < w.length; i++) n[i] = Math.max(-1, Math.min(1, w[i] * g));
                float sc = model.scores(n)[YamNet.C_SNORING];
                if (sc > bestSc) { bestSc = sc; bestAt = at; }
            }
            if (bestSc < 0.9f) continue;
            short[] t = Arrays.copyOfRange(x, bestAt, bestAt + 15600);
            for (int i = 0; i < 800; i++) { double a = i / 800.0; t[i] = (short) (t[i] * a); t[15600 - 1 - i] = (short) (t[15600 - 1 - i] * a); }
            tiles.add(t);
            // 噪声底：该文件最安静的 1 秒
            double mn = 1e9; for (int at = 0; at + 16000 <= x.length; at += 4000) { double m = 0; for (int i = 0; i < 16000; i++) { double v = x[at + i] / 32768.0; m += v * v; } mn = Math.min(mn, 10 * Math.log10(m / 16000 + 1e-12) + 90); }
            floors.add(mn);
        }
        System.out.println("可用的真实鼾声片段: " + tiles.size());
        Random rnd = new Random(5);
        for (double gap : gaps) {
            int trials = 0, hit = 0, any = 0;
            for (int ti = 0; ti < tiles.size(); ti++) {
                for (int rep = 0; rep < 2; rep++) {
                    short[] tile = tiles.get(ti);
                    double bedDb = Math.max(22, floors.get(ti));
                    int pre = 10, post = 6;
                    double cycle = 3.6 + rnd.nextDouble() * 1.6;
                    int total = 16000 * (60 + (int) (cycle * (pre + post)) + (int) gap + 30);
                    short[] bed = new short[total];
                    double rms = Math.pow(10, (bedDb - 90) / 20) * 32768;
                    for (int i = 0; i < total; i++) bed[i] = (short) Math.max(-32768, Math.min(32767, rnd.nextGaussian() * rms));
                    double t = 60; double gapStart = -1, gapEnd = -1;
                    for (int k = 0; k < pre + post; k++) {
                        if (k == pre && gap > 0) { gapStart = t; t += gap; gapEnd = t; }
                        int at = (int) (t * 16000);
                        double lvl = 0.8 + rnd.nextDouble() * 0.4;
                        for (int i = 0; i < tile.length && at + i < total; i++) bed[at + i] = (short) Math.max(-32768, Math.min(32767, bed[at + i] + tile[i] * lvl));
                        t += cycle * (0.9 + rnd.nextDouble() * 0.2);
                    }
                    final List<Analyzer.Ev> evs = new ArrayList<>();
                    Analyzer a = new Analyzer(new Analyzer.Listener() { public void onEvent(Analyzer.Ev e) { evs.add(e); } public void onSecond(float l, float f) { } }, 1, model, false);
                    for (int i = 0; i < bed.length; i += 1600) a.feed(Arrays.copyOfRange(bed, i, Math.min(bed.length, i + 1600)), Math.min(1600, bed.length - i));
                    a.flush();
                    trials++;
                    boolean found = false, anyApnea = false;
                    for (Analyzer.Ev e : evs) if (e.type == Analyzer.T_APNEA) { anyApnea = true; if (gap > 0 && e.startMs() / 1000.0 >= gapStart - 8 && e.startMs() / 1000.0 <= gapEnd + 2) found = true; }
                    if (found) hit++; if (anyApnea) any++;
                    if (gap == 20 && !found) { int ns = 0; for (Analyzer.Ev e : evs) if (e.type == Analyzer.T_SNORE) ns++; System.out.printf("  漏检: tile %d 噪声底 %.0f dB 鼾声事件 %d / %d 声%n", ti, floors.get(ti), ns, pre + post); }
                }
            }
            System.out.printf("静默 %4.0f 秒 | 试验 %d | 检出疑似暂停 %3.0f%% | 出现任何暂停事件 %3.0f%%%n", gap, trials, 100.0 * hit / trials, 100.0 * any / trials);
        }
    }
}
