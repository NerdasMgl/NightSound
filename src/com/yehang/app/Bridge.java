package com.yehang.app;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

import com.yehang.app.core.Advice;
import com.yehang.app.core.Json;
import com.yehang.app.core.Rec;
import com.yehang.app.core.ReportBuilder;
import com.yehang.app.core.Summary;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 界面（WebView 里的网页）与系统之间的桥，在网页里叫 window.YH。
 * 约定：参数全是字符串；返回值要么是 JSON 字符串，要么是简单字符串。
 * 注意：@JavascriptInterface 方法在 WebView 自己的后台线程上执行，凡是碰界面、权限、播放器的事都要切到主线程。
 */
public final class Bridge {
    private static final long START_TIMEOUT_MS = 8000;

    private final MainActivity a;
    private final Prefs prefs;
    private final Db db;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 上次监测被系统中断的提示。放在静态里，界面重建后还在。 */
    static volatile String interruptedMsg;
    static volatile long interruptedNight;

    private volatile boolean waitingStart;
    private volatile long waitingSince;
    private volatile String startError;
    private Boolean modelFile;

    private final HashMap<Long, Summary> sumCache = new HashMap<Long, Summary>();
    private int sumCacheGoal = -1;

    private MediaPlayer player;       // 只在主线程访问
    private long playingId;

    private volatile Thread micThread;
    private volatile boolean micRun;
    private volatile float micLevel;

    Bridge(MainActivity a, Prefs prefs, Db db) { this.a = a; this.prefs = prefs; this.db = db; }

    // ================================================================= 环境与状态
    @JavascriptInterface public String env() {
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("version", BuildInfo.VERSION);
            m.put("sdk", Build.VERSION.SDK_INT);
            m.put("dark", a.isDark());
            m.put("introDone", prefs.introDone());
            m.put("hasMic", a.hasMic());
            m.put("hasNotif", a.hasNotif());
            m.put("ignoringBattery", a.ignoringBattery());
            m.put("batteryHintDone", prefs.batteryHintDone());
            m.put("modelFile", hasModelFile());
            m.put("audioSource", MonitorState.audioSource);
            return Json.write(m);
        } catch (Throwable t) { return fail("env", t); }
    }

    private boolean hasModelFile() {
        if (modelFile == null) {
            boolean ok = false;
            try { a.getAssets().open("yamnet.bin").close(); ok = true; } catch (Throwable ignore) { }
            modelFile = ok;
        }
        return modelFile;
    }

    @JavascriptInterface public String state() {
        try {
            boolean running = MonitorState.running;
            if (waitingStart) {
                if (running) waitingStart = false;
                else if (MonitorState.error != null || System.currentTimeMillis() - waitingSince > START_TIMEOUT_MS) {
                    waitingStart = false;
                    startError = MonitorState.error != null ? MonitorState.error
                            : "监测没能启动。可以在“我的 > 高级”里点“分享诊断信息”，把日志发给开发者看看。";
                }
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("running", running);
            m.put("waiting", waitingStart && !running);
            m.put("startMs", MonitorState.startMs);
            m.put("level", MonitorState.level);
            m.put("floor", MonitorState.floor);
            m.put("snore", MonitorState.snoreCount);
            m.put("apnea", MonitorState.apneaCount);
            m.put("sounds", MonitorState.soundCount);
            m.put("error", running ? MonitorState.error : null);
            m.put("startError", startError);
            m.put("micSilent", MonitorState.micSilent);
            m.put("accelOk", MonitorState.accelOk);
            m.put("finishedNight", MonitorState.finishedNight);
            m.put("lastSnoreMs", MonitorState.lastSnoreMs);
            m.put("stopReason", MonitorState.stopReason);
            return Json.write(m);
        } catch (Throwable t) { return fail("state", t); }
    }

    // ================================================================= 数据
    private Summary summaryOf(Rec.Night n) {
        synchronized (sumCache) {
            int goal = prefs.goalMin();
            if (goal != sumCacheGoal) { sumCache.clear(); sumCacheGoal = goal; }
            Summary s = sumCache.get(n.id);
            if (s != null && s.endMs == (n.endMs > 0 ? n.endMs : s.endMs)) return s;
        }
        Summary s = Data.summary(db, prefs, n);
        synchronized (sumCache) { sumCache.put(n.id, s); }
        return s;
    }

    private void dropCache() { synchronized (sumCache) { sumCache.clear(); } }

    private static Double sleepyAfter(List<Rec.CheckIn> cks, long endMs) {
        double sum = 0; int cnt = 0;
        for (Rec.CheckIn c : cks) if (c.ts >= endMs && c.ts < endMs + 20L * 3600000L) { sum += c.val; cnt++; }
        return cnt == 0 ? null : Double.valueOf(Math.round(sum / cnt * 10) / 10.0);
    }

    @JavascriptInterface public String nights() {
        try {
            List<Rec.Night> ns = db.finishedNights(60);
            List<Object> out = new ArrayList<Object>();
            long since = ns.isEmpty() ? 0 : ns.get(ns.size() - 1).endMs;
            List<Rec.CheckIn> cks = db.checkIns(since);
            for (Rec.Night n : ns) {
                Summary s = summaryOf(n);
                Map<String, Object> it = ReportBuilder.nightItem(n, s);
                it.put("sleepy", sleepyAfter(cks, s.endMs));
                out.add(it);
            }
            return Json.write(out);
        } catch (Throwable t) { return fail("nights", t); }
    }

    @JavascriptInterface public String home() {
        try {
            List<Rec.Night> ns = db.finishedNights(5);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ArrayList<Summary> recent = new ArrayList<Summary>();
            for (Rec.Night n : ns) recent.add(summaryOf(n));
            m.put("latest", ns.isEmpty() ? null : ReportBuilder.nightItem(ns.get(0), recent.get(0)));
            String why = Advice.seeDoctorReason(recent, Data.recentRatings(db, 7));
            m.put("doctor", why == null ? null : Json.m("reason", why, "text", Advice.SEE_DOCTOR, "drive", Advice.DONT_DRIVE));
            String im = interruptedMsg;
            m.put("interrupted", im == null ? null : Json.m("night", interruptedNight, "msg", im));
            return Json.write(m);
        } catch (Throwable t) { return fail("home", t); }
    }

    @JavascriptInterface public String report(String idStr) {
        try {
            long id = Long.parseLong(idStr);
            Rec.Night n = db.night(id);
            if (n == null) return "null";
            Summary s = summaryOf(n);
            ArrayList<Summary> prev = new ArrayList<Summary>();
            for (Rec.Night p : db.finishedNights(40)) {
                if (p.startMs < n.startMs && prev.size() < 14) prev.add(summaryOf(p));
            }
            ArrayList<Summary> chain = new ArrayList<Summary>();
            chain.add(s);
            for (int i = 0; i < prev.size() && chain.size() < 5; i++) chain.add(prev.get(i));
            String why = Advice.seeDoctorReason(chain, Data.recentRatings(db, 7));
            Map<String, Object> r = ReportBuilder.build(n, s, db.epochs(n.id), prev, why, System.currentTimeMillis(), prefs.goalMin());
            return Json.write(r);
        } catch (Throwable t) { return fail("report", t); }
    }

    @JavascriptInterface public String prefs() {
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("sens", prefs.sensitivity());
            m.put("goalMin", prefs.goalMin());
            m.put("saveClips", prefs.saveClips());
            m.put("clipDays", prefs.clipDays());
            m.put("grind", prefs.grind());
            m.put("bedsideOn", prefs.bedsideOn());
            ArrayList<String> tags = new ArrayList<String>();
            for (String t : prefs.pendingTags().split(",")) if (t.length() > 0) tags.add(t);
            m.put("tags", tags);
            m.put("clipBytes", db.clipBytes());
            return Json.write(m);
        } catch (Throwable t) { return fail("prefs", t); }
    }

    // ================================================================= 设置与记录
    @JavascriptInterface public void setPref(String k, String v) {
        try {
            int i = Integer.parseInt(v);
            if ("sens".equals(k)) prefs.setSensitivity(Math.max(0, Math.min(2, i)));
            else if ("goal".equals(k)) { prefs.setGoalMin(Math.max(240, Math.min(720, i))); dropCache(); }
            else if ("grind".equals(k)) prefs.setGrind(i != 0);
            else if ("clips".equals(k)) prefs.setSaveClips(i != 0);
            else if ("clipDays".equals(k)) prefs.setClipDays(Math.max(1, Math.min(90, i)));
            else if ("bedside".equals(k)) { prefs.setBedsideOn(i != 0); ui.post(new Runnable() { public void run() { a.reapplyTheme(); } }); }
        } catch (Throwable t) { Diag.log(a, "setPref " + k + "=" + v + " 失败：" + t); }
    }

    @JavascriptInterface public void setTags(String csv) { prefs.setPendingTags(csv == null ? "" : csv); }

    @JavascriptInterface public void putCheckIn(String v) {
        try {
            int val = Integer.parseInt(v);
            if (val < 1 || val > 5) return;
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            db.putCheckIn(h < 12 ? 0 : (h < 18 ? 1 : 2), val);
        } catch (Throwable t) { Diag.log(a, "putCheckIn 失败：" + t); }
    }

    @JavascriptInterface public void deleteNight(String idStr) {
        try { stopClip(); db.deleteNight(Long.parseLong(idStr)); dropCache(); } catch (Throwable t) { Diag.log(a, "deleteNight 失败：" + t); }
    }

    @JavascriptInterface public void deleteAll() {
        try { stopClip(); db.deleteAll(); dropCache(); interruptedMsg = null; } catch (Throwable t) { Diag.log(a, "deleteAll 失败：" + t); }
    }

    @JavascriptInterface public void clearClips() {
        try { stopClip(); db.clearClips(); } catch (Throwable t) { Diag.log(a, "clearClips 失败：" + t); }
    }

    @JavascriptInterface public String exportCsv() {
        try {
            String where = Export.csv(a, db, prefs);
            return Json.write(Json.m("ok", true, "where", where));
        } catch (Throwable t) {
            Diag.log(a, "导出失败：" + t);
            return Json.write(Json.m("ok", false, "err", String.valueOf(t.getMessage())));
        }
    }

    @JavascriptInterface public void shareDiag() {
        ui.post(new Runnable() { public void run() {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, Diag.report(a));
                a.startActivity(Intent.createChooser(i, "分享诊断信息"));
            } catch (Throwable t) { Diag.log(a, "分享诊断失败：" + t); }
        } });
    }

    @JavascriptInterface public void dismissInterrupted() { interruptedMsg = null; interruptedNight = 0; }

    /** 孤儿夜：上次监测被系统杀掉、没来得及结束的那一晚。回到前台时由 Activity 调用。 */
    void checkOrphans() {
        if (MonitorState.running) return;
        ArrayList<Long> ids = db.closeOrphans();
        if (!ids.isEmpty()) {
            long id = ids.get(ids.size() - 1);
            Rec.Night n = db.night(id);
            if (n != null) {
                interruptedNight = id;
                interruptedMsg = "上一次监测在 " + com.yehang.app.core.Fmt.clock(n.endMs) + " 左右被系统中断（后台被清理，或手机关机、没电），已保存中断之前的记录。"
                        + "要整晚监测，请到“我的 > 让夜莺整晚不被关掉”看看怎么设置，开始前接上充电线。";
                dropCache();
            }
        }
        if (prefs.activeNight() != 0) prefs.setActiveNight(0);
    }

    // ================================================================= 权限、开始、结束
    @JavascriptInterface public void requestPerms() { ui.post(new Runnable() { public void run() { a.requestPerms(); } }); }
    @JavascriptInterface public void openAppSettings() { ui.post(new Runnable() { public void run() { a.openAppSettings(); } }); }
    @JavascriptInterface public void requestIgnoreBattery() { ui.post(new Runnable() { public void run() { a.requestIgnoreBattery(); } }); }
    @JavascriptInterface public void batteryHintDone() { prefs.setBatteryHintDone(); }
    @JavascriptInterface public void introDone() { prefs.setIntroDone(); }

    @JavascriptInterface public void start() {
        if (MonitorState.running || waitingStart) return;
        if (!a.hasMic()) { startError = "没有麦克风权限，没法监测。请先允许麦克风。"; return; }
        stopMicTest();
        MonitorState.error = null;
        MonitorState.stopReason = null;
        interruptedMsg = null;
        startError = null;
        waitingSince = System.currentTimeMillis();
        waitingStart = true;
        ui.post(new Runnable() { public void run() {
            try {
                a.startForegroundService(new Intent(a, MonitorService.class).setAction(MonitorService.ACTION_START));
            } catch (Throwable t) {
                Diag.log(a, "startForegroundService 失败：" + t);
                waitingStart = false;
                startError = "系统拒绝启动录音服务：" + t.getMessage();
            }
        } });
    }

    @JavascriptInterface public void stop() {
        ui.post(new Runnable() { public void run() {
            try { a.startService(new Intent(a, MonitorService.class).setAction(MonitorService.ACTION_STOP)); } catch (Throwable t) { Diag.log(a, "stop 失败：" + t); }
        } });
    }

    // ================================================================= 外观
    @JavascriptInterface public void setTheme(final String t) { ui.post(new Runnable() { public void run() { a.applyTheme(t); } }); }

    // ================================================================= 录音回放
    @JavascriptInterface public String playClip(String idStr) {
        try {
            final long id = Long.parseLong(idStr);
            String rel = db.clipPath(id);
            if (rel == null) return "gone";
            final File f = new File(a.getFilesDir(), rel);
            if (!f.exists()) return "gone";
            return onUi(new Callable<String>() { public String call() {
                releasePlayer();
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    mp.setDataSource(f.getAbsolutePath());
                    mp.prepare();
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer m) {
                        releasePlayer();
                        a.fire("clipDone", "");
                    } });
                    mp.start();
                    player = mp; playingId = id;
                    return "ok";
                } catch (Throwable t) {
                    Diag.log(a, "播放失败：" + t);
                    releasePlayer();
                    return "error";
                }
            } }, "error");
        } catch (Throwable t) { return fail("playClip", t); }
    }

    @JavascriptInterface public void stopClip() { ui.post(new Runnable() { public void run() { releasePlayer(); } }); }

    /** 主线程调用（Activity.onPause 也会用）。 */
    void releasePlayer() {
        MediaPlayer mp = player;
        player = null; playingId = 0;
        if (mp != null) { try { mp.stop(); } catch (Throwable ignore) { } try { mp.release(); } catch (Throwable ignore) { } }
    }

    private <T> T onUi(final Callable<T> c, T def) {
        if (Looper.myLooper() == Looper.getMainLooper()) { try { return c.call(); } catch (Throwable t) { return def; } }
        final Object[] box = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() { public void run() { try { box[0] = c.call(); } catch (Throwable ignore) { } latch.countDown(); } });
        try { if (!latch.await(3, TimeUnit.SECONDS)) return def; } catch (InterruptedException e) { return def; }
        @SuppressWarnings("unchecked") T r = (T) box[0];
        return r == null ? def : r;
    }

    // ================================================================= 麦克风自检（新手引导最后一步）
    @JavascriptInterface public void micTest(String on) {
        if ("1".equals(on)) startMicTest(); else stopMicTest();
    }

    @JavascriptInterface public String micLevel() { return String.valueOf(micLevel); }

    private synchronized void startMicTest() {
        if (micRun || MonitorState.running || !a.hasMic()) return;
        micRun = true; micLevel = 0;
        Thread t = new Thread(new Runnable() { public void run() { micLoop(); } }, "yehang-mictest");
        micThread = t;
        t.start();
    }

    synchronized void stopMicTest() {
        micRun = false;
        Thread t = micThread;
        micThread = null;
        if (t != null) { try { t.join(500); } catch (InterruptedException ignore) { } }
        micLevel = 0;
    }

    private void micLoop() {
        AudioRecord ar = null;
        try {
            int sr = 16000;
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int[] sources = { MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC };
            for (int src : sources) {
                try {
                    AudioRecord r = new AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr / 2));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) { ar = r; break; }
                    r.release();
                } catch (Throwable ignore) { }
            }
            if (ar == null) return;
            ar.startRecording();
            short[] buf = new short[sr / 10];
            while (micRun) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) { try { Thread.sleep(20); } catch (InterruptedException e) { break; } continue; }
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n) / 32768.0;
                double db = 20 * Math.log10(Math.max(rms, 1e-6));       // dBFS
                micLevel = (float) Math.max(0, Math.min(1, (db + 50) / 30.0));   // -50 dBFS → 0，-20 dBFS → 1
            }
        } catch (Throwable t) {
            Diag.log(a, "麦克风自检失败：" + t);
        } finally {
            if (ar != null) { try { ar.stop(); } catch (Throwable ignore) { } try { ar.release(); } catch (Throwable ignore) { } }
        }
    }

    void onPause() { stopMicTest(); releasePlayer(); }

    // ================================================================= 小工具
    private String fail(String what, Throwable t) {
        Diag.log(a, "Bridge." + what + " 失败：" + t);
        return "null";
    }
}
