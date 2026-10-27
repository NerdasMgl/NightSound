package com.yehang.app;

/** 监测服务与界面之间共享的实时状态（同进程内，直接读取）。 */
public final class MonitorState {
    private MonitorState() { }

    public static volatile boolean running;
    public static volatile long nightId;
    public static volatile long startMs;
    public static volatile float level, floor;
    public static volatile int snoreCount, apneaCount, soundCount;
    public static volatile long lastSnoreMs;
    public static volatile float lux = -1;
    public static volatile boolean accelOk;
    public static volatile boolean micSilent;          // 长时间完全没有信号
    public static volatile String error;               // 非空表示出现了需要告诉用户的问题
    public static volatile String audioSource = "未启动";
    public static volatile long finishedNight;         // 刚刚结束的那一晚（界面据此跳转到报告）
    public static volatile boolean bedsideKeepOn;      // 床头屏常亮中：亮屏不计为“用手机”
    public static volatile boolean modelOn;            // 本次监测是否用上了识别模型
    public static volatile String stopReason;          // 非用户主动结束的原因
}
