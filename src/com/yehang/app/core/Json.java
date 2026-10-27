package com.yehang.app.core;

import java.util.List;
import java.util.Map;

/** 极简 JSON 输出（不依赖 org.json，桌面 JVM 上也能跑，方便用同一份代码生成界面预览数据）。 */
public final class Json {
    private Json() { }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        put(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void put(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { str(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(((Boolean) o) ? "true" : "false"); return; }
        if (o instanceof Float || o instanceof Double) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(String.format(java.util.Locale.US, "%.3f", d).replaceAll("0+$", "").replaceAll("\\.$", ""));
            return;
        }
        if (o instanceof Number) { sb.append(((Number) o).longValue()); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, e.getKey()); sb.append(':'); put(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<Object>) o) { if (!first) sb.append(','); first = false; put(sb, x); }
            sb.append(']');
            return;
        }
        if (o instanceof int[]) {
            int[] a = (int[]) o; sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']'); return;
        }
        if (o instanceof float[]) {
            float[] a = (float[]) o; sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); put(sb, (double) a[i]); }
            sb.append(']'); return;
        }
        str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case ' ': sb.append("\\u2028"); break;
                case ' ': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** 方便写字面量：m("a", 1, "b", "x")。 */
    public static Map<String, Object> m(Object... kv) {
        java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }
}
