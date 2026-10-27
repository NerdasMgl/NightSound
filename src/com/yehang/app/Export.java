package com.yehang.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.yehang.app.core.Fmt;
import com.yehang.app.core.Rec;
import com.yehang.app.core.Summary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

/** 把全部记录导出成两个 CSV（给想自己分析数据的人用，不是主要功能）。 */
public final class Export {
    private Export() { }

    private static String q(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    /** 成功返回保存位置，失败抛异常。 */
    public static String csv(Context c, Db db, Prefs prefs) throws Exception {
        StringBuilder nights = new StringBuilder("﻿日期,开始,结束,在床分钟,睡眠分钟,入睡用时分钟,醒来次数,深睡分钟,鼾声占比%,鼾声次数,最响dB,疑似暂停次数,暂停指数每小时,梦话,磨牙,咳嗽,其他,评分,标签,是否中断\n");
        StringBuilder evs = new StringBuilder("﻿夜晚日期,时间,类型,持续秒,峰值dB,平均dB,置信度,暂停安静秒数\n");
        List<Rec.Night> ns = db.finishedNights(1000);
        for (Rec.Night n : ns) {
            Summary s = Data.summary(db, prefs, n);
            String d = Fmt.day(n.endMs);
            nights.append(q(d)).append(',').append(Fmt.full(n.startMs)).append(',').append(Fmt.full(s.endMs)).append(',')
                    .append(s.inBedMin).append(',').append(s.sleepMin).append(',').append(s.latencyMin).append(',').append(s.awakenings).append(',').append(s.deepMin).append(',')
                    .append(Fmt.f1(s.snorePct)).append(',').append(s.snoreEvents).append(',').append(Math.round(s.snoreMaxDb)).append(',')
                    .append(s.apneaCount).append(',').append(Fmt.f1(s.apneaIdx)).append(',').append(s.talkCount).append(',').append(s.grindCount).append(',').append(s.coughCount).append(',').append(s.otherCount).append(',')
                    .append(s.score).append(',').append(q(n.tags)).append(',').append((n.flags & 1) != 0 ? "是" : "否").append('\n');
            for (Rec.Event e : db.events(n.id)) {
                evs.append(q(d)).append(',').append(Fmt.full(e.startMs)).append(',').append(Fmt.typeName(e.type)).append(',')
                        .append(Fmt.f1((e.endMs - e.startMs) / 1000f)).append(',').append(Math.round(e.peakDb)).append(',').append(Math.round(e.meanDb)).append(',')
                        .append(Fmt.f1(e.conf)).append(',').append(e.type == 2 ? Math.round(e.extra) : 0).append('\n');
            }
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date());
        String where = write(c, "nightsound-nights-" + stamp + ".csv", nights.toString());
        write(c, "nightsound-events-" + stamp + ".csv", evs.toString());
        return where;
    }

    private static String write(Context c, String name, String content) throws Exception {
        byte[] data = content.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/夜莺");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new Exception("无法创建文件");
            OutputStream o = c.getContentResolver().openOutputStream(u);
            try { o.write(data); } finally { o.close(); }
            return "下载/夜莺";
        }
        File dir = c.getExternalFilesDir(null);
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
        return f.getParent();
    }
}
