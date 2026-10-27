package com.yehang.app.core;

import java.util.ArrayList;
import java.util.List;

/** 就医提示与简单的建议文案。夜莺不诊断；阈值是产品内的参考，不是医学标准。 */
public final class Advice {
    private Advice() { }

    public static final String SEE_DOCTOR =
            "这几天的记录显示夜间呼吸可能有问题，白天也容易困。可以去睡眠门诊、呼吸科或耳鼻喉科做一次专业的睡眠监测。";
    public static final String DONT_DRIVE = "白天开车或操作机器时觉得困，请先不要开车。";

    /** 最近的夜（新的在前）与最近的白天自评（任意顺序，值 1..5）。返回触发的原因，空表示不提示。 */
    public static String seeDoctorReason(List<Summary> recent, List<Integer> sleepyRatings) {
        if (recent.size() >= 5) {
            boolean apnea = true, snore = true;
            for (int i = 0; i < 5; i++) {
                Summary s = recent.get(i);
                if (s.tooShort) { apnea = false; snore = false; break; }
                if (s.apneaIdx < 15f) apnea = false;
                if (s.snorePct <= 30f) snore = false;
            }
            if (apnea) return "连续 5 晚疑似暂停指数不低于 15";
            if (snore && mostlySleepy(sleepyRatings)) return "连续 5 晚鼾声占睡眠超过 30%，而且这几天白天多数时候觉得困";
        }
        return null;
    }

    public static boolean mostlySleepy(List<Integer> ratings) {
        if (ratings.size() < 3) return false;
        int n = 0;
        for (int v : ratings) if (v >= 4) n++;
        return n * 2 > ratings.size();
    }

    /** 一晚报告里给的几句话。 */
    public static List<String> tips(Summary s) {
        ArrayList<String> out = new ArrayList<String>();
        if (s.tooShort) { out.add("这次记录不到 2 小时，不计分，也不足以看出规律。"); return out; }
        if (s.apneaIdx >= 15f) out.add("疑似暂停指数偏高（每小时 " + Math.round(s.apneaIdx) + " 次）。手机听不到气流和血氧，不能据此判断，但值得找专业机构做一次睡眠监测。");
        else if (s.apneaCount > 0) out.add("检测到 " + s.apneaCount + " 次疑似暂停：鼾声连续出现后突然安静了一段时间，再恢复。偶尔出现不必紧张，如果连续几天都有，建议留意。");
        if (s.snorePct >= 30f) out.add("鼾声占了睡眠时间的 " + Math.round(s.snorePct) + "%。侧卧、抬高床头、睡前少喝酒、鼻塞时先处理鼻子，对一部分人有帮助。");
        else if (s.snorePct >= 10f) out.add("有一定的鼾声（占睡眠 " + Math.round(s.snorePct) + "%）。可以在“今晚情况”里记下酒精、鼻塞、睡姿，几天后看看哪个与鼾声最相关。");
        if (s.latencyMin > 40) out.add("入睡用了 " + s.latencyMin + " 分钟，比较久。睡前一小时少看手机、固定上床时间会有帮助。");
        if (s.motionOk && s.awakenings >= 4) out.add("夜里醒来 " + s.awakenings + " 次。留意是否是起夜、噪声或鼾声自己吵醒。");
        if (s.noiseAvg > 38f) out.add("卧室背景噪声偏高，可能影响睡眠。");
        if (s.sleepMin > 0 && s.sleepMin < 360) out.add("这晚睡了不到 6 小时，白天犯困并不意外。");
        if (!s.motionOk) out.add("这次没有拿到可用的体动数据（手机没放在床上，或系统限制了传感器），入睡与醒来的判断只能靠声音，分期仅供参考。");
        return out;
    }

    /** 带上封顶原因的评语：分数被鼾声/暂停压住时，不再说“整体不错”这种和实际对不上的话。 */
    public static String verdictFor(Summary s) {
        if (s.tooShort) return "记录太短";
        if (s.scoreCap > 0 && s.score >= 50) return s.apneaIdx >= 5f ? "呼吸要留意" : "鼾声偏重";
        return verdict(s.score);
    }

    public static String verdict(int score) {
        if (score < 0) return "记录太短，不计分";
        if (score >= 85) return "睡得安稳";
        if (score >= 70) return "整体不错";
        if (score >= 50) return "睡得一般";
        return "睡得不太好";
    }
}
