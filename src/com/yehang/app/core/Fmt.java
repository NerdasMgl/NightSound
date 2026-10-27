package com.yehang.app.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Fmt {
    private Fmt() { }

    public static String hm(int minutes) {
        if (minutes < 60) return minutes + " 分钟";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    public static String clock(long ms) { return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ms)); }
    public static String clockSec(long ms) { return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(ms)); }
    public static String day(long ms) { return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(ms)); }
    public static String dayWeek(long ms) { return new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(new Date(ms)); }
    public static String full(long ms) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms)); }

    public static String dur(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " 秒";
        return (s / 60) + " 分 " + (s % 60) + " 秒";
    }

    public static String typeName(int t) {
        switch (t) {
            case Analyzer.T_SNORE: return "鼾声";
            case Analyzer.T_APNEA: return "疑似暂停";
            case Analyzer.T_TALK: return "梦话";
            case Analyzer.T_GRIND: return "磨牙";
            case Analyzer.T_COUGH: return "咳嗽";
            default: return "其他声音";
        }
    }

    public static String f1(float v) { return String.format(Locale.CHINA, "%.1f", v); }
}
