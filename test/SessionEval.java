import com.yehang.app.core.Analyzer;
import com.yehang.app.core.YamNet;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 分钟级评估：把同一类的 12 个 5 秒录音拼成 1 分钟，放在噪声底里，看这一分钟被判成打鼾的概率（≥3 声）和平均鼾声数。 */
public class SessionEval {
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
        int sessions = Integer.parseInt(args[4]);
        String only = args.length > 5 ? args[5] : null;
        Random rnd = new Random(11);
        String[] classes = new File(raw).list(); Arrays.sort(classes);
        System.out.printf("%-18s | %s%n", "class", "该分钟被判为打鼾(≥3声) | 平均鼾声数 | 咳嗽(≥1) | 说话(≥1) | 磨牙(≥1)");
        for (String c : classes) {
            if (only != null && !only.contains(c)) continue;
            File[] fs = new File(raw, c).listFiles(); Arrays.sort(fs);
            short[][] all = new short[fs.length][]; double[] fl = new double[fs.length];
            for (int k = 0; k < fs.length; k++) { all[k] = read(fs[k].getPath()); fl[k] = clipFloor(all[k]); }
            int snoreMin = 0, cough = 0, talk = 0, grind = 0; double snoreSum = 0;
            for (int s = 0; s < sessions; s++) {
                List<Integer> idx = new ArrayList<>(); for (int k = 0; k < fs.length; k++) idx.add(k); Collections.shuffle(idx, rnd);
                int m = Math.min(12, fs.length);
                double[] sf = new double[m]; for (int k = 0; k < m; k++) sf[k] = fl[idx.get(k)];
                Arrays.sort(sf); double bedDb = Math.max(20, sf[m / 2 - 1]) + boost;
                int total = 16000 * (60 + 5 * m + 8);
                short[] bed = new short[total];
                double rms = Math.pow(10, (bedDb - 90) / 20) * 32768;
                for (int i = 0; i < total; i++) bed[i] = (short) Math.max(-32768, Math.min(32767, rnd.nextGaussian() * rms));
                for (int k = 0; k < m; k++) { short[] cl = all[idx.get(k)]; for (int i = 0; i < cl.length; i++) { int at = 16000 * (60 + 5 * k) + i; bed[at] = (short) Math.max(-32768, Math.min(32767, bed[at] + cl[i])); } }
                final int[] cnt = new int[8];
                Analyzer a = new Analyzer(new Analyzer.Listener() { public void onEvent(Analyzer.Ev e) { if (e.startMs() >= 59000) cnt[e.type]++; } public void onSecond(float l, float f) { } }, sens, model, false);
                for (int i = 0; i < bed.length; i += 1600) a.feed(Arrays.copyOfRange(bed, i, Math.min(bed.length, i + 1600)), Math.min(1600, bed.length - i));
                a.flush();
                if (cnt[1] >= 3) snoreMin++;
                snoreSum += cnt[1];
                if (cnt[5] > 0) cough++;
                if (cnt[3] > 0) talk++;
                if (cnt[4] > 0) grind++;
            }
            System.out.printf("%-18s | %5.0f%% | %5.1f | %4.0f%% | %4.0f%% | %4.0f%%%n", c, 100.0 * snoreMin / sessions, snoreSum / sessions, 100.0 * cough / sessions, 100.0 * talk / sessions, 100.0 * grind / sessions);
        }
    }
}
