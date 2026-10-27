package com.yehang.app.core;

/** 数据记录（与数据库行一一对应）。 */
public final class Rec {
    private Rec() { }

    public static final class Night {
        public long id, startMs, endMs, lastBeat;
        public int flags;      // 1 = 监测被意外中断
        public int sens;
        public String tags = "";   // 逗号分隔：咖啡因,酒精,…,姿势:仰卧
    }

    /** 每分钟一条。 */
    public static final class Epoch {
        public long t;          // 该分钟开始的时间戳
        public float act;       // 平均体动（m/s²），-1 表示没有加速度计数据
        public float actMax;    // 本分钟最大体动
        public float noise;     // 本分钟噪声底（相对 dB）
        public float snoreDb;   // 本分钟最响鼾声（0 = 无）
        public int snoreSec;
        public int evtCnt;      // 非鼾声声音事件数（咳嗽、梦话、其他）
        public float lux;       // 平均照度，-1 表示没有数据
        public int screenSec;   // 屏幕点亮的秒数
    }

    public static final class Event {
        public long id, nightId;
        public int type;        // Analyzer.T_*
        public long startMs, endMs;
        public float peakDb, meanDb, conf, extra;
        public String clip;     // 相对路径，可能为 null
    }

    public static final class CheckIn {
        public long id, ts;
        public int kind;        // 0 早晨 1 下午 2 晚上
        public int val;         // 1..5
    }
}
