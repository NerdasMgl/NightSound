package com.yehang.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用设置。全部保存在本机。 */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) { sp = c.getSharedPreferences("yehang", Context.MODE_PRIVATE); }

    public int sensitivity() { return sp.getInt("sens", 1); }               // 0 低 1 中 2 高
    public void setSensitivity(int v) { sp.edit().putInt("sens", v).apply(); }

    public int goalMin() { return sp.getInt("goal", 450); }                 // 目标睡眠时长（分钟）
    public void setGoalMin(int v) { sp.edit().putInt("goal", v).apply(); }

    public boolean saveClips() { return sp.getBoolean("clips", true); }
    public void setSaveClips(boolean v) { sp.edit().putBoolean("clips", v).apply(); }

    public int clipDays() { return sp.getInt("clipDays", 7); }
    public void setClipDays(int v) { sp.edit().putInt("clipDays", v).apply(); }

    public boolean grind() { return sp.getBoolean("grind", true); }
    public void setGrind(boolean v) { sp.edit().putBoolean("grind", v).apply(); }

    public boolean introDone() { return sp.getBoolean("intro", false); }
    public void setIntroDone() { sp.edit().putBoolean("intro", true).apply(); }

    public boolean batteryHintDone() { return sp.getBoolean("batHint", false); }
    public void setBatteryHintDone() { sp.edit().putBoolean("batHint", true).apply(); }

    public String pendingTags() { return sp.getString("pendingTags", ""); }
    public void setPendingTags(String v) { sp.edit().putString("pendingTags", v).apply(); }

    public boolean bedsideOn() { return sp.getBoolean("bedsideOn", false); }   // 床头屏常亮
    public void setBedsideOn(boolean v) { sp.edit().putBoolean("bedsideOn", v).apply(); }

    public long activeNight() { return sp.getLong("activeNight", 0); }
    public void setActiveNight(long id) { sp.edit().putLong("activeNight", id).apply(); }
}
