package com.yehang.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.yehang.app.core.Analyzer;
import com.yehang.app.core.Rec;
import com.yehang.app.core.Wav;
import com.yehang.app.core.YamNet;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 整晚监测：前台服务 + 麦克风录音 + 声音事件分析 + 体动/光线/屏幕记录。 */
public class MonitorService extends Service implements Analyzer.Listener, SensorEventListener {
    public static final String ACTION_START = "com.yehang.app.START";
    public static final String ACTION_STOP = "com.yehang.app.STOP";
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;
    private static final int RING_SEC = 120;
    private static final long MAX_NIGHT_MS = 15L * 3600 * 1000;

    private Prefs prefs;
    private Db db;
    private volatile Analyzer an;
    private PowerManager.WakeLock wake;
    private PowerManager pm;
    private SensorManager sm;
    private Thread audioThread;
    private volatile boolean stopping;
    private ExecutorService io;
    private long nightId, startMs;
    private volatile long driftMs;

    // 环形缓冲：最近 RING_SEC 秒的原始音频，用来在事件被确认后回头剪出录音
    private short[] ring;
    private volatile long fed;            // 已写入环形缓冲的总样本数（录音线程写，分析线程读）
    private int flushedBefore;            // 早于此分钟的统计已经写入数据库（持有 lock 访问）

    // 分钟统计
    private final Object lock = new Object();
    private int curMin = -1;
    private double actSum; int actN; float actMax; long lastActSample = -1; float lastAx, lastAy, lastAz; boolean haveLast;
    private double noiseSum; int noiseN;
    private int screenSec;
    private float luxSum; int luxN; float lastLux = -1;
    private final java.util.HashMap<Integer, Rec.Epoch> pending = new java.util.HashMap<Integer, Rec.Epoch>();
    private int secCount;
    private long lastBeatWall;
    private int snoreClips, apneaClips, otherClips;
    private long lastSnoreClipMs; private float bestSnoreClipPeak;
    private long silentSince;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            if (MonitorState.running) finishFromThread(null); else { stopForeground(true); stopSelf(); }
            return START_NOT_STICKY;
        }
        if (MonitorState.running) return START_NOT_STICKY;
        startMonitoring();
        return START_NOT_STICKY;
    }

    private void startMonitoring() {
        ending = false;
        prefs = new Prefs(this);
        db = Db.get(this);
        createChannel();
        Notification n = buildNotification("正在准备…", 0);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF_ID, n);
        } catch (RuntimeException e) {
            Diag.log(this, "startForeground 失败：" + e);
            MonitorState.error = "系统不允许在后台启动录音服务，请在应用打开的状态下点“开始监测”。";
            stopSelf();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            MonitorState.error = "没有麦克风权限。";
            Diag.log(this, "没有 RECORD_AUDIO 权限");
            stopForeground(true); stopSelf();
            return;
        }

        io = Executors.newSingleThreadExecutor();
        startMs = System.currentTimeMillis();
        db.closeOrphans();
        nightId = db.startNight(startMs, prefs.sensitivity());
        db.setTags(nightId, prefs.pendingTags());
        prefs.setPendingTags("");          // 今晚的习惯只属于这一晚，明天重新选
        prefs.setActiveNight(nightId);
        Diag.log(this, "开始监测 night=" + nightId + " sens=" + prefs.sensitivity());

        MonitorState.nightId = nightId; MonitorState.startMs = startMs;
        MonitorState.snoreCount = 0; MonitorState.apneaCount = 0; MonitorState.soundCount = 0;
        MonitorState.error = null; MonitorState.stopReason = null; MonitorState.micSilent = false; MonitorState.accelOk = false;
        MonitorState.level = 0; MonitorState.floor = 0; MonitorState.lastSnoreMs = 0;
        MonitorState.running = true;

        synchronized (lock) {
            pending.clear(); curMin = -1; actSum = 0; actN = 0; actMax = 0; haveLast = false;
            noiseSum = 0; noiseN = 0; screenSec = 0; luxSum = 0; luxN = 0; lastLux = -1;
        }
        snoreClips = 0; apneaClips = 0; otherClips = 0; lastSnoreClipMs = 0; bestSnoreClipPeak = 0;
        ring = new short[Analyzer.SR * RING_SEC];
        fed = 0; driftMs = 0; secCount = 0; lastBeatWall = startMs; silentSince = 0;
        stopping = false;
        flushedBefore = 0;

        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yehang:monitor");
        wake.setReferenceCounted(false);
        wake.acquire(MAX_NIGHT_MS);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL);
        if (light != null) sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        Diag.log(this, "传感器：加速度=" + (acc != null) + " 光线=" + (light != null));

        audioThread = new Thread(new Runnable() { public void run() { audioLoop(); } }, "yehang-audio");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    // ---------------------------------------------------------------- 录音循环
    private AudioRecord openRecord() {
        int minBuf = AudioRecord.getMinBufferSize(Analyzer.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, Analyzer.SR * 2 * 2);
        int[] sources = { MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.UNPROCESSED };
        String[] names = { "语音识别（无自动增益）", "标准麦克风", "原始" };
        for (int i = 0; i < sources.length; i++) {
            try {
                AudioRecord r = new AudioRecord(sources[i], Analyzer.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        MonitorState.audioSource = names[i];
                        Diag.log(this, "录音源：" + names[i] + " buf=" + bufBytes);
                        return r;
                    }
                }
                r.release();
            } catch (Throwable t) {
                Diag.log(this, "打开录音源失败 " + names[i] + "：" + t);
            }
        }
        return null;
    }

    private void audioLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignore) { }
        // 识别模型：失败时退回手写规则，并把原因记进诊断日志
        YamNet model = null;
        long t0 = System.currentTimeMillis();
        try {
            model = new YamNet(getAssets().open("yamnet.bin"));
            MonitorState.modelOn = true;
            Diag.log(this, "识别模型 YAMNet 已加载，用时 " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            MonitorState.modelOn = false;
            Diag.log(this, "识别模型加载失败，改用基础规则：" + t);
        }
        final Analyzer analyzer = new Analyzer(this, prefs.sensitivity(), model, model != null);
        analyzer.setGrindEnabled(prefs.grind());
        an = analyzer;
        AudioRecord ar = openRecord();
        if (ar == null) {
            MonitorState.error = "无法打开麦克风（可能被其他应用占用）。";
            Diag.log(this, "无法打开麦克风");
            finishFromThread("无法打开麦克风");
            return;
        }
        short[] buf = new short[Analyzer.SR / 10];
        int fails = 0, reopen = 0;
        int peakInWindow = 0;
        long windowStart = fed;
        while (!stopping) {
            int n = ar.read(buf, 0, buf.length);
            if (n <= 0) {
                fails++;
                if (fails > 20) {
                    ar.release();
                    if (++reopen > 3) { MonitorState.error = "麦克风中断，无法恢复。"; Diag.log(this, "麦克风读取失败，放弃"); break; }
                    Diag.log(this, "麦克风读取失败，重新打开（第 " + reopen + " 次）");
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    ar = openRecord();
                    if (ar == null) { MonitorState.error = "麦克风中断，无法恢复。"; break; }
                    fails = 0;
                } else {
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
                continue;
            }
            fails = 0;
            // 写入环形缓冲
            int cap = ring.length;
            int p = (int) (fed % cap);
            int first = Math.min(n, cap - p);
            System.arraycopy(buf, 0, ring, p, first);
            if (first < n) System.arraycopy(buf, first, ring, 0, n - first);
            fed += n;
            for (int i = 0; i < n; i++) { int a = buf[i] < 0 ? -buf[i] : buf[i]; if (a > peakInWindow) peakInWindow = a; }
            // 完全静音检测：连续 60 秒样本峰值为 0
            if (fed - windowStart >= Analyzer.SR * 60L) {
                boolean silent = peakInWindow == 0;
                if (silent && !MonitorState.micSilent) Diag.log(this, "麦克风 60 秒内没有任何信号");
                MonitorState.micSilent = silent;
                peakInWindow = 0; windowStart = fed;
            }
            analyzer.feed(buf, n);
        }
        try { ar.stop(); } catch (Throwable ignore) { }
        ar.release();
        if (!stopping) finishFromThread(MonitorState.error);
    }

    private void finishFromThread(final String reason) {
        // 从录音线程触发结束
        new Thread(new Runnable() { public void run() { stopMonitoring(reason); } }).start();
    }

    // ---------------------------------------------------------------- Analyzer 回调（在录音线程）
    private long wallOf(long sample) { return startMs + sample * 1000L / Analyzer.SR + driftMs; }

    @Override public void onSecond(float levelDb, float floorDb) {
        MonitorState.level = levelDb; MonitorState.floor = floorDb;
        long now = System.currentTimeMillis();
        long nominal = startMs + fed * 1000L / Analyzer.SR;
        driftMs = now - nominal;
        if (Math.abs(driftMs) < 1500) driftMs = 0;
        secCount++;
        int min = (int) ((now - startMs) / 60000);
        synchronized (lock) {
            if (curMin < 0) curMin = min;
            noiseSum += floorDb; noiseN++;
            if (pm != null && pm.isInteractive() && !MonitorState.bedsideKeepOn) screenSec++;
            if (lastLux >= 0) { luxSum += lastLux; luxN++; }
            if (min != curMin) closeMinute(curMin);
            curMin = min;
        }
        if (now - lastBeatWall >= 30000) {
            lastBeatWall = now;
            final long t = now;
            runIo(new Runnable() { public void run() { db.beat(nightId, t); } });
            checkBatteryAndLimit(now);
        }
        if (secCount % 60 == 0) updateNotification();
    }

    /** 持有 lock 调用。把一分钟的汇总放进待写队列，延迟一分钟再落盘（等晚到的事件）。 */
    private void closeMinute(int m) {
        Rec.Epoch e = pending.get(m);
        if (e == null) { e = new Rec.Epoch(); e.t = startMs + m * 60000L; pending.put(m, e); }
        e.act = actN > 0 ? (float) (actSum / actN) : -1f;
        e.actMax = actN > 0 ? actMax : -1f;
        e.noise = noiseN > 0 ? (float) (noiseSum / noiseN) : 0f;
        e.lux = luxN > 0 ? luxSum / luxN : -1f;
        e.screenSec = screenSec;
        actSum = 0; actN = 0; actMax = 0; noiseSum = 0; noiseN = 0; screenSec = 0; luxSum = 0; luxN = 0;
        // 落盘 m-1 及更早的
        java.util.ArrayList<Integer> done = new java.util.ArrayList<Integer>();
        for (Integer k : pending.keySet()) if (k < m) done.add(k);
        if (m > flushedBefore) flushedBefore = m;
        for (Integer k : done) {
            final Rec.Epoch ep = pending.remove(k);
            if (ep.actMax == 0f && ep.act == 0f && ep.noise == 0f) continue;
            runIo(new Runnable() { public void run() { db.putEpoch(nightId, ep); } });
        }
    }

    private Rec.Epoch epochFor(long wallMs) {
        int m = (int) ((wallMs - startMs) / 60000);
        if (m < 0) m = 0;
        Rec.Epoch e = pending.get(m);
        if (e == null) { e = new Rec.Epoch(); e.t = startMs + m * 60000L; e.act = -1; e.actMax = -1; e.lux = -1; pending.put(m, e); }
        return e;
    }

    @Override public void onEvent(Analyzer.Ev ev) {
        final Rec.Event r = new Rec.Event();
        r.nightId = nightId; r.type = ev.type;
        r.startMs = wallOf(ev.startSample); r.endMs = wallOf(ev.endSample);
        r.peakDb = ev.peakDb; r.meanDb = ev.meanDb; r.conf = ev.conf; r.extra = ev.extra;

        final int addSec = ev.type == Analyzer.T_SNORE ? Math.max(1, (int) ((r.endMs - r.startMs) / 1000)) : 0;
        final int addEvt = (ev.type != Analyzer.T_SNORE && ev.type != Analyzer.T_APNEA) ? 1 : 0;
        synchronized (lock) {
            int m = (int) ((r.startMs - startMs) / 60000);
            if (m < flushedBefore) {
                // 这一分钟的统计已经写进数据库了（模型分析比较慢时会发生）：直接累加到那一行
                final long minT = startMs + Math.max(0, m) * 60000L;
                final float pk = ev.type == Analyzer.T_SNORE ? ev.peakDb : 0f;
                if (addSec > 0 || addEvt > 0) runIo(new Runnable() { public void run() { db.addToEpoch(nightId, minT, addSec, pk, addEvt); } });
            } else {
                Rec.Epoch e = epochFor(r.startMs);
                if (ev.type == Analyzer.T_SNORE) { e.snoreSec += addSec; e.snoreDb = Math.max(e.snoreDb, ev.peakDb); }
                else e.evtCnt += addEvt;
            }
        }
        if (ev.type == Analyzer.T_SNORE) { MonitorState.snoreCount++; MonitorState.lastSnoreMs = r.endMs; }
        else if (ev.type == Analyzer.T_APNEA) MonitorState.apneaCount++;
        else MonitorState.soundCount++;

        short[] clip = null;
        if (prefs.saveClips()) clip = maybeClip(ev, r);
        final short[] clipData = clip;
        final String name = clipData == null ? null : "clips/" + nightId + "_" + r.startMs + "_" + ev.type + ".wav";
        r.clip = name;
        final Context ctx = this;
        runIo(new Runnable() {
            public void run() {
                if (clipData != null) {
                    try {
                        File dir = new File(getFilesDir(), "clips"); dir.mkdirs();
                        Wav.write(new File(getFilesDir(), name), clipData, 0, clipData.length);
                    } catch (Throwable t) { Diag.log(ctx, "保存录音失败：" + t); r.clip = null; }
                }
                db.putEvent(nightId, r);
            }
        });
    }

    /** 决定是否保存这个事件的录音，并从环形缓冲里剪出来。返回 null 表示不保存。 */
    private short[] maybeClip(Analyzer.Ev ev, Rec.Event r) {
        int pre, post, maxSec;
        switch (ev.type) {
            case Analyzer.T_APNEA:
                if (apneaClips >= 20) return null;
                pre = 8; post = 4; maxSec = 30; break;
            case Analyzer.T_SNORE: {
                if (snoreClips >= 24) return null;
                boolean spaced = r.startMs - lastSnoreClipMs >= 10 * 60000L;
                boolean louder = ev.peakDb >= bestSnoreClipPeak + 5f;
                if (!spaced && !louder) return null;
                pre = 1; post = 1; maxSec = 8; break;
            }
            default:
                if (otherClips >= 30) return null;
                pre = 1; post = 1; maxSec = 8;
        }
        long s = ev.startSample - (long) pre * Analyzer.SR;
        long e = ev.endSample + (long) post * Analyzer.SR;
        if (e - s > (long) maxSec * Analyzer.SR) {
            // 暂停事件：保留结尾（呼吸恢复）并截去前面的部分
            s = e - (long) maxSec * Analyzer.SR;
        }
        long fedNow = fed;
        long oldest = Math.max(0, fedNow - ring.length + 3L * Analyzer.SR);   // 留 3 秒余量，避免和录音线程的写入撞车
        if (s < oldest) s = oldest;
        if (e > fedNow) e = fedNow;
        int len = (int) (e - s);
        if (len < Analyzer.SR / 2) return null;
        short[] out = new short[len];
        int cap = ring.length;
        for (int i = 0; i < len; i++) out[i] = ring[(int) ((s + i) % cap)];
        if (ev.type == Analyzer.T_APNEA) apneaClips++;
        else if (ev.type == Analyzer.T_SNORE) { snoreClips++; lastSnoreClipMs = r.startMs; bestSnoreClipPeak = Math.max(bestSnoreClipPeak, ev.peakDb); }
        else otherClips++;
        return out;
    }

    // ---------------------------------------------------------------- 传感器
    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            synchronized (lock) {
                if (haveLast) {
                    float dx = x - lastAx, dy = y - lastAy, dz = z - lastAz;
                    float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    actSum += d; actN++;
                    if (d > actMax) actMax = d;
                    MonitorState.accelOk = true;
                }
                lastAx = x; lastAy = y; lastAz = z; haveLast = true;
            }
        } else if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            lastLux = e.values[0];
            MonitorState.lux = lastLux;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    // ---------------------------------------------------------------- 结束
    private void checkBatteryAndLimit(long now) {
        if (now - startMs > MAX_NIGHT_MS - 60000) { finishFromThread("已连续监测超过 14 小时，自动结束。"); return; }
        try {
            Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                if (!charging && level >= 0 && level * 100 / scale <= 4) {
                    MonitorState.stopReason = "电量过低（不足 5%），已自动结束监测并保存。";
                    finishFromThread(MonitorState.stopReason);
                }
            }
        } catch (Throwable ignore) { }
    }

    private boolean ending;

    private void stopMonitoring(String reason) {
        synchronized (this) { if (ending) return; ending = true; }
        stopping = true;
        if (reason != null && MonitorState.stopReason == null) MonitorState.stopReason = reason;
        try {
            if (audioThread != null && audioThread != Thread.currentThread()) audioThread.join(3000);
        } catch (InterruptedException ignore) { }
        try { if (sm != null) sm.unregisterListener(this); } catch (Throwable ignore) { }
        Analyzer a0 = an;
        if (a0 != null) {
            try { a0.flush(); } catch (Throwable t) { Diag.log(this, "flush 失败：" + t); }
            try { a0.release(); } catch (Throwable ignore) { }
        }
        long end = System.currentTimeMillis();
        synchronized (lock) {
            if (curMin >= 0) closeMinute(curMin);
            // 剩余待写的全部落盘
            for (final Rec.Epoch ep : pending.values()) {
                if (ep.actMax == 0f && ep.act == 0f && ep.noise == 0f) continue;
                runIo(new Runnable() { public void run() { db.putEpoch(nightId, ep); } });
            }
            pending.clear();
        }
        final long endF = end;
        final long id = nightId;
        runIo(new Runnable() { public void run() {
            db.endNight(id, endF, MonitorState.modelOn ? 0 : 2);
            if (prefs.saveClips()) db.purgeClips(prefs.clipDays());
        } });
        if (io != null) {
            io.shutdown();
            try { io.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        }
        prefs.setActiveNight(0);
        Diag.log(this, "结束监测 night=" + id + " 时长=" + (end - startMs) / 60000 + " 分钟 鼾声=" + MonitorState.snoreCount + " 暂停=" + MonitorState.apneaCount
                + (MonitorState.stopReason != null ? " 原因=" + MonitorState.stopReason : ""));
        if (wake != null && wake.isHeld()) wake.release();
        MonitorState.finishedNight = id;
        MonitorState.running = false;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        // 被系统销毁但没有走正常结束：尽量释放资源，夜的记录留给下次启动时修复
        stopping = true;
        try { if (sm != null) sm.unregisterListener(this); } catch (Throwable ignore) { }
        if (wake != null && wake.isHeld()) wake.release();
        if (MonitorState.running && !ending) {
            Diag.log(this, "服务在监测中被销毁（可能被系统清理）");
            MonitorState.running = false;
        }
        super.onDestroy();
    }

    private void runIo(Runnable r) {
        ExecutorService x = io;
        if (x == null) return;
        try { x.execute(r); } catch (Throwable ignore) { r.run(); }
    }

    // ---------------------------------------------------------------- 通知
    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "夜间监测", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("整晚监测时显示，不会发出声音");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text, long elapsedMin) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("夜莺正在监测睡眠")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "结束监测", sp).build());
        return b.build();
    }

    private void updateNotification() {
        long mins = (System.currentTimeMillis() - startMs) / 60000;
        String t = "已监测 " + (mins / 60) + " 小时 " + (mins % 60) + " 分 · 鼾声 " + MonitorState.snoreCount + " 次";
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification(t, mins));
        } catch (Throwable ignore) { }
    }
}
