import com.yehang.app.core.Analyzer;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 用 ESC-50 真实录音检查检测器（只在本机测试用，音频不进 APK）。 */
public class AnalyzerTest {
    static String RAW;
    static com.yehang.app.core.YamNet MODEL;
    static double BOOST = 0;
    static short[] read(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }
    static short[] noise(int n, double db, Random r) {
        double rms = Math.pow(10, (db - 90) / 20) * 32768;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) Math.max(-32768, Math.min(32767, r.nextGaussian() * rms));
        return s;
    }
    static void put(short[] dst, int at, short[] src, double gain) {
        for (int i = 0; i < src.length && at + i < dst.length; i++) {
            int v = dst[at + i] + (int) (src[i] * gain);
            dst[at + i] = (short) Math.max(-32768, Math.min(32767, v));
        }
    }
    /** 片段自身的“噪声底”：逐秒电平的最小值附近（第 15 百分位）。 */
    static double clipFloor(short[] x) {
        int secs = x.length / 16000; double[] l = new double[secs];
        for (int k = 0; k < secs; k++) { double m = 0; for (int i = 0; i < 16000; i++) { double v = x[k * 16000 + i] / 32768.0; m += v * v; } l[k] = 10 * Math.log10(m / 16000 + 1e-12) + 90; }
        Arrays.sort(l); return l[(int) (secs * 0.15)];
    }
    static class Run { List<Analyzer.Ev> ev = new ArrayList<>(); }
    static Run run(short[] pcm, int sens) {
        final Run r = new Run();
        Analyzer a = new Analyzer(new Analyzer.Listener() {
            public void onEvent(Analyzer.Ev e) { r.ev.add(e); }
            public void onSecond(float l, float f) { }
        }, sens, MODEL, false);
        for (int i = 0; i < pcm.length; i += 1600) a.feed(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 1600)), Math.min(1600, pcm.length - i));
        a.flush();
        return r;
    }
    static int count(Run r, int type) { int n = 0; for (Analyzer.Ev e : r.ev) if (e.type == type) n++; return n; }

    public static void main(String[] args) throws Exception {
        RAW = args[0];
        int sens = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        if (args.length > 3) BOOST = Double.parseDouble(args[3]);
        if (args.length > 2 && !args[2].equals("-")) MODEL = new com.yehang.app.core.YamNet(new FileInputStream(args[2]));
        Random rnd = new Random(42);
        String[] classes = new File(RAW).list(); Arrays.sort(classes);
        System.out.printf("%-18s %6s | snore cough talk grind other apnea (events per 6-clip, 36 s episode, mean)%n", "class", "ep");
        for (String c : classes) {
            File[] fs = new File(RAW, c).listFiles(); Arrays.sort(fs);
            int eps = 0; int[] tot = new int[7]; int snoreEpisodesHit = 0;
            for (int start = 0; start + 6 <= fs.length; start += 6) {
                short[][] cl = new short[6][]; double[] fl = new double[6];
                for (int k = 0; k < 6; k++) { cl[k] = read(fs[start + k].getPath()); fl[k] = clipFloor(cl[k]); }
                double[] sf = fl.clone(); Arrays.sort(sf); double bedDb = Math.max(20, sf[3]) + BOOST;
                short[] bed = noise(16000 * (70 + 6 * 5 + 5), bedDb, rnd);
                for (int k = 0; k < 6; k++) put(bed, 16000 * (60 + 5 * k), cl[k], 1.0);
                Run r = run(bed, sens);
                eps++; for (int t = 1; t <= 6; t++) tot[t] += count(r, t);
                if (count(r, Analyzer.T_SNORE) > 0) snoreEpisodesHit++;
            }
            System.out.printf("%-18s %6d | %5.1f %5.1f %4.1f %5.1f %5.1f %5.1f   snore-hit %d/%d%n", c, eps,
                tot[1] / (double) eps, tot[5] / (double) eps, tot[3] / (double) eps, tot[4] / (double) eps, tot[6] / (double) eps, tot[2] / (double) eps, snoreEpisodesHit, eps);
        }
    }
}
