import com.yehang.app.core.Analyzer;
import com.yehang.app.core.YamNet;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 片段级评估：每个 5 秒真实录音放进噪声底里，看它被判成哪些事件类型（≥1 个事件即算命中）。 */
public class ClipEval {
    static short[] read(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }
    static double clipFloor(short[] x) {
        int secs = x.length / 16000; double[] l = new double[secs];
        for (int k = 0; k < secs; k++) { double m = 0; for (int i = 0; i < 16000; i++) { double v = x[k * 16000 + i] / 32768.0; m += v * v; } l[k] = 10 * Math.log10(m / 16000 + 1e-12) + 90; }
        Arrays.sort(l); return l[(int) (secs * 0.15)];
    }
    public static void main(String[] args) throws Exception {
        String raw = args[0]; int sens = Integer.parseInt(args[1]);
        YamNet model = args[2].equals("-") ? null : new YamNet(new FileInputStream(args[2]));
        double boost = Double.parseDouble(args[3]);
        Random rnd = new Random(7);
        String[] classes = new File(raw).list(); Arrays.sort(classes);
        System.out.printf("%-18s %4s | %6s %6s %6s %6s %6s   (clip-level: fraction of clips with ≥1 event of type)%n", "class", "n", "snore", "cough", "talk", "grind", "other");
        double[] allSnoreFp = new double[2];
        for (String c : classes) {
            if (System.getProperty("yh.only") != null && !c.equals(System.getProperty("yh.only"))) continue;
            File[] fs = new File(raw, c).listFiles(); Arrays.sort(fs);
            int n = 0; int[] hit = new int[7];
            for (int start = 0; start + 6 <= fs.length; start += 6) {
                short[][] cl = new short[6][]; double[] fl = new double[6];
                for (int k = 0; k < 6; k++) { cl[k] = read(fs[start + k].getPath()); fl[k] = clipFloor(cl[k]); }
                double[] sf = fl.clone(); Arrays.sort(sf); double bedDb = Math.max(20, sf[3]) + boost;
                int total = 16000 * (60 + 6 * 5 + 8);
                short[] bed = new short[total];
                double rms = Math.pow(10, (bedDb - 90) / 20) * 32768;
                for (int i = 0; i < total; i++) bed[i] = (short) Math.max(-32768, Math.min(32767, rnd.nextGaussian() * rms));
                for (int k = 0; k < 6; k++) for (int i = 0; i < cl[k].length; i++) { int at = 16000 * (60 + 5 * k) + i; bed[at] = (short) Math.max(-32768, Math.min(32767, bed[at] + cl[k][i])); }
                final List<Analyzer.Ev> evs = new ArrayList<>();
                Analyzer a = new Analyzer(new Analyzer.Listener() { public void onEvent(Analyzer.Ev e) { evs.add(e); } public void onSecond(float l, float f) { } }, sens, model, false);
                for (int i = 0; i < bed.length; i += 1600) a.feed(Arrays.copyOfRange(bed, i, Math.min(bed.length, i + 1600)), Math.min(1600, bed.length - i));
                a.flush();
                for (int k = 0; k < 6; k++) {
                    double t0 = 60 + 5 * k - 0.3, t1 = 65 + 5 * k + 0.5;
                    boolean[] h = new boolean[7];
                    for (Analyzer.Ev e : evs) { double t = e.startMs() / 1000.0, te = e.endMs() / 1000.0; if (te >= t0 && t <= t1) h[e.type] = true; }
                    n++; for (int t = 1; t <= 6; t++) if (h[t]) hit[t]++;
                    if (System.getProperty("yh.debug") != null) System.out.printf("CLIP %s #%d window %.1f-%.1f snore=%s%n", c, n, t0, t1, h[1]);
                }
            }
            System.out.printf("%-18s %4d | %5.0f%% %5.0f%% %5.0f%% %5.0f%% %5.0f%%%n", c, n, 100.0 * hit[1] / n, 100.0 * hit[5] / n, 100.0 * hit[3] / n, 100.0 * hit[4] / n, 100.0 * hit[6] / n);
        }
    }
}
