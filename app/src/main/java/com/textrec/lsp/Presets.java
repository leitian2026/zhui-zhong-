package com.textrec.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * 预设（纯 Java，不依赖 Android）：一个名字 = 一小组「key=value」。
 * 三处共用同一份定义，不会各写各的：
 *  1. 粘贴框 / 配置里写 {@code preset=text}，粘贴时先展开成这些行，再被你显式写的字段覆盖（见 {@link PasteBlocks}）；
 *  2. 首页「我想找什么」和「采集过滤」里的快速选择按钮（{@link #applyTo}，只改预设列出的字段，别的不动）；
 *  3. 给 AI 的《写配置手册》里的预设表（文字由 {@link #docTable()} 生成）。
 * kind：scene = 要找什么（内容类开关）；filter = 采集过滤。
 */
final class Presets {
    private Presets() {}

    static final class P {
        final String name, kind, title, desc;
        final String[] kv;

        P(String name, String kind, String title, String desc, String... kv) {
            this.name = name;
            this.kind = kind;
            this.title = title;
            this.desc = desc;
            this.kv = kv;
        }
    }

    private static final String[] CONTENT_OFF = {"traceSet=false", "traceDraw=false", "traceColor=false", "autoParams=false",
            "traceIcons=false", "traceSetOnly=false", "traceState=false"};

    /** 内容类开关全关，但不含 except 这一项（场景预设要把它单独打开，不能同一个预设里先关再开）。 */
    private static String[] offExcept(String except) {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        for (String s : CONTENT_OFF) if (!s.startsWith(except + "=")) l.add(s);
        l.add(except + "=true");
        return l.toArray(new String[0]);
    }

    private static String[] cat(String[] a, String... b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static final P[] ALL = {
            new P("text", "scene", "文字是谁设置 / 谁画的 / 什么颜色",
                    "最常用。配合 keywordMode=true + keywords=那段文字，能大幅缩小范围。",
                    "traceSet=true", "traceDraw=true", "traceColor=true", "autoParams=true",
                    "traceIcons=false", "traceSetOnly=false", "traceState=false", "stackDepth=12"),
            new P("icons", "scene", "图标 / 按钮图片是谁设的",
                    "记录 ImageView 的图片和 contentDescription（资源名、缩略图）。",
                    offExcept("traceIcons")),
            new P("state", "scene", "控件透明度 / 可见 / 选中是谁改的",
                    "记录 alpha / visibility / selected 的变化；可在 viewFilter 里填控件类名或 id 缩小范围。",
                    offExcept("traceState")),
            new P("trace", "scene", "某个方法被谁调用、参数和返回值",
                    "只记方法追踪：文字 / 图标 / 颜色 / 状态记录全关；方法清单写在 traceMethods。",
                    CONTENT_OFF),
            new P("statehook", "scene", "找「判断状态」的钩子 / 谁改了某个字段",
                    "方法追踪 + 数据树 + 观察点 + 对象归属，并排除通用基础设施、压低高频方法；任务清单要写成「对照 / 做这件事 / 做反向的事」。",
                    cat(CONTENT_OFF, "treeOn=true", "watchAuto=true", "annotOwners=true", "annotSweep=true", "annotFrames=true",
                            "excludeInfra=true", "quietCount=50", "quietSpanMs=3000")),
            new P("viewtree", "scene", "一块界面里有哪些控件、谁在谁里面",
                    "任务开始 / 结束 / 点标记时各抓一份视图树；用 viewDumpKey 指定以哪个控件为准。",
                    "viewDump=true", "traceSet=true", "traceDraw=false", "traceColor=false", "autoParams=false",
                    "traceIcons=false", "traceSetOnly=false", "traceState=false"),
            new P("tap", "scene", "不知道控件叫什么，只知道它在屏幕哪里",
                    "点一下那个位置，导出里列出这个点上所有控件（外层到最上层）。",
                    "viewTap=true", "traceSet=true", "traceDraw=false", "traceColor=false", "autoParams=false",
                    "traceIcons=false", "traceSetOnly=false", "traceState=false", "traceExclusive=false"),
            new P("all", "scene", "全部打开（最全，最卡）",
                    "文字 / 图标 / 没显示的文字 / 状态全开，栈深 30。只在调试时用。",
                    "traceSet=true", "traceDraw=true", "traceColor=true", "autoParams=true",
                    "traceIcons=true", "traceSetOnly=true", "traceState=true", "stackDepth=30"),
            new P("hook", "filter", "找钩子（最窄）",
                    "触摸后 1.5 秒内才记、排除通用基础设施、压低高频方法。只设置采集过滤。",
                    "windowMs=1500", "windowAll=false", "excludeInfra=true", "threadMode=0", "valueClock=false",
                    "quietCount=50", "quietSpanMs=3000"),
            new P("nofilter", "filter", "关掉全部过滤",
                    "不限触摸窗口 / 线程 / 页面 / 控件 / 值，不排除任何东西。",
                    "windowMs=0", "windowAll=false", "excludeInfra=false", "threadMode=0", "valueClock=false",
                    "quietCount=0", "threadFilter=", "pageFilter=", "viewFilter=", "excludeList=", "valueFilter="),
    };

    static P get(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (P p : ALL) if (p.name.equalsIgnoreCase(n)) return p;
        return null;
    }

    /** 全部预设名，逗号分隔。 */
    static String names() {
        StringBuilder sb = new StringBuilder();
        for (P p : ALL) sb.append(sb.length() > 0 ? "、" : "").append(p.name);
        return sb.toString();
    }

    static List<String> nameList() {
        ArrayList<String> l = new ArrayList<>();
        for (P p : ALL) l.add(p.name);
        return l;
    }

    /** 预设的 {key, value} 列表。 */
    static List<String[]> pairs(String name) {
        ArrayList<String[]> out = new ArrayList<>();
        P p = get(name);
        if (p == null) return out;
        for (String s : p.kv) {
            int i = s.indexOf('=');
            out.add(new String[]{s.substring(0, i), s.substring(i + 1)});
        }
        return out;
    }

    /** 把预设写进配置对象：只改预设里列出的字段，别的不动。返回实际改了几个字段的值。 */
    static int applyTo(RecConfig c, String name) {
        int changed = 0;
        for (String[] kv : pairs(name)) {
            try {
                java.lang.reflect.Field f = RecConfig.class.getField(kv[0]);
                Class<?> t = f.getType();
                if (t == boolean.class) {
                    boolean v = Boolean.parseBoolean(kv[1]);
                    if (f.getBoolean(c) != v) changed++;
                    f.setBoolean(c, v);
                } else if (t == int.class) {
                    int v = Integer.parseInt(kv[1]);
                    if (f.getInt(c) != v) changed++;
                    f.setInt(c, v);
                } else if (t == String.class) {
                    if (!kv[1].equals(f.get(c))) changed++;
                    f.set(c, kv[1]);
                }
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    /** 当前配置是否正好满足某个预设列出的全部字段（首页用来高亮按钮）。 */
    static boolean matches(RecConfig c, String name) {
        List<String[]> ps = pairs(name);
        if (ps.isEmpty()) return false;
        for (String[] kv : ps) {
            try {
                java.lang.reflect.Field f = RecConfig.class.getField(kv[0]);
                Class<?> t = f.getType();
                if (t == boolean.class) {
                    if (f.getBoolean(c) != Boolean.parseBoolean(kv[1])) return false;
                } else if (t == int.class) {
                    if (f.getInt(c) != Integer.parseInt(kv[1])) return false;
                } else if (t == String.class) {
                    if (!kv[1].trim().equals(String.valueOf(f.get(c)).trim())) return false;
                }
            } catch (Throwable e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 当前配置最贴近的预设：在 kind 这一类里，满足全部字段的预设中字段最多（最具体）的那个，没有返回 null。
     * 例：只设了「文字全关」时匹配 trace；statehook 比 trace 多设了几项，配置满足 statehook 时选 statehook 而不是 trace。
     */
    static P best(RecConfig c, String kind) {
        P hit = null;
        for (P p : ALL) {
            if (!p.kind.equals(kind) || !matches(c, p.name)) continue;
            if (hit == null || p.kv.length > hit.kv.length) hit = p;
        }
        return hit;
    }

    /** 给文档用的预设表（Markdown）。 */
    static String docTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("| 预设 | 用来找什么 | 展开成 |\n|---|---|---|\n");
        for (P p : ALL) {
            StringBuilder kv = new StringBuilder();
            for (String s : p.kv) kv.append(kv.length() > 0 ? " " : "").append('`').append(s).append('`');
            sb.append("| `").append(p.name).append("` | ").append(p.title).append(" | ").append(kv).append(" |\n");
        }
        return sb.toString();
    }
}
