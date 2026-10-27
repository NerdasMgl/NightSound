package com.yehang.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.yehang.app.core.Rec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 本机 SQLite。夜、分钟记录、事件、打卡。 */
public final class Db extends SQLiteOpenHelper {
    private static Db inst;
    public static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private Db(Context c) { super(c, "yehang.db", null, 1); ctx = c; }

    @Override public void onConfigure(SQLiteDatabase d) {
        super.onConfigure(d);
        d.enableWriteAheadLogging();     // 服务在后台写、界面在前台读，互不阻塞
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE nights(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER, end_ms INTEGER, last_beat INTEGER, flags INTEGER, sens INTEGER, tags TEXT)");
        d.execSQL("CREATE TABLE epochs(night_id INTEGER, t INTEGER, act REAL, act_max REAL, noise REAL, snore_db REAL, snore_sec INTEGER, evt_cnt INTEGER, lux REAL, screen_sec INTEGER, PRIMARY KEY(night_id, t))");
        d.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, night_id INTEGER, type INTEGER, start_ms INTEGER, end_ms INTEGER, peak REAL, mean REAL, conf REAL, extra REAL, clip TEXT)");
        d.execSQL("CREATE INDEX ev_night ON events(night_id, type)");
        d.execSQL("CREATE TABLE checkins(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind INTEGER, val INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int o, int n) { }

    // ---------------- 夜 ----------------
    public long startNight(long startMs, int sens) {
        ContentValues v = new ContentValues();
        v.put("start_ms", startMs); v.put("end_ms", 0); v.put("last_beat", startMs); v.put("flags", 0); v.put("sens", sens); v.put("tags", "");
        return getWritableDatabase().insert("nights", null, v);
    }

    public void beat(long id, long ms) {
        ContentValues v = new ContentValues(); v.put("last_beat", ms);
        getWritableDatabase().update("nights", v, "id=?", new String[] { String.valueOf(id) });
    }

    public void endNight(long id, long endMs, int flags) {
        ContentValues v = new ContentValues(); v.put("end_ms", endMs); v.put("last_beat", endMs); v.put("flags", flags);
        getWritableDatabase().update("nights", v, "id=?", new String[] { String.valueOf(id) });
    }

    /** 上次被系统杀掉、没有正常结束的夜：用最后一次心跳作为结束时间，并标记“被中断”。返回被处理的夜的 id。 */
    public ArrayList<Long> closeOrphans() {
        SQLiteDatabase d = getWritableDatabase();
        long stale = System.currentTimeMillis() - 90000L;     // 心跳 30 秒一次，90 秒没动静才算孤儿
        Cursor c = d.rawQuery("SELECT id,start_ms,last_beat FROM nights WHERE end_ms=0 AND last_beat<?", new String[] { String.valueOf(stale) });
        ArrayList<Long> out = new ArrayList<Long>();
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0), st = c.getLong(1), lb = c.getLong(2);
                if (lb - st < 5 * 60000L) { deleteNight(id); }
                else { endNight(id, lb, 1); out.add(id); }
            }
        } finally { c.close(); }
        return out;
    }

    public void setTags(long id, String tags) {
        ContentValues v = new ContentValues(); v.put("tags", tags);
        getWritableDatabase().update("nights", v, "id=?", new String[] { String.valueOf(id) });
    }

    private Rec.Night readNight(Cursor c) {
        Rec.Night n = new Rec.Night();
        n.id = c.getLong(0); n.startMs = c.getLong(1); n.endMs = c.getLong(2); n.lastBeat = c.getLong(3);
        n.flags = c.getInt(4); n.sens = c.getInt(5); n.tags = c.getString(6) == null ? "" : c.getString(6);
        return n;
    }

    public Rec.Night night(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id,start_ms,end_ms,last_beat,flags,sens,tags FROM nights WHERE id=?", new String[] { String.valueOf(id) });
        try { return c.moveToFirst() ? readNight(c) : null; } finally { c.close(); }
    }

    /** 已结束的夜，新的在前。 */
    public List<Rec.Night> finishedNights(int limit) {
        ArrayList<Rec.Night> out = new ArrayList<Rec.Night>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,start_ms,end_ms,last_beat,flags,sens,tags FROM nights WHERE end_ms>0 ORDER BY start_ms DESC LIMIT " + limit, null);
        try { while (c.moveToNext()) out.add(readNight(c)); } finally { c.close(); }
        return out;
    }

    public void deleteNight(long id) {
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT clip FROM events WHERE night_id=? AND clip IS NOT NULL", new String[] { String.valueOf(id) });
        try { while (c.moveToNext()) new File(ctx.getFilesDir(), c.getString(0)).delete(); } finally { c.close(); }
        String[] a = { String.valueOf(id) };
        d.delete("events", "night_id=?", a);
        d.delete("epochs", "night_id=?", a);
        d.delete("nights", "id=?", a);
    }

    public void deleteAll() {
        for (Rec.Night n : finishedNights(100000)) deleteNight(n.id);
        SQLiteDatabase d = getWritableDatabase();
        d.delete("events", null, null); d.delete("epochs", null, null); d.delete("nights", null, null); d.delete("checkins", null, null);
        File[] fs = new File(ctx.getFilesDir(), "clips").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    // ---------------- 分钟记录 ----------------
    public void putEpoch(long nightId, Rec.Epoch e) {
        ContentValues v = new ContentValues();
        v.put("night_id", nightId); v.put("t", e.t); v.put("act", e.act); v.put("act_max", e.actMax); v.put("noise", e.noise);
        v.put("snore_db", e.snoreDb); v.put("snore_sec", e.snoreSec); v.put("evt_cnt", e.evtCnt); v.put("lux", e.lux); v.put("screen_sec", e.screenSec);
        getWritableDatabase().insertWithOnConflict("epochs", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 给已写入的一分钟补加晚到的鼾声/声音事件；那一行不存在就新建一行（没有体动数据）。 */
    public void addToEpoch(long nightId, long t, int snoreSec, float snoreDb, int evtCnt) {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("UPDATE epochs SET snore_sec=snore_sec+?, snore_db=MAX(snore_db,?), evt_cnt=evt_cnt+? WHERE night_id=? AND t=?",
                new Object[] { snoreSec, snoreDb, evtCnt, nightId, t });
        Cursor c = d.rawQuery("SELECT changes()", null);
        int ch = 0;
        try { if (c.moveToFirst()) ch = c.getInt(0); } finally { c.close(); }
        if (ch == 0) {
            Rec.Epoch e = new Rec.Epoch();
            e.t = t; e.act = -1; e.actMax = -1; e.lux = -1; e.snoreSec = snoreSec; e.snoreDb = snoreDb; e.evtCnt = evtCnt;
            putEpoch(nightId, e);
        }
    }

    public List<Rec.Epoch> epochs(long nightId) {
        ArrayList<Rec.Epoch> out = new ArrayList<Rec.Epoch>();
        Cursor c = getReadableDatabase().rawQuery("SELECT t,act,act_max,noise,snore_db,snore_sec,evt_cnt,lux,screen_sec FROM epochs WHERE night_id=? ORDER BY t", new String[] { String.valueOf(nightId) });
        try {
            while (c.moveToNext()) {
                Rec.Epoch e = new Rec.Epoch();
                e.t = c.getLong(0); e.act = c.getFloat(1); e.actMax = c.getFloat(2); e.noise = c.getFloat(3); e.snoreDb = c.getFloat(4);
                e.snoreSec = c.getInt(5); e.evtCnt = c.getInt(6); e.lux = c.getFloat(7); e.screenSec = c.getInt(8);
                out.add(e);
            }
        } finally { c.close(); }
        return out;
    }

    // ---------------- 事件 ----------------
    public long putEvent(long nightId, Rec.Event e) {
        ContentValues v = new ContentValues();
        v.put("night_id", nightId); v.put("type", e.type); v.put("start_ms", e.startMs); v.put("end_ms", e.endMs);
        v.put("peak", e.peakDb); v.put("mean", e.meanDb); v.put("conf", e.conf); v.put("extra", e.extra); v.put("clip", e.clip);
        return getWritableDatabase().insert("events", null, v);
    }

    public List<Rec.Event> events(long nightId) { return events(nightId, -1); }

    public List<Rec.Event> events(long nightId, int type) {
        ArrayList<Rec.Event> out = new ArrayList<Rec.Event>();
        String sql = "SELECT id,night_id,type,start_ms,end_ms,peak,mean,conf,extra,clip FROM events WHERE night_id=?" + (type > 0 ? " AND type=" + type : "") + " ORDER BY start_ms";
        Cursor c = getReadableDatabase().rawQuery(sql, new String[] { String.valueOf(nightId) });
        try {
            while (c.moveToNext()) {
                Rec.Event e = new Rec.Event();
                e.id = c.getLong(0); e.nightId = c.getLong(1); e.type = c.getInt(2); e.startMs = c.getLong(3); e.endMs = c.getLong(4);
                e.peakDb = c.getFloat(5); e.meanDb = c.getFloat(6); e.conf = c.getFloat(7); e.extra = c.getFloat(8); e.clip = c.getString(9);
                out.add(e);
            }
        } finally { c.close(); }
        return out;
    }

    /** 某条事件的录音文件相对路径（没有或已被清理则为 null）。 */
    public String clipPath(long eventId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT clip FROM events WHERE id=?", new String[] { String.valueOf(eventId) });
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    /** 清除所有录音文件，事件记录保留。 */
    public void clearClips() {
        File[] fs = new File(ctx.getFilesDir(), "clips").listFiles();
        if (fs != null) for (File f : fs) f.delete();
        getWritableDatabase().execSQL("UPDATE events SET clip=NULL");
    }

    public long clipBytes() {
        File[] fs = new File(ctx.getFilesDir(), "clips").listFiles();
        long n = 0;
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    public void deleteEvent(long id) {
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT clip FROM events WHERE id=?", new String[] { String.valueOf(id) });
        try { if (c.moveToFirst() && c.getString(0) != null) new File(ctx.getFilesDir(), c.getString(0)).delete(); } finally { c.close(); }
        d.delete("events", "id=?", new String[] { String.valueOf(id) });
    }

    /** 删除超过保留天数的录音文件（只清文件，事件记录保留）。 */
    public void purgeClips(int days) {
        long cutoff = System.currentTimeMillis() - days * 86400000L;
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT id,clip FROM events WHERE clip IS NOT NULL AND start_ms<?", new String[] { String.valueOf(cutoff) });
        try {
            while (c.moveToNext()) {
                new File(ctx.getFilesDir(), c.getString(1)).delete();
                ContentValues v = new ContentValues(); v.putNull("clip");
                d.update("events", v, "id=?", new String[] { String.valueOf(c.getLong(0)) });
            }
        } finally { c.close(); }
    }

    // ---------------- 打卡 ----------------
    public void putCheckIn(int kind, int val) {
        ContentValues v = new ContentValues(); v.put("ts", System.currentTimeMillis()); v.put("kind", kind); v.put("val", val);
        getWritableDatabase().insert("checkins", null, v);
    }

    public List<Rec.CheckIn> checkIns(long sinceMs) {
        ArrayList<Rec.CheckIn> out = new ArrayList<Rec.CheckIn>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,ts,kind,val FROM checkins WHERE ts>=? ORDER BY ts", new String[] { String.valueOf(sinceMs) });
        try {
            while (c.moveToNext()) {
                Rec.CheckIn k = new Rec.CheckIn(); k.id = c.getLong(0); k.ts = c.getLong(1); k.kind = c.getInt(2); k.val = c.getInt(3); out.add(k);
            }
        } finally { c.close(); }
        return out;
    }
}
