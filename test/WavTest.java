import com.yehang.app.core.Wav;
public class WavTest {
    public static void main(String[] a) throws Exception {
        short[] pcm = new short[16000 * 3];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (800 * Math.sin(2 * Math.PI * 120 * i / 16000.0));
        Wav.write(new java.io.File(a[0]), pcm, 0, pcm.length);
    }
}
