package com.yehang.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 本地诊断日志与崩溃记录。不联网；只有用户点“分享诊断信息”时才会发出。 */
public final class Diag {
    private Diag() { }

    private static File logFile(Context c) { return new File(c.getFilesDir(), "yehang.log"); }

    public static synchronized void log(Context c, String msg) {
        try {
            File f = logFile(c);
            if (f.length() > 96 * 1024) {
                File old = new File(c.getFilesDir(), "yehang.log.1");
                old.delete(); f.renameTo(old);
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(f, true);
            try { o.write((ts + " " + msg + "\n").getBytes("UTF-8")); } finally { o.close(); }
        } catch (Throwable ignore) { }
    }

    public static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    log(app, "CRASH in " + t.getName() + ": " + sw);
                } catch (Throwable ignore) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    public static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("夜莺 ").append(BuildInfo.VERSION).append("\n");
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("麦克风模式：").append(MonitorState.audioSource).append("\n\n");
        try {
            File f = logFile(c);
            if (f.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] b = new byte[(int) f.length()];
                int n = in.read(b); in.close();
                String s = new String(b, 0, Math.max(n, 0), "UTF-8");
                if (s.length() > 12000) s = s.substring(s.length() - 12000);
                sb.append(s);
            } else sb.append("（没有日志）");
        } catch (Throwable e) { sb.append("读取日志失败：").append(e); }
        return sb.toString();
    }
}
