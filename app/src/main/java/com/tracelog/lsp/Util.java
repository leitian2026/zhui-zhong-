package com.tracelog.lsp;

import java.util.ArrayList;
import java.util.Arrays;
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

    /** 抓当前调用栈：去掉 Hook 框架自身的帧；dropLeading=true 时再去掉最前面的系统帧，直接露出 App 代码。 */
    static List<StackTraceElement> capture(boolean dropLeading) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        ArrayList<StackTraceElement> out = new ArrayList<>(st.length);
        boolean leading = dropLeading;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (isInfra(cn)) continue;
            if (leading && isFramework(cn)) continue;
            leading = false;
            out.add(e);
        }
        if (out.isEmpty() && dropLeading) return capture(false);
        return out;
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
