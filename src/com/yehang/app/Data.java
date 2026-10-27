package com.yehang.app;

import com.yehang.app.core.Rec;
import com.yehang.app.core.Summary;

import java.util.ArrayList;
import java.util.List;

/** 读库并计算报告的小工具。 */
public final class Data {
    private Data() { }

    public static Summary summary(Db db, Prefs prefs, Rec.Night n) {
        return Summary.compute(n, db.epochs(n.id), db.events(n.id), prefs.goalMin());
    }

    /** 最近 k 晚（新的在前）。 */
    public static ArrayList<Summary> recent(Db db, Prefs prefs, int k, List<Rec.Night> outNights) {
        ArrayList<Summary> out = new ArrayList<Summary>();
        for (Rec.Night n : db.finishedNights(k)) {
            out.add(summary(db, prefs, n));
            if (outNights != null) outNights.add(n);
        }
        return out;
    }

    /** 最近 days 天里“有点困 / 很困”相关的自评值。 */
    public static ArrayList<Integer> recentRatings(Db db, int days) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (Rec.CheckIn c : db.checkIns(System.currentTimeMillis() - days * 86400000L)) out.add(c.val);
        return out;
    }
}
