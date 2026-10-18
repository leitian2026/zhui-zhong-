package com.tracelog.lsp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

final class Util {
    private Util() {}

    static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    static boolean isFramework(String cn) {
        return cn.startsWith("android.") || cn.startsWith("java.") || cn.startsWith("javax.")
                || cn.startsWith("dalvik.") || cn.startsWith("com.android.internal.")
                || cn.startsWith("libcore.") || cn.startsWith("sun.");
    }

    static boolean isInfra(String cn) {
        return cn.startsWith("de.robv.android.xposed.") || cn.startsWith("org.lsposed.")
                || cn.startsWith("LSPHooker_") || cn.startsWith("com.tracelog.lsp.");
    }

    /** 抓当前调用栈：去掉 Hook 框架自身的帧（含它生成的跳板类、intercept/proceed 调用链）；
     *  dropLeading=true 时再去掉最前面的系统帧，直接露出 App 代码。 */
    static List<StackTraceElement> capture(boolean dropLeading) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        // 1) Hook 回调帧（类名可能被混淆，认方法名）：它及它之前的全是框架自身
        int start = 0;
        for (int i = 0; i < st.length && i < 16; i++) {
            String m = st[i].getMethodName();
            if (m.equals("callBeforeHookedMethod") || m.equals("callAfterHookedMethod")) start = i + 1;
        }
        // 2) 紧跟的 intercept/proceed 链，以及没有行号的"系统类"（Hook 框架生成的跳板，如 android.app.Xxx.drawText）
        while (start < st.length) {
            StackTraceElement e = st[start];
            String m = e.getMethodName();
            boolean trampoline = e.getLineNumber() < 0 && !e.isNativeMethod() && e.getClassName().startsWith("android.");
            if (m.equals("intercept") || m.equals("proceed") || trampoline) start++;
            else break;
        }
        ArrayList<StackTraceElement> out = new ArrayList<>(st.length);
        boolean leading = dropLeading;
        for (int i = start; i < st.length; i++) {
            StackTraceElement e = st[i];
            String cn = e.getClassName();
            if (isInfra(cn)) continue;
            if (leading && isFramework(cn)) continue;
            leading = false;
            out.add(e);
        }
        if (out.isEmpty() && dropLeading) return capture(false);
        return out;
    }

    static boolean hasFrame(List<StackTraceElement> fr, String className) {
        for (StackTraceElement e : fr) if (e.getClassName().equals(className)) return true;
        return false;
    }

    static String format(List<StackTraceElement> fr, int depth) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(depth, fr.size());
        for (int i = 0; i < n; i++) sb.append("    at ").append(fr.get(i).toString()).append('\n');
        if (fr.size() > n) sb.append("    ... ").append(fr.size() - n).append(" more\n");
        return sb.toString();
    }

    static int signature(List<StackTraceElement> fr) {
        int h = 17;
        int n = Math.min(6, fr.size());
        for (int i = 0; i < n; i++) {
            StackTraceElement e = fr.get(i);
            h = h * 31 + e.getClassName().hashCode();
            h = h * 31 + e.getMethodName().hashCode();
        }
        return h;
    }

    static String joined(List<StackTraceElement> fr) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : fr) sb.append(e.getClassName()).append('.').append(e.getMethodName()).append(' ');
        return sb.toString();
    }

    /** 去掉换行（保证每条日志头只占一行）并截断。 */
    static String clean(String s, int max) {
        if (s == null) return "null";
        String t = s.replace("\r", "\\r").replace("\n", "\\n");
        if (t.length() > max) t = t.substring(0, max) + "…(" + s.length() + ")";
        return t;
    }

    static String hex(int c) {
        return String.format("0x%08X", c);
    }

    // ------------------------------------------------------------ 字段快照

    /**
     * 把对象周围的状态列出来：沿字段往下最多 4 层（只进 App 自己的类，kotlin Lazy 取值），
     * 数值 / 布尔 / 文字直接写，LiveData 写它当前的值。每行以空格开头（日志里以 # 开头的才算新记录）。
     * 调用前必须已经设了 BUSY，免得读 LiveData 时又触发被追踪的方法。
     */
    static String dumpFields(Object o, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try {
            dumpInto(o, "this", 0, new IdentityHashMap<Object, Boolean>(), sb, new int[]{maxLines});
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static final Object NONE = new Object();

    private static void dumpInto(Object o, String path, int depth, IdentityHashMap<Object, Boolean> seen, StringBuilder sb, int[] left) {
        if (o == null || left[0] <= 0 || depth > 3 || seen.put(o, Boolean.TRUE) != null) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class && !skipClass(c.getName()); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (left[0] <= 0) return;
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    String p = path + "." + f.getName();
                    if (v instanceof Number || v instanceof Boolean) line(sb, left, p + " = " + v);
                    else if (v instanceof CharSequence) line(sb, left, p + " = \"" + clean(v.toString(), 30) + "\"");
                    else dumpObject(v, p, depth, seen, sb, left);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void dumpObject(Object v, String p, int depth, IdentityHashMap<Object, Boolean> seen, StringBuilder sb, int[] left) {
        Class<?> k = v.getClass();
        String n = k.getName();
        if (n.contains("Lazy")) {
            try {
                dumpObject(k.getMethod("getValue").invoke(v), p, depth, seen, sb, left);
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
                line(sb, left, p + " (LiveData) = " + clean(String.valueOf(lv), 40));
            } else {
                line(sb, left, p + " (LiveData) = " + lv.getClass().getName());
                dumpInto(lv, p + ".value", depth + 1, seen, sb, left);
            }
            return;
        }
        if (!skipClass(n)) dumpInto(v, p, depth + 1, seen, sb, left);
    }

    private static boolean isLiveData(Class<?> k) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().contains("LiveData")) return true;
        }
        return false;
    }

    private static boolean skipClass(String cn) {
        return isFramework(cn) || cn.startsWith("kotlin.") || cn.startsWith("kotlinx.") || cn.startsWith("androidx.")
                || isInfra(cn);
    }

    private static void line(StringBuilder sb, int[] left, String s) {
        sb.append("    ").append(clean(s, 100)).append('\n');
        left[0]--;
    }

    static String str(Object o) {
        try {
            if (o == null) return "null";
            if (o instanceof CharSequence) return "\"" + clean(o.toString(), 160) + "\"";
            if (o instanceof Number || o instanceof Boolean || o instanceof Character) return o.toString();
            if (o instanceof Class) return "class " + ((Class<?>) o).getName();
            Class<?> c = o.getClass();
            if (c.isArray()) {
                String s;
                if (o instanceof int[]) s = Arrays.toString((int[]) o);
                else if (o instanceof long[]) s = Arrays.toString((long[]) o);
                else if (o instanceof float[]) s = Arrays.toString((float[]) o);
                else if (o instanceof double[]) s = Arrays.toString((double[]) o);
                else if (o instanceof short[]) s = Arrays.toString((short[]) o);
                else if (o instanceof boolean[]) s = Arrays.toString((boolean[]) o);
                else if (o instanceof Object[]) s = Arrays.toString((Object[]) o);
                else s = c.getSimpleName() + "(len=" + java.lang.reflect.Array.getLength(o) + ")";
                return clean(s, 160);
            }
            return clean(String.valueOf(o), 160);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }
}
