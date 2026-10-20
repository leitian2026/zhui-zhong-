package com.textrec.lsp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/**
 * 把一个对象周围的状态列出来（方法追踪里「记对象字段值」用）：沿字段往下最多 maxDepth 层（默认 3，0 = 只列自己的字段），
 * 数值 / 布尔 / 文字直接写；LiveData 写它当前的值；kotlin Lazy 取值后继续往下。
 * 只进 App 自己的类，框架类、kotlin、androidx 一律不进。每行以空格开头。
 * 调用前 Recorder 已经设了 BUSY，读 LiveData 时再触发被追踪的方法也不会递归记录。
 */
final class FieldDump {
    private FieldDump() {}

    private static final Object NONE = new Object();

    /** 额外放行的包 / 类名前缀（默认 androidx.compose.ui.text.）；由 Recorder 在读配置时设置。 */
    private static volatile String[] extra = new String[]{"androidx.compose.ui.text."};

    static void setExtra(String csv) {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        if (csv != null) {
            for (String t : csv.split("[,\\n\\r]+")) {
                t = t.trim();
                if (!t.isEmpty()) l.add(t);
            }
        }
        extra = l.toArray(new String[0]);
    }

    private static boolean isExtra(String cn) {
        for (String x : extra) if (cn.startsWith(x)) return true;
        return false;
    }

    static String dump(Object o, int maxLines) {
        return dump(o, maxLines, 3);
    }

    /** maxDepth：往子对象里最多展开几层；0 = 只列 o 自己的数值 / 布尔 / 文字字段，不进子对象。 */
    static String dump(Object o, int maxLines, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        try {
            into(o, "this", 0, maxDepth, new IdentityHashMap<Object, Boolean>(), sb, new int[]{maxLines});
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void into(Object o, String path, int depth, int maxDepth, IdentityHashMap<Object, Boolean> seen, StringBuilder sb, int[] left) {
        if (o == null || left[0] <= 0 || depth > maxDepth || seen.put(o, Boolean.TRUE) != null) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class && !skip(c.getName()); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (left[0] <= 0) return;
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    String p = path + "." + f.getName();
                    if (v instanceof Number || v instanceof Boolean) line(sb, left, p + " = " + v);
                    else if (v instanceof CharSequence) line(sb, left, p + " = \"" + cut(v.toString(), 30) + "\"");
                    else object(v, p, depth, maxDepth, seen, sb, left);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void object(Object v, String p, int depth, int maxDepth, IdentityHashMap<Object, Boolean> seen, StringBuilder sb, int[] left) {
        if (v instanceof java.util.List) { // 列表：只看前 3 个元素（Compose 的 paragraphInfoList 之类）
            int cnt = 0;
            for (Object e : (java.util.List<?>) v) {
                if (cnt >= 3 || left[0] <= 0) break;
                String ep = p + "[" + cnt + "]";
                cnt++;
                if (e == null) continue;
                if (e instanceof Number || e instanceof Boolean) line(sb, left, ep + " = " + e);
                else if (e instanceof CharSequence) line(sb, left, ep + " = \"" + cut(e.toString(), 30) + "\"");
                else object(e, ep, depth, maxDepth, seen, sb, left);
            }
            return;
        }
        Class<?> k = v.getClass();
        String n = k.getName();
        if (n.contains("Lazy")) {
            try {
                object(k.getMethod("getValue").invoke(v), p, depth, maxDepth, seen, sb, left);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (isLiveData(k)) {
            Object lv = NONE;
            try {
                lv = k.getMethod("getValue").invoke(v);
            } catch (Throwable ignored) {
            }
            if (lv == NONE) return;
            if (lv == null) {
                line(sb, left, p + " (LiveData) = null");
            } else if (lv instanceof Number || lv instanceof Boolean || lv instanceof CharSequence) {
                line(sb, left, p + " (LiveData) = " + cut(String.valueOf(lv), 40));
            } else {
                line(sb, left, p + " (LiveData) = " + lv.getClass().getName());
                into(lv, p + ".value", depth + 1, maxDepth, seen, sb, left);
            }
            return;
        }
        if (!skip(n)) into(v, p, depth + 1, maxDepth, seen, sb, left);
    }

    private static boolean isLiveData(Class<?> k) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().contains("LiveData")) return true;
        }
        return false;
    }

    private static boolean skip(String cn) {
        if (isExtra(cn)) return false;
        return cn.startsWith("android.") || cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("dalvik.")
                || cn.startsWith("kotlin.") || cn.startsWith("kotlinx.") || cn.startsWith("androidx.")
                || cn.startsWith("de.robv.android.xposed.") || cn.startsWith("org.lsposed.") || cn.startsWith("com.textrec.lsp.");
    }

    private static void line(StringBuilder sb, int[] left, String s) {
        sb.append("    ").append(cut(s, 100)).append('\n');
        left[0]--;
    }

    private static String cut(String s, int max) {
        String t = s.replace("\r", "\\r").replace("\n", "\\n");
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
