import com.yehang.app.core.YamNet;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** 读 windows.txt（每行：pcm 路径 起始样本），对每个窗口输出 521 个得分（CSV），并统计耗时。 */
public class YamNetTest {
    public static void main(String[] a) throws Exception {
        YamNet m = new YamNet(new FileInputStream(a[0]));
        List<String> lines = Files.readAllLines(Paths.get(a[1]));
        PrintWriter out = new PrintWriter(new FileWriter(a[2]));
        long t0 = System.nanoTime(); int n = 0; long best = Long.MAX_VALUE;
        for (String ln : lines) {
            String[] p = ln.trim().split(" ");
            byte[] b = Files.readAllBytes(Paths.get(p[0]));
            ShortBuffer sb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int off = Integer.parseInt(p[1]);
            float[] w = new float[YamNet.WAVE];
            for (int i = 0; i < w.length; i++) w[i] = sb.get(off + i) / 32768f;
            long s = System.nanoTime();
            float[] sc = m.scores(w);
            long e = System.nanoTime() - s; best = Math.min(best, e);
            n++;
            StringBuilder sbd = new StringBuilder();
            for (int i = 0; i < sc.length; i++) { if (i > 0) sbd.append(','); sbd.append(sc[i]); }
            out.println(sbd);
        }
        out.close();
        System.err.printf("windows=%d avg=%.1f ms best=%.1f ms%n", n, (System.nanoTime() - t0) / 1e6 / n, best / 1e6);
    }
}
