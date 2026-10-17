package com.textrec.lsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 采集端过滤条件：从 RecConfig 预先解析好，热路径里只做字符串包含 / 集合查找，不分配对象、不跑正则。
 * 所有条件为空 = 不过滤（默认）。判断都发生在抓栈之前，被挡掉的调用不抓栈、不建记录，只在 Store 里累加“挡掉了多少”。
 */
final class Gate {
    static final Gate EMPTY = new Gate(new RecConfig());

    /** 触发窗口：触摸后多少毫秒内才记录；0 = 不限。 */
    final int windowMs;
    /** 触发窗口是否也用于文字 / 图标 / 颜色 / 状态 / 绘制记录（默认只管方法追踪）。 */
    final boolean windowAll;
    final boolean hasThreads, hasPages, hasViews, hasValues;
    /** 文字 / 图标 / 状态 / 绘制这类记录要不要过一遍 allowAll（全空就直接放行，省一次调用）。 */
    final boolean anyAll;

    private final String[] threads, pages, views, values;
    private final String[] exPrefix;
    private final Set<String> exMethod = new HashSet<>();
    private final Set<String> exPair = new HashSet<>();

    Gate(RecConfig c) {
        windowMs = Math.max(0, c.windowMs);
        windowAll = c.windowAll && windowMs > 0;
        threads = arr(RecConfig.splitLines(c.threadFilter), false);
        pages = arr(RecConfig.splitLines(c.pageFilter), true);
        views = arr(RecConfig.splitLines(c.viewFilter), false);

        LinkedHashSet<String> vs = new LinkedHashSet<>();
        for (String tok : RecConfig.splitLines(c.valueFilter)) expand(tok, vs);
        values = vs.toArray(new String[0]);

        ArrayList<String> pre = new ArrayList<>();
        for (String t : RecConfig.splitLines(c.excludeList)) {
            if (t.startsWith("#")) { // #方法名：任何类里的这个方法
                if (t.length() > 1) exMethod.add(t.substring(1));
            } else if (t.endsWith("#*")) { // 类#*：整个类
                if (t.length() > 2) pre.add(t.substring(0, t.length() - 2));
            } else if (t.indexOf('#') > 0) { // 类#方法
                exPair.add(t);
            } else { // 没有 #：当作类名前缀（包名 / 类名都行）
                pre.add(t);
            }
        }
        exPrefix = pre.toArray(new String[0]);

        hasThreads = threads.length > 0;
        hasPages = pages.length > 0;
        hasViews = views.length > 0;
        hasValues = values.length > 0;
        anyAll = windowAll || hasThreads || hasPages || hasViews;
    }

    private static String[] arr(List<String> l, boolean lower) {
        ArrayList<String> out = new ArrayList<>();
        for (String s : l) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            out.add(lower ? t.toLowerCase(Locale.ROOT) : t);
        }
        return out.toArray(new String[0]);
    }

    boolean excluded(String cn, String mn) {
        if (!exPair.isEmpty() && exPair.contains(cn + "#" + mn)) return true;
        for (String p : exPrefix) if (cn.startsWith(p)) return true;
        return !exMethod.isEmpty() && exMethod.contains(mn);
    }

    boolean threadOk(String name) {
        if (name == null) return false;
        for (String t : threads) if (name.contains(t)) return true;
        return false;
    }

    /** lowerPage 是小写后的页面标签（Recorder.pageLower）。 */
    boolean pageOk(String lowerPage) {
        if (lowerPage == null) return false;
        for (String t : pages) if (lowerPage.contains(t)) return true;
        return false;
    }

    /** View 类名或 id 名里包含任意一个词。 */
    boolean viewMatch(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String t : views) if (s.contains(t)) return true;
        return false;
    }

    boolean valueOk(String text) {
        if (text == null) return false;
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    // ------------------------------------------------------------ 值的多种写法

    /**
     * 一个值展开成几种常见写法，一次搜全：
     *  - 01:37 → 01:37、1:37、97、97000
     *  - 97    → 97、01:37、1:37、97000（当秒）
     *  - 97000 → 97000、01:37、1:37（当毫秒；单独的 97 太短，不加，免得到处命中）
     * 短于 3 个字符的纯数字不加入，免得把无关调用也放进来。
     */
    private static void expand(String tok, Set<String> out) {
        tok = tok.trim();
        if (tok.isEmpty()) return;
        out.add(tok);
        if (tok.indexOf(':') > 0) {
            long sec = parseClock(tok);
            if (sec >= 0) {
                addNum(out, String.valueOf(sec));
                addNum(out, String.valueOf(sec * 1000L));
            }
            return;
        }
        long n;
        try {
            n = Long.parseLong(tok);
        } catch (Throwable t) {
            return;
        }
        if (n < 0) return;
        if (n < 6000) { // 当作秒
            addClock(out, n);
            addNum(out, String.valueOf(n * 1000L));
        }
        if (n >= 1000) { // 当作毫秒
            long s = n / 1000L;
            if (s < 6000) addClock(out, s);
            addNum(out, String.valueOf(s));
        }
    }

    private static long parseClock(String s) {
        String[] ps = s.split(":");
        if (ps.length < 2 || ps.length > 3) return -1;
        long v = 0;
        for (String p : ps) {
            try {
                v = v * 60L + Long.parseLong(p.trim());
            } catch (Throwable t) {
                return -1;
            }
        }
        return v;
    }

    private static void addClock(Set<String> out, long sec) {
        long m = sec / 60L, s = sec % 60L;
        out.add(String.format(Locale.US, "%02d:%02d", m, s));
        out.add(String.format(Locale.US, "%d:%02d", m, s));
    }

    private static void addNum(Set<String> out, String s) {
        if (s.length() >= 3) out.add(s);
    }
}
